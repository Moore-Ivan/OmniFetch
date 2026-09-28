package com.downloader.protocol;

import com.downloader.model.ProgressCallback;
import com.downloader.util.FileUtils;

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

    private static final int MAX_CONCURRENT = 8;
    private static final int CONNECT_TIMEOUT_SEC = 10;

    public M3u8Downloader(String url, String savePath, ProgressCallback cb) {
        this.playlistURL = url;
        this.savePath = savePath;
        this.callback = cb;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SEC))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public void download() throws Exception {
        callback.onStatusUpdate("解析 m3u8 播放列表...");

        // 1. 下载并解析 m3u8
        String playlist = fetchURL(playlistURL);
        List<Segment> segments = parsePlaylist(playlist);

        if (segments.isEmpty()) {
            throw new IOException("播放列表中没有找到媒体分片");
        }

        String baseUrl = playlistURL.substring(0,
                playlistURL.lastIndexOf('/') + 1);

        // 如果是主播放列表（嵌套），取第一个子列表
        if (segments.get(0).isPlaylist) {
            String subUrl = resolveURL(baseUrl, segments.get(0).uri);
            callback.onStatusUpdate("检测到主播放列表，获取子列表...");
            String subPlaylist = fetchURL(subUrl);
            segments = parsePlaylist(subPlaylist);
            baseUrl = subUrl.substring(0, subUrl.lastIndexOf('/') + 1);
        }

        int total = segments.size();
        callback.onConnected("stream.ts", -1);
        callback.onStatusUpdate("共 " + total + " 个分片，开始下载...");

        // 2. 创建临时目录
        Path tempDir = Files.createTempDirectory("m3u8_dl_");

        try {
            // 3. 多线程下载分片
            executor = Executors.newFixedThreadPool(MAX_CONCURRENT);
            AtomicInteger completed = new AtomicInteger(0);
            AtomicLong totalDownloaded = new AtomicLong(0);
            long startTime = System.currentTimeMillis();
            AtomicLong lastCheck = new AtomicLong(startTime);
            AtomicLong lastBytes = new AtomicLong(0);

            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < total; i++) {
                if (cancelled) break;

                final int index = i;
                final Segment seg = segments.get(i);
                final String segUrl = resolveURL(baseUrl, seg.uri);

                futures.add(executor.submit(() -> {
                    try {
                        byte[] data = fetchBytes(segUrl);

                        // AES-128 解密
                        if (seg.encryptionKey != null) {
                            data = decryptAES128(data, seg.encryptionKey,
                                    seg.encryptionIV);
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
                            callback.onProgress(done, total, speed);
                            callback.onStatusUpdate(
                                    String.format("分片 %d/%d", done, total));
                            lastBytes.set(downloaded);
                        }
                    } catch (Exception e) {
                        if (!cancelled) {
                            callback.onError("分片 " + index + " 失败: "
                                    + e.getMessage());
                        }
                    }
                }));
            }

            // 等待所有分片完成
            for (Future<?> f : futures) {
                try { f.get(); } catch (Exception ignored) {}
            }

            if (cancelled) return;

            // 4. 按顺序合并分片
            callback.onStatusUpdate("合并分片...");
            String outName = "output_" + System.currentTimeMillis() + ".ts";
            File outputFile = FileUtils.buildSaveFile(savePath, outName);

            try (FileOutputStream fos = new FileOutputStream(outputFile)) {
                for (int i = 0; i < total; i++) {
                    Path segFile = tempDir.resolve(
                            String.format("%05d.ts", i));
                    if (Files.exists(segFile)) {
                        fos.write(Files.readAllBytes(segFile));
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
            FileUtils.deleteDir(tempDir);
        }
    }

    // --- m3u8 解析 ---

    private List<Segment> parsePlaylist(String content) {
        List<Segment> segments = new ArrayList<>();
        String[] lines = content.split("\n");
        byte[] currentKey = null;
        byte[] currentIV = null;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;

            if (line.startsWith("#EXT-X-STREAM-INF:")) {
                if (i + 1 < lines.length) {
                    Segment seg = new Segment();
                    seg.uri = lines[i + 1].trim();
                    seg.isPlaylist = true;
                    segments.add(seg);
                }
            } else if (line.startsWith("#EXT-X-KEY:")) {
                currentKey = parseKeyInfo(line);
            } else if (line.startsWith("#EXTINF:")) {
                if (i + 1 < lines.length) {
                    Segment seg = new Segment();
                    seg.uri = lines[i + 1].trim();
                    seg.encryptionKey = currentKey;
                    seg.encryptionIV = currentIV;
                    segments.add(seg);
                }
            }
        }
        return segments;
    }

    private byte[] parseKeyInfo(String keyLine) {
        String uri = null;
        int uriIdx = keyLine.indexOf("URI=\"");
        if (uriIdx >= 0) {
            int end = keyLine.indexOf("\"", uriIdx + 5);
            if (end > 0) uri = keyLine.substring(uriIdx + 5, end);
        }

        if (uri == null) return null;

        try {
            String baseUrl = playlistURL.substring(0,
                    playlistURL.lastIndexOf('/') + 1);
            return fetchBytes(resolveURL(baseUrl, uri));
        } catch (Exception e) {
            return null;
        }
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

    static class Segment {
        String uri;
        boolean isPlaylist = false;
        byte[] encryptionKey;
        byte[] encryptionIV;
    }
}
