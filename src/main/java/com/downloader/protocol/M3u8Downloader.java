package com.downloader.protocol;

import com.downloader.config.ConfigManager;
import com.downloader.model.ProgressCallback;
import com.downloader.util.FileUtils;
import com.downloader.util.HttpTrust;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * m3u8/HLS 下载器
 * 多线程分片下载 + 顺序合并 + AES-128 解密
 */
public class M3u8Downloader implements DownloadProtocol {

    private final String playlistURL;
    private final String savePath;
    private final ProgressCallback callback;
    private volatile boolean cancelled = false;

    private final HttpClient httpClient;
    private ExecutorService executor;

    private static final int CONNECT_TIMEOUT_SEC = 10;
    private static final int MERGE_BUFFER_SIZE = 64 * 1024;

    private final ConfigManager configManager = ConfigManager.getInstance();

    public M3u8Downloader(String url, String savePath, ProgressCallback cb) {
        this.playlistURL = url;
        this.savePath = savePath;
        this.callback = cb;
        HttpClient.Builder clientBuilder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SEC))
                .followRedirects(HttpClient.Redirect.NORMAL);
        // 与 HTTP 下载器/更新链路一致：信任所有证书，避免本地加速代理的自签证书
        // 或精简 JRE 信任库不全导致 PKIX/certificate_unknown 失败
        if (HttpTrust.SSL_CONTEXT != null) {
            clientBuilder.sslContext(HttpTrust.SSL_CONTEXT);
        }
        this.httpClient = clientBuilder.build();
    }

    @Override
    public void download() throws Exception {
        callback.onStatusUpdate("解析 m3u8 播放列表...");

        // 1. 下载并解析 m3u8
        Playlist parsed = parsePlaylist(fetchURL(playlistURL),
                baseUrlOf(playlistURL));
        List<Segment> segments = parsed.segments;

        if (segments.isEmpty()) {
            throw new IOException("播放列表中没有找到媒体分片");
        }

        String baseUrl = parsed.baseUrl;

        // 如果是主播放列表（嵌套），取第一个子列表
        if (segments.get(0).isPlaylist) {
            String subUrl = resolveURL(baseUrl, segments.get(0).uri);
            callback.onStatusUpdate("检测到主播放列表，获取子列表...");
            String subPlaylist = fetchURL(subUrl);
            parsed = parsePlaylist(subPlaylist, baseUrlOf(subUrl));
            segments = parsed.segments;
            baseUrl = parsed.baseUrl;
        }

        // 主播放列表分支会重新赋值 parsed，lambda 只能引用 final 副本
        final Playlist playlist = parsed;
        final String finalBaseUrl = baseUrl;

        int total = segments.size();
        callback.onConnected("stream.ts", -1);
        callback.onStatusUpdate("共 " + total + " 个分片，开始下载...");

        // 2. 创建临时目录
        Path tempDir = Files.createTempDirectory("m3u8_dl_");

        try {
            // 3. 多线程下载分片（并发数可配置）
            int maxConcurrent = configManager.getInt("download.m3u8Concurrent", 8);
            executor = Executors.newFixedThreadPool(maxConcurrent);
            AtomicInteger completed = new AtomicInteger(0);
            AtomicLong totalDownloaded = new AtomicLong(0);
            AtomicBoolean failed = new AtomicBoolean(false);
            AtomicReference<String> firstError = new AtomicReference<>(null);
            long startTime = System.currentTimeMillis();
            AtomicLong lastCheck = new AtomicLong(startTime);
            AtomicLong lastBytes = new AtomicLong(0);

            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < total; i++) {
                if (cancelled || failed.get()) break;

                final int index = i;
                final Segment seg = segments.get(i);
                final String segUrl = resolveURL(finalBaseUrl, seg.uri);

                futures.add(executor.submit(() -> {
                    try {
                        // 已有分片失败时尽快退出，避免无谓流量
                        if (failed.get() || cancelled) {
                            return;
                        }
                        byte[] data = fetchBytes(segUrl);

                        // AES-128 解密；未显式指定 IV 时按 HLS 规范使用媒体序列号
                        if (seg.encryptionKey != null) {
                            byte[] iv = seg.explicitIV != null
                                    ? seg.explicitIV
                                    : sequenceIv(playlist.mediaSequence + index);
                            data = decryptAES128(data, seg.encryptionKey, iv);
                        }

                        Path segFile = tempDir.resolve(
                                String.format("%05d.ts", index));
                        Files.write(segFile, data);

                        int done = completed.incrementAndGet();
                        long downloaded = totalDownloaded.addAndGet(data.length);

                        long now = System.currentTimeMillis();
                        long lc = lastCheck.get();
                        if (now - lc >= 300 && lastCheck.compareAndSet(lc, now)) {
                            long speed = (long) ((downloaded - lastBytes.get())
                                    * 1000.0 / (now - lc));
                            // 进度按实际字节数上报（总大小未知，速度保持真实）
                            callback.onProgress(downloaded, -1, speed);
                            callback.onStatusUpdate(
                                    String.format("分片 %d/%d", done, total));
                            lastBytes.set(downloaded);
                        }
                    } catch (Exception e) {
                        // 快速失败：记录首个错误并终止后续分片，避免"半成品文件仍被标记完成"
                        if (failed.compareAndSet(false, true)) {
                            firstError.set("分片 " + index + " 失败: " + e.getMessage());
                        }
                        if (executor != null) {
                            executor.shutdownNow();
                        }
                    }
                }));
            }

            // 等待所有分片结束
            for (Future<?> f : futures) {
                try { f.get(); } catch (Exception ignored) {}
            }

            if (cancelled) return;
            if (failed.get()) {
                throw new IOException(firstError.get());
            }

            // 4. 按顺序合并分片（流式复制，避免一次性读入大分片导致 OOM）
            callback.onStatusUpdate("合并分片...");
            String outName = "output_" + System.currentTimeMillis() + ".ts";
            File outputFile = FileUtils.buildSaveFile(savePath, outName);

            try (FileOutputStream fos = new FileOutputStream(outputFile)) {
                byte[] copyBuf = new byte[MERGE_BUFFER_SIZE];
                for (int i = 0; i < total; i++) {
                    Path segFile = tempDir.resolve(
                            String.format("%05d.ts", i));
                    if (Files.exists(segFile)) {
                        try (InputStream in = Files.newInputStream(segFile)) {
                            int n;
                            while ((n = in.read(copyBuf)) >= 0) {
                                fos.write(copyBuf, 0, n);
                            }
                        }
                    }
                }
            }

            long elapsed = System.currentTimeMillis() - startTime;
            callback.onStatusUpdate(String.format(
                    "完成! 共 %d 分片, 耗时 %.1f 秒",
                    total, elapsed / 1000.0));
            callback.onComplete();

        } finally {
            // 5. 清理临时文件
            if (executor != null) {
                executor.shutdownNow();
            }
            FileUtils.deleteDir(tempDir);
        }
    }

    private static String baseUrlOf(String url) {
        return url.substring(0, url.lastIndexOf('/') + 1);
    }

    // --- m3u8 解析 ---

    private Playlist parsePlaylist(String content, String baseUrl) {
        Playlist playlist = new Playlist(baseUrl);
        String[] lines = content.split("\n");
        KeyInfo currentKey = null;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;

            if (line.startsWith("#EXT-X-STREAM-INF:")) {
                if (i + 1 < lines.length) {
                    Segment seg = new Segment();
                    seg.uri = lines[i + 1].trim();
                    seg.isPlaylist = true;
                    playlist.segments.add(seg);
                }
            } else if (line.startsWith("#EXT-X-MEDIA-SEQUENCE:")) {
                try {
                    playlist.mediaSequence = Long.parseLong(
                            line.substring(line.indexOf(':') + 1).trim());
                } catch (NumberFormatException ignored) {}
            } else if (line.startsWith("#EXT-X-KEY:")) {
                // METHOD=NONE 表示取消加密；否则解析 URI/IV 并拉取密钥
                currentKey = parseKeyInfo(line, baseUrl);
            } else if (line.startsWith("#EXTINF:")) {
                if (i + 1 < lines.length) {
                    Segment seg = new Segment();
                    seg.uri = lines[i + 1].trim();
                    if (currentKey != null) {
                        seg.encryptionKey = currentKey.key;
                        seg.explicitIV = currentKey.iv;
                    }
                    playlist.segments.add(seg);
                }
            }
        }
        return playlist;
    }

    /** 解析 EXT-X-KEY；METHOD=NONE 返回 null，拉取密钥失败也返回 null（按未加密处理） */
    private KeyInfo parseKeyInfo(String keyLine, String baseUrl) {
        String method = attrValue(keyLine, "METHOD");
        if (method == null || "NONE".equalsIgnoreCase(method)) {
            return null;
        }
        String uri = attrValue(keyLine, "URI");
        if (uri == null) {
            return null;
        }

        KeyInfo info = new KeyInfo();
        String ivText = attrValue(keyLine, "IV");
        if (ivText != null) {
            info.iv = parseHexIv(ivText);
        }
        try {
            info.key = fetchBytes(resolveURL(baseUrl, uri));
        } catch (Exception e) {
            return null;
        }
        if (info.key == null || info.key.length != 16) {
            // AES-128 密钥必须是 16 字节，无效则不标记加密
            return null;
        }
        return info;
    }

    /** 从属性行中提取未加引号（或加引号）的属性值，如 METHOD、URI、IV */
    private static String attrValue(String line, String name) {
        String token = name + "=";
        int idx = line.indexOf(token);
        if (idx < 0) {
            return null;
        }
        int start = idx + token.length();
        if (start >= line.length()) {
            return null;
        }
        if (line.charAt(start) == '"') {
            int end = line.indexOf('"', start + 1);
            return end > 0 ? line.substring(start + 1, end) : null;
        }
        int end = start;
        while (end < line.length() && line.charAt(end) != ','
                && line.charAt(end) != ' ' && line.charAt(end) != '\t') {
            end++;
        }
        return line.substring(start, end);
    }

    /** 解析 0x 前缀的十六进制 IV 为 16 字节；不足左侧补零，超长取末 16 字节 */
    private static byte[] parseHexIv(String text) {
        String hex = text.trim();
        if (hex.startsWith("0x") || hex.startsWith("0X")) {
            hex = hex.substring(2);
        }
        if (hex.isEmpty()) {
            return null;
        }
        if (hex.length() % 2 != 0) {
            hex = "0" + hex;
        }
        byte[] raw;
        try {
            raw = new byte[hex.length() / 2];
            for (int i = 0; i < raw.length; i++) {
                raw[i] = (byte) Integer.parseInt(
                        hex.substring(i * 2, i * 2 + 2), 16);
            }
        } catch (NumberFormatException e) {
            return null;
        }
        if (raw.length == 16) {
            return raw;
        }
        byte[] iv = new byte[16];
        int copy = Math.min(raw.length, 16);
        System.arraycopy(raw, raw.length - copy, iv, 16 - copy, copy);
        return iv;
    }

    /** HLS 默认 IV：128 位大端序的媒体序列号 */
    private static byte[] sequenceIv(long sequence) {
        byte[] iv = new byte[16];
        for (int i = 15; i >= 8 && sequence != 0; i--) {
            iv[i] = (byte) (sequence & 0xFF);
            sequence >>>= 8;
        }
        return iv;
    }

    /** AES-128-CBC 解密 */
    private byte[] decryptAES128(byte[] data, byte[] key, byte[] iv)
            throws Exception {
        if (iv == null) {
            iv = new byte[16];
        }
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE,
                new SecretKeySpec(key, "AES"),
                new IvParameterSpec(iv));
        return cipher.doFinal(data);
    }

    // --- HTTP 辅助 ---

    private String fetchURL(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "Mozilla/5.0")
                .GET().build();
        return httpClient.send(req,
                HttpResponse.BodyHandlers.ofString()).body();
    }

    private byte[] fetchBytes(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "Mozilla/5.0")
                .GET().build();
        return httpClient.send(req,
                HttpResponse.BodyHandlers.ofByteArray()).body();
    }

    private String resolveURL(String base, String relative) {
        if (relative.startsWith("http://") || relative.startsWith("https://")) {
            return relative;
        }
        return base + relative;
    }

    @Override
    public void cancel() {
        cancelled = true;
        if (executor != null) executor.shutdownNow();
    }

    @Override
    public void close() {
        if (executor != null && !executor.isShutdown()) {
            executor.shutdown();
        }
    }

    // --- 内部类 ---

    private static class Playlist {
        final String baseUrl;
        final List<Segment> segments = new ArrayList<>();
        long mediaSequence = 0;

        Playlist(String baseUrl) {
            this.baseUrl = baseUrl;
        }
    }

    private static class KeyInfo {
        byte[] key;
        byte[] iv;
    }

    static class Segment {
        String uri;
        boolean isPlaylist = false;
        byte[] encryptionKey;
        /** 播放列表中显式声明的 IV；为 null 时使用媒体序列号 */
        byte[] explicitIV;
    }
}
