package com.downloader.protocol;

import com.downloader.config.ConfigManager;
import com.downloader.model.ProgressCallback;
import com.downloader.pool.FtpConnectionPool;
import com.downloader.util.AppLogger;
import com.downloader.util.FileUtils;
import com.downloader.util.RateLimiter;

import java.io.*;
import java.net.*;

/**
 * FTP 下载器
 * 纯 Java Socket 实现，使用 PASV 被动模式。
 * 落盘采用 .part 隔离：全程写 {@code <最终文件>.part}，下载完成并校验后
 * 原子改名为最终文件；暂停/失败保留 .part，下次通过 REST 命令断点续传。
 */
public class FtpDownloader implements DownloadProtocol {

    private final String remotePath;
    private final String savePath;
    /** 上次运行回报的最终落盘路径（续传/重试时复用，避免二次避让改名）；可为 null */
    private final String saveFilePath;
    private final ProgressCallback callback;
    /** 任务级限速器（字节/秒，0=不限）；可为 null（插件通道） */
    private final RateLimiter limiter;
    private volatile boolean cancelled = false;

    private final FtpConnectionPool connectionPool;
    private FtpConnectionPool.FtpConnection connection;

    private static final int BUFFER_SIZE = 16_384;
    /** 数据连接读超时：持续 30 秒无任何数据判定连接死亡，抛错触发重试 */
    private static final int READ_TIMEOUT_MS = 30_000;

    private final ConfigManager configManager = ConfigManager.getInstance();

    private int getProgressIntervalMs() {
        return configManager.getInt("download.progressIntervalMs", 200);
    }

    public FtpDownloader(String host, int port, String user, String pass,
                         String remotePath, String savePath,
                         ProgressCallback callback) {
        this(host, port, user, pass, remotePath, savePath, callback, null, null);
    }

    public FtpDownloader(String host, int port, String user, String pass,
                         String remotePath, String savePath,
                         ProgressCallback callback, String saveFilePath,
                         RateLimiter limiter) {
        this.remotePath = remotePath;
        this.savePath = savePath;
        this.callback = callback;
        this.saveFilePath = saveFilePath;
        this.limiter = limiter;
        this.connectionPool = new FtpConnectionPool(host, port, user, pass);
    }

    @Override
    public void download() throws Exception {
        try {
            // 从连接池获取连接
            connection = connectionPool.getConnection();

            // 获取文件大小
            long totalSize = -1;
            try {
                String sizeResp = connection.sendCommandAndGetLine("SIZE " + remotePath);
                if (sizeResp.startsWith("213 ")) {
                    totalSize = Long.parseLong(sizeResp.substring(4).trim());
                }
            } catch (Exception ignored) {}

            String fileName = remotePath.contains("/")
                    ? remotePath.substring(remotePath.lastIndexOf('/') + 1)
                    : remotePath;
            if (fileName.isEmpty()) fileName = "ftp_download";

            callback.onConnected(fileName, totalSize);

            // 最终落盘路径：续传/重试复用上次回报路径，避免二次避让改名
            File finalFile = (saveFilePath != null && !saveFilePath.isEmpty())
                    ? new File(saveFilePath)
                    : FileUtils.buildSaveFile(savePath, fileName);
            // 重名避让后的真实落盘路径回报给任务（打开文件/续传都以它为准）
            callback.onSaveFile(finalFile);
            File partFile = new File(finalFile.getAbsolutePath() + ".part");

            // 断点续传：.part 已有数据时从其长度处 REST 续传
            long offset = 0;
            if (partFile.exists()) {
                long existing = partFile.length();
                if (totalSize > 0 && existing >= totalSize) {
                    // 上次已完成但未改名（异常残留）：直接收尾
                    FileUtils.atomicMove(partFile.toPath(), finalFile.toPath());
                    callback.onComplete();
                    return;
                }
                offset = existing;
            }
            boolean resumed = false;
            if (offset > 0) {
                resumed = tryRest(offset);
                if (!resumed) {
                    // 服务器不支持续传：丢弃旧数据从头下载
                    AppLogger.info("FTP 服务器不支持 REST 续传，从头下载: " + remotePath);
                    if (!partFile.delete() && partFile.exists()) {
                        throw new IOException("无法重置下载临时文件: " + partFile.getAbsolutePath());
                    }
                    offset = 0;
                }
            }

            // PASV 被动模式
            int[] pasvAddr = connection.enterPassiveMode();
            String dataIp = pasvAddr[0] + "." + pasvAddr[1] + "."
                    + pasvAddr[2] + "." + pasvAddr[3];
            int dataPort = pasvAddr[4];

            // 先建立数据连接再发 RETR（标准顺序）：部分服务器在 RETR 后立即推数据，
            // 晚连接会依赖 socket backlog 缓冲，极端情况下死锁
            Socket dataSocket = new Socket();
            dataSocket.connect(
                    new InetSocketAddress(dataIp, dataPort), 10000);
            // 读超时防止服务器假死导致任务永久卡在"下载中"
            dataSocket.setSoTimeout(READ_TIMEOUT_MS);

            // 发送 RETR：150（文件状态正确，即将打开数据连接）
            // 或 125（数据连接已打开，传输开始）都是合法响应
            connection.sendCommand("RETR " + remotePath, 150, 125);

            long downloaded = offset;
            try (InputStream in = dataSocket.getInputStream();
                 RandomAccessFile raf = new RandomAccessFile(partFile, "rw")) {

                // 预分配磁盘空间，避免文件系统碎片（续传时仅向上扩展，不动已有数据）
                if (totalSize > 0) {
                    raf.setLength(totalSize);
                }
                raf.seek(offset);

                byte[] buf = new byte[BUFFER_SIZE];
                int read;
                long lastTime = System.currentTimeMillis();
                long lastBytes = offset;
                int progressInterval = getProgressIntervalMs();

                while ((read = in.read(buf)) != -1) {
                    if (cancelled) {
                        break;
                    }

                    raf.write(buf, 0, read);
                    downloaded += read;
                    // 限速：按本次读入字节数获取令牌（不限速时空操作）
                    RateLimiter.acquire(limiter, read);

                    long now = System.currentTimeMillis();
                    if (now - lastTime >= progressInterval) {
                        long speed = (long) ((downloaded - lastBytes)
                                * 1000.0 / (now - lastTime));
                        callback.onProgress(downloaded, totalSize, speed);
                        lastTime = now;
                        lastBytes = downloaded;
                    }
                }
            } finally {
                FileUtils.closeQuietly(dataSocket);
            }

            if (cancelled) {
                // 保留 .part 供断点续传；ABOR 合法响应：
                // 225（数据连接已关闭）或 226（传输已中止）
                try { connection.sendCommand("ABOR", 225, 226); }
                catch (Exception ignored) {}
                return;
            }

            connection.readResponse(226);
            // 严禁假完成：总大小已知但未写满必须走重试
            if (totalSize > 0 && downloaded < totalSize) {
                throw new IOException("FTP 下载不完整: " + downloaded + "/" + totalSize + " 字节");
            }
            // 完成：.part 原子改名为最终文件
            FileUtils.atomicMove(partFile.toPath(), finalFile.toPath());
            if (resumed) {
                callback.onStatusUpdate("FTP 断点续传完成（从 "
                        + FileUtils.formatSize(offset) + " 处继续）");
            }
            callback.onComplete();

        } finally {
            close();
        }
    }

    /** 尝试 REST 断点续传，服务器返回 350 表示接受；失败返回 false（连接仍可用） */
    private boolean tryRest(long offset) {
        try {
            String line = connection.sendCommandAndGetLine("REST " + offset);
            return line.startsWith("350");
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void cancel() {
        cancelled = true;
    }

    @Override
    public void close() {
        if (connection != null) {
            connectionPool.releaseConnection(connection);
            connection = null;
        }
        // 每个下载任务独享一个连接池，任务结束后必须整体关闭：
        // 否则池内清理线程永久持有池引用（线程持有外部 this），空闲连接永不释放
        connectionPool.close();
    }
}
