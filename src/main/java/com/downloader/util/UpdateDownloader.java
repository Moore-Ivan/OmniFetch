package com.downloader.util;

import java.io.File;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URI;

/**
 * 应用更新包下载器。
 * <ul>
 *   <li>基于 HTTP Range 断点续传：下载中写入 {@code *.part} 临时文件，暂停/继续复用已下载部分；</li>
 *   <li>支持取消（中断连接并删除临时文件）；</li>
 *   <li>回调在虚拟下载线程上触发，界面层需自行切回 EDT；</li>
 *   <li>约每 200ms 回调一次进度（含基于采样窗口计算的瞬时速度）。</li>
 * </ul>
 */
public final class UpdateDownloader {

    /** 下载状态回调（均在下载线程触发） */
    public interface Listener {
        /** 连接建立，total 为文件总大小（可能为 -1 表示未知） */
        default void onStart(long total) {
        }

        /** 下载已暂停（连接已断开，已下载部分保留在 part 文件中） */
        default void onPaused() {
        }

        /**
         * 进度回调
         *
         * @param downloaded   已下载字节
         * @param total        总字节（-1 未知）
         * @param bytesPerSec  瞬时速度（字节/秒）
         */
        void onProgress(long downloaded, long total, long bytesPerSec);

        /** 下载完成，file 为最终安装包 */
        void onComplete(File file);

        /** 下载出错（非取消） */
        void onError(String message);

        /** 用户取消，临时文件已清理 */
        default void onCancelled() {
        }
    }

    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 30_000;
    private static final int BUFFER_SIZE = 64 * 1024;
    private static final long PROGRESS_INTERVAL_MS = 200;

    private final String fileUrl;
    private final File finalFile;
    private final File partFile;
    private final Listener listener;

    private volatile Thread worker;
    private volatile boolean pauseRequested;
    private volatile boolean cancelRequested;
    private volatile long downloaded;
    private volatile long total = -1;

    public UpdateDownloader(String fileUrl, File finalFile, Listener listener) {
        this.fileUrl = fileUrl;
        this.finalFile = finalFile;
        this.partFile = new File(finalFile.getParentFile(), finalFile.getName() + ".part");
        this.listener = listener;
    }

    /** 开始（或首次）下载。已完成的最终文件不会被覆盖。 */
    public synchronized void start() {
        if (worker != null || finalFile.exists()) {
            return;
        }
        pauseRequested = false;
        cancelRequested = false;
        spawnWorker();
    }

    /** 请求暂停：当前连接读完一个缓冲块后停止，已下载内容保留。 */
    public synchronized void pause() {
        pauseRequested = true;
    }

    /** 从断点继续下载。 */
    public synchronized void resume() {
        if (worker != null || cancelRequested || finalFile.exists()) {
            return;
        }
        pauseRequested = false;
        spawnWorker();
    }

    /** 取消下载并清理临时文件。 */
    public synchronized void cancel() {
        cancelRequested = true;
        Thread w = worker;
        if (w != null) {
            w.interrupt();
        }
    }

    private synchronized void spawnWorker() {
        worker = Thread.ofVirtual().name("update-downloader").start(this::runDownload);
    }

    private synchronized void clearWorker() {
        worker = null;
    }

    private void runDownload() {
        HttpURLConnection conn = null;
        try {
            long existing = partFile.exists() ? partFile.length() : 0;
            downloaded = existing;

            // 手动跟随重定向：GitHub 资产会 302 到 objects.githubusercontent.com。
            // 不用自动跟随的原因：
            // 1) 自动跟随打开的新连接不会继承 HttpTrust 的信任配置（精简 JRE cacerts 不全时重定向后 PKIX）；
            // 2) 手动跟随可在连接被拒时指出具体主机（hosts 失效/代理未开/防火墙的排查依据）
            String currentUrl = fileUrl;
            int code;
            int redirects = 0;
            while (true) {
                conn = openConnection(currentUrl, existing);
                try {
                    code = conn.getResponseCode();
                } catch (java.io.IOException e) {
                    throw enrichConnectionError(currentUrl, e);
                }
                if (code >= 300 && code < 400) {
                    String location = conn.getHeaderField("Location");
                    conn.disconnect();
                    conn = null;
                    if (location == null || location.isBlank()) {
                        throw new java.io.IOException("服务器重定向响应缺少 Location 头");
                    }
                    if (redirects++ >= 10) {
                        throw new java.io.IOException("重定向次数过多（超过 10 次）");
                    }
                    currentUrl = URI.create(currentUrl).resolve(location).toString();
                    String lower = currentUrl.toLowerCase(java.util.Locale.ROOT);
                    if (!lower.startsWith("https://") && !lower.startsWith("http://")) {
                        throw new java.io.IOException("重定向地址协议不受支持：" + currentUrl);
                    }
                    continue;
                }
                break;
            }

            if (code == 206) {
                // 断点续传：优先从 Content-Range 取总大小
                total = parseTotalFromContentRange(conn.getHeaderField("Content-Range"));
                if (total <= 0) {
                    long contentLength = conn.getContentLengthLong();
                    total = contentLength > 0 ? existing + contentLength : -1;
                }
            } else if (code == 200) {
                // 服务器不支持 Range（或无 part 文件）：从头下载
                if (existing > 0 && !partFile.delete()) {
                    throw new java.io.IOException("无法重置临时文件：" + partFile.getAbsolutePath());
                }
                existing = 0;
                downloaded = 0;
                total = conn.getContentLengthLong();
            } else {
                throw new java.io.IOException("服务器返回异常状态码：" + code);
            }

            listener.onStart(total);

            try (InputStream in = conn.getInputStream();
                 RandomAccessFile raf = new RandomAccessFile(partFile, "rw")) {
                raf.seek(existing);
                byte[] buffer = new byte[BUFFER_SIZE];

                // 瞬时速度采样窗口
                long windowStart = System.nanoTime();
                long windowBytes = downloaded;
                long lastEmit = System.currentTimeMillis();

                int n;
                while (!cancelRequested && !pauseRequested && (n = in.read(buffer)) >= 0) {
                    raf.write(buffer, 0, n);
                    downloaded += n;

                    long now = System.currentTimeMillis();
                    if (now - lastEmit >= PROGRESS_INTERVAL_MS) {
                        double elapsedSec = (System.nanoTime() - windowStart) / 1_000_000_000.0;
                        long speed = elapsedSec > 0 ? (long) ((downloaded - windowBytes) / elapsedSec) : 0;
                        listener.onProgress(downloaded, total, speed);
                        // 重置窗口，速度反映最近 200ms
                        windowStart = System.nanoTime();
                        windowBytes = downloaded;
                        lastEmit = now;
                    }
                }
            } finally {
                conn.disconnect();
                conn = null;
            }

            if (cancelRequested) {
                if (partFile.exists() && !partFile.delete()) {
                    System.err.println("取消下载后无法删除临时文件: " + partFile.getAbsolutePath());
                }
                listener.onCancelled();
            } else if (pauseRequested) {
                listener.onPaused();
            } else {
                if (total > 0 && downloaded < total) {
                    throw new java.io.IOException("下载不完整（" + downloaded + "/" + total + " 字节），可稍后继续");
                }
                if (finalFile.exists() && !finalFile.delete()) {
                    throw new java.io.IOException("无法覆盖已存在的文件：" + finalFile.getAbsolutePath());
                }
                if (!partFile.renameTo(finalFile)) {
                    // 跨盘 rename 失败时回退到复制
                    java.nio.file.Files.move(partFile.toPath(), finalFile.toPath(),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                listener.onProgress(downloaded, total > 0 ? total : downloaded, 0);
                listener.onComplete(finalFile);
            }
        } catch (Exception e) {
            if (conn != null) {
                conn.disconnect();
            }
            if (cancelRequested) {
                if (partFile.exists() && !partFile.delete()) {
                    System.err.println("取消下载后无法删除临时文件: " + partFile.getAbsolutePath());
                }
                listener.onCancelled();
            } else {
                listener.onError(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            }
        } finally {
            clearWorker();
        }
    }

    /**
     * 建立一次下载连接（重定向链中的每一跳都调用本方法），
     * 确保 HttpTrust 信任配置作用于最终资源主机，并统一携带 UA 与 Range。
     */
    private static HttpURLConnection openConnection(String urlStr, long existing)
            throws java.io.IOException {
        HttpURLConnection conn;
        try {
            conn = (HttpURLConnection) URI.create(urlStr).toURL().openConnection();
        } catch (IllegalArgumentException e) {
            throw new java.io.IOException("下载地址无效：" + urlStr, e);
        }
        // 信任所有证书（含重定向后的 objects.githubusercontent.com）
        if (conn instanceof javax.net.ssl.HttpsURLConnection https) {
            HttpTrust.apply(https);
        }
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setRequestProperty("User-Agent", "OmniFetch-Updater");
        conn.setInstanceFollowRedirects(false); // 由调用方手动跟随
        if (existing > 0) {
            conn.setRequestProperty("Range", "bytes=" + existing + "-");
        }
        try {
            conn.connect();
        } catch (java.io.IOException e) {
            conn.disconnect();
            throw enrichConnectionError(urlStr, e);
        }
        return conn;
    }

    /**
     * 把连接阶段的底层异常翻译成带主机信息与排查建议的错误：
     * DNS 失败 / 连接被拒是 GitHub 资源 CDN 最常见的两类网络问题。
     */
    private static java.io.IOException enrichConnectionError(String urlStr, java.io.IOException e) {
        String host;
        try {
            host = URI.create(urlStr).getHost();
        } catch (Exception ex) {
            return e;
        }
        if (host == null) {
            return e;
        }
        String hint;
        if (e instanceof java.net.UnknownHostException) {
            hint = "无法解析域名 " + host + "（DNS 解析失败），请检查网络连接、系统代理或 hosts 文件";
        } else if (e instanceof java.net.ConnectException) {
            hint = "无法连接 " + host + "（连接被拒绝）。常见原因：系统代理/VPN 未开启或端口已失效、"
                    + "hosts 文件中的 GitHub 加速条目失效、防火墙拦截；也可点「查看发布页」手动下载";
        } else {
            return e;
        }
        return new java.io.IOException(hint, e);
    }

    /** 解析形如 {@code bytes 100-199/1000} 的 Content-Range 总长度，失败返回 -1。 */
    private static long parseTotalFromContentRange(String contentRange) {
        if (contentRange == null) {
            return -1;
        }
        int slash = contentRange.lastIndexOf('/');
        if (slash < 0 || slash + 1 >= contentRange.length()) {
            return -1;
        }
        String totalPart = contentRange.substring(slash + 1).trim();
        if ("*".equals(totalPart)) {
            return -1;
        }
        try {
            return Long.parseLong(totalPart);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * 更新包保存目录：程序根目录下的 {@code updates} 文件夹（不存在时创建）。
     * 若程序根目录不可写（如安装在 Program Files 且无权限），回退到用户主目录下的同名文件夹。
     */
    public static File defaultDownloadDir() {
        File updatesDir = new File("updates").getAbsoluteFile();
        if ((updatesDir.isDirectory() || updatesDir.mkdirs()) && updatesDir.canWrite()) {
            return updatesDir;
        }
        File fallback = new File(System.getProperty("user.home"), "OmniFetch-updates");
        fallback.mkdirs();
        return fallback;
    }
}
