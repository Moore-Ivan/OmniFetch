package com.downloader.protocol;

import com.downloader.config.ConfigManager;
import com.downloader.model.ProgressCallback;
import com.downloader.util.FileUtils;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.channels.*;
import static java.nio.file.StandardOpenOption.*;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * HTTP/HTTPS 下载器
 * 使用 Java 17+ HttpClient，自动协商 HTTP/2，支持多线程分段下载
 */
public class HttpDownloader implements DownloadProtocol {

    private final String fileURL;
    private final String savePath;
    private final ProgressCallback callback;
    private volatile boolean cancelled = false;
    private HttpClient httpClient;
    private List<Future<?>> downloadFutures;
    private long downloadedSize = 0;
    private String saveFilePath;

    private final ConfigManager configManager = ConfigManager.getInstance();
    
    private int getBufferSize() {
        return configManager.getInt("download.bufferSize", 262144);
    }
    
    private int getConnectTimeoutSec() {
        return configManager.getInt("download.connectTimeoutSec", 15);
    }
    
    private int getProgressIntervalMs() {
        return configManager.getInt("download.progressIntervalMs", 200);
    }
    
    private int getFlushIntervalMs() {
        return configManager.getInt("download.flushIntervalMs", 10000);
    }
    
    private int getMaxThreads() {
        return configManager.getInt("http.maxThreads", 16);
    }
    
    private long getMinChunkSize() {
        return configManager.getLong("http.minChunkSize", 5242880); // 5MB
    }
    
    private long getMaxChunkSize() {
        return configManager.getLong("http.maxChunkSize", 52428800); // 50MB
    }

    private String getUserAgent() {
        return configManager.getString("http.userAgent",
                "Mozilla/5.0 (MultiProtocolDownloader/2.0)");
    }

    public HttpDownloader(String fileURL, String savePath,
                          ProgressCallback callback) {
        this.fileURL = fileURL;
        this.savePath = savePath;
        this.callback = callback;
    }

    public HttpDownloader(String fileURL, String savePath,
                          ProgressCallback callback, long downloadedSize) {
        this.fileURL = fileURL;
        this.savePath = savePath;
        this.callback = callback;
        this.downloadedSize = downloadedSize;
    }

    public HttpDownloader(String fileURL, String savePath,
                          ProgressCallback callback, long downloadedSize, String saveFilePath) {
        this.fileURL = fileURL;
        this.savePath = savePath;
        this.callback = callback;
        this.downloadedSize = downloadedSize;
        this.saveFilePath = saveFilePath;
    }

    @Override
    public void download() throws Exception {
        httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .connectTimeout(Duration.ofSeconds(getConnectTimeoutSec()))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        // 先检查是否已取消
        if (cancelled) {
            return;
        }

        // 先发送 HEAD 请求获取文件信息
        HttpRequest headRequest = HttpRequest.newBuilder()
                .uri(URI.create(fileURL))
                .header("User-Agent", getUserAgent())
                .header("Accept", "*/*")
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .build();

        HttpResponse<Void> headResponse = httpClient.send(
                headRequest, HttpResponse.BodyHandlers.discarding());

        if (cancelled) {
            return;
        }

        if (headResponse.statusCode() != 200) {
            throw new IOException("HTTP " + headResponse.statusCode());
        }

        String fileName = FileUtils.extractFileName(
                headResponse.headers().firstValue("Content-Disposition").orElse(null),
                fileURL);
        long totalSize = headResponse.headers()
                .firstValueAsLong("Content-Length").orElse(-1);

        callback.onConnected(fileName, totalSize);
        callback.onStatusUpdate("HTTP/"
                + (headResponse.version() == HttpClient.Version.HTTP_2 ? "2" : "1.1"));

        File saveFile;
        if (saveFilePath != null && !saveFilePath.isEmpty()) {
            saveFile = new File(saveFilePath);
        } else {
            saveFile = FileUtils.buildSaveFile(savePath, fileName);
        }

        // 检查是否支持 Range 请求
        boolean supportRange = headResponse.headers().firstValue("Accept-Ranges").isPresent();

        if (supportRange && totalSize > 0) {
            // 使用多线程分段下载
            downloadWithMultipleThreads(saveFile, totalSize);
        } else {
            // 单线程下载
            downloadWithSingleThread(saveFile);
        }

        if (!cancelled) {
            callback.onComplete();
        }
    }

    private void downloadWithMultipleThreads(File saveFile, long totalSize) throws Exception {
        // 创建文件并预分配空间
        try (RandomAccessFile raf = new RandomAccessFile(saveFile, "rw")) {
            raf.setLength(totalSize);
        }

        // 先进行速度测试，确定合适的分片策略
        long testSpeed = testConnectionSpeed();
        int threadCount = determineThreadCount(testSpeed, totalSize);
        long chunkSize = determineChunkSize(testSpeed, totalSize, threadCount);

        System.out.println("连接速度测试: " + FileUtils.formatSize(testSpeed) + "/s");
        System.out.println("使用线程数: " + threadCount);
        System.out.println("分片大小: " + FileUtils.formatSize(chunkSize));

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        downloadFutures = new ArrayList<>();
        AtomicLong downloadedTotal = new AtomicLong(0);
        AtomicInteger failedChunks = new AtomicInteger(0);

        try {
            for (int i = 0; i < threadCount; i++) {
                final long start = i * chunkSize;
                final long end = (i == threadCount - 1) ? totalSize - 1 : (i + 1) * chunkSize - 1;
                final int threadIndex = i;

                // 跳过超出文件末尾的分片
                if (start >= totalSize) {
                    continue;
                }

                Future<?> future = executor.submit(() -> {
                    try {
                        if (!cancelled) {
                            downloadChunk(saveFile, start, end, downloadedTotal);
                        }
                    } catch (Exception e) {
                        failedChunks.incrementAndGet();
                        if (!cancelled) {
                            System.err.println("分片 " + threadIndex + " 下载失败: " + e.getMessage());
                        }
                    }
                });
                downloadFutures.add(future);
            }

            // 监控进度：只在有字节数增长时刷新活动时间，避免"零进度也刷新"导致停滞检测失效
            long lastTime = System.currentTimeMillis();
            long lastBytes = 0;
            long lastProgressBytes = 0;
            long lastProgressTime = System.currentTimeMillis();
            while (!cancelled) {
                boolean allDone = true;
                for (Future<?> future : downloadFutures) {
                    if (!future.isDone()) {
                        allDone = false;
                        break;
                    }
                }
                if (allDone) {
                    break;
                }
                long now = System.currentTimeMillis();
                long downloaded = downloadedTotal.get();
                if (now - lastTime >= getProgressIntervalMs()) {
                    long speed = (long) ((downloaded - lastBytes) * 1000.0 / (now - lastTime));
                    callback.onProgress(downloaded, totalSize, speed);
                    lastTime = now;
                    lastBytes = downloaded;
                }
                if (downloaded != lastProgressBytes) {
                    lastProgressBytes = downloaded;
                    lastProgressTime = now;
                } else if (now - lastProgressTime > 60_000) {
                    throw new IOException("下载停滞超过 60 秒，已中止本次尝试");
                }
                Thread.sleep(100);
            }

            // 有界等待分片线程收尾，避免无限阻塞
            for (Future<?> future : downloadFutures) {
                try {
                    future.get(10, TimeUnit.SECONDS);
                } catch (TimeoutException e) {
                    future.cancel(true);
                } catch (Exception ignored) {
                }
            }
        } finally {
            // 无论完成、取消还是异常都必须关闭线程池，避免线程泄漏
            executor.shutdownNow();
        }

        if (cancelled) {
            return;
        }
        // 分片失败或数据不完整都视为失败（走统一重试），绝不允许"假完成"产生损坏文件
        if (failedChunks.get() > 0) {
            throw new IOException(failedChunks.get() + " 个分片下载失败");
        }
        long downloaded = downloadedTotal.get();
        if (downloaded < totalSize) {
            throw new IOException("分片下载不完整: " + downloaded + "/" + totalSize + " 字节");
        }
    }
    
    /**
     * 测试连接速度
     */
    private long testConnectionSpeed() throws Exception {
        long startTime = System.currentTimeMillis();
        long bytesRead = 0;
        int testDuration = 2000; // 测试2秒，减少测试时间
        
        HttpRequest testRequest = HttpRequest.newBuilder()
                .uri(URI.create(fileURL))
                .header("User-Agent", getUserAgent())
                .header("Accept", "*")
                .header("Range", "bytes=0-2097151") // 测试2MB，增加测试数据量
                .GET()
                .build();
        
        HttpResponse<InputStream> response = httpClient.send(testRequest, HttpResponse.BodyHandlers.ofInputStream());
        
        if (response.statusCode() == 206) {
            try (InputStream in = response.body()) {
                byte[] buffer = new byte[16384]; // 增大测试缓冲区
                int read;
                long endTime = startTime + testDuration;
                
                while (System.currentTimeMillis() < endTime && (read = in.read(buffer)) != -1) {
                    bytesRead += read;
                }
            }
        }
        
        long elapsedTime = System.currentTimeMillis() - startTime;
        if (elapsedTime == 0) elapsedTime = 1;
        
        return (bytesRead * 1000) / elapsedTime;
    }
    
    /**
     * 根据连接速度确定线程数
     */
    private int determineThreadCount(long speed, long totalSize) {
        int maxThreads = getMaxThreads();
        
        // 慢速连接（< 1MB/s）：使用更多线程
        if (speed < 1048576) {
            return Math.min(4, maxThreads);
        }
        // 中速连接（1-10MB/s）：使用中等线程数
        else if (speed < 10485760) {
            return Math.min(6, maxThreads);
        }
        // 高速连接（> 10MB/s）：使用较多线程
        else {
            return Math.min(10, maxThreads);
        }
    }
    
    /**
     * 根据连接速度和线程数确定分片大小
     */
    private long determineChunkSize(long speed, long totalSize, int threadCount) {
        long minChunkSize = getMinChunkSize();
        long maxChunkSize = getMaxChunkSize();
        
        // 根据带宽延迟积（BDP）估算合适的块大小
        // 假设网络延迟为100ms
        long bdp = (speed * 100) / 1000;
        long chunkSize = Math.max(bdp * 2, minChunkSize);
        
        // 确保分片大小不超过最大值，并且分片数合理
        chunkSize = Math.min(chunkSize, maxChunkSize);
        
        // 计算最终的分片大小，确保能均匀分配
        long finalChunkSize = totalSize / threadCount;
        if (finalChunkSize < minChunkSize) {
            finalChunkSize = minChunkSize;
        }
        
        return finalChunkSize;
    }

    /**
     * 下载单个分片。任何失败都抛出异常（由调用方计数并触发统一重试），
     * 保证不完整的数据永远不会被标记为完成。
     */
    private void downloadChunk(File saveFile, long start, long end, AtomicLong downloadedTotal) throws Exception {
        long expected = end - start + 1;

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(fileURL))
                .header("User-Agent", getUserAgent())
                .header("Accept", "*/*")
                .header("Range", "bytes=" + start + "-" + end)
                .GET()
                .build();

        if (cancelled) {
            return;
        }

        HttpResponse<InputStream> response = httpClient.send(
                request, HttpResponse.BodyHandlers.ofInputStream());

        if (cancelled) {
            response.body().close();
            return;
        }

        if (response.statusCode() != 206) {
            response.body().close();
            throw new IOException("HTTP " + response.statusCode() + "（分片请求期望 206）");
        }

        try (InputStream in = response.body();
             FileChannel fileChannel = FileChannel.open(saveFile.toPath(), WRITE, CREATE);
             ReadableByteChannel inChannel = Channels.newChannel(in)) {

            long currentPosition = start;
            long transferredTotal = 0;
            long lastActivityTime = System.currentTimeMillis();
            long lastFlushTime = System.currentTimeMillis();

            while (transferredTotal < expected) {
                if (cancelled) {
                    return;
                }
                if (in.available() > 0) {
                    // 零拷贝传输数据
                    long transferred = fileChannel.transferFrom(
                            inChannel, currentPosition, getBufferSize());

                    if (transferred == 0) {
                        throw new IOException("数据流意外提前结束");
                    }

                    downloadedTotal.addAndGet(transferred);
                    currentPosition += transferred;
                    transferredTotal += transferred;
                    lastActivityTime = System.currentTimeMillis();

                    // 按照配置的间隔刷新缓冲区
                    if (System.currentTimeMillis() - lastFlushTime > getFlushIntervalMs()) {
                        fileChannel.force(true); // 强制刷新到磁盘
                        lastFlushTime = System.currentTimeMillis();
                    }
                } else {
                    // 超过 30 秒没有任何数据视为连接停滞，抛错触发重试
                    if (System.currentTimeMillis() - lastActivityTime > 30_000) {
                        throw new IOException("连接 30 秒无数据");
                    }
                    Thread.sleep(10);
                }
            }
        }
    }

    private void downloadWithSingleThread(File saveFile) throws Exception {
        long start = downloadedSize;

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(fileURL))
                .header("User-Agent", getUserAgent())
                .header("Accept", "*/*");
        if (start > 0) {
            // 支持断点续传，发送Range请求
            builder.header("Range", "bytes=" + start + "-");
        }
        HttpRequest request = builder.GET().build();

        if (cancelled) {
            return;
        }

        HttpResponse<InputStream> response = httpClient.send(
                request, HttpResponse.BodyHandlers.ofInputStream());

        if (cancelled) {
            response.body().close();
            return;
        }

        if (start > 0 && response.statusCode() != 206) {
            response.body().close();
            throw new IOException("HTTP " + response.statusCode() + "（续传请求期望 206）");
        }
        if (start == 0 && response.statusCode() != 200) {
            response.body().close();
            throw new IOException("HTTP " + response.statusCode());
        }

        long totalSize = response.headers()
                .firstValueAsLong("Content-Length").orElse(-1);

        // 如果是续传，需要计算实际的总大小
        if (start > 0 && totalSize > 0) {
            totalSize += start;
        }

        try (InputStream in = response.body();
             FileChannel fileChannel = FileChannel.open(saveFile.toPath(), WRITE, CREATE);
             ReadableByteChannel inChannel = Channels.newChannel(in)) {

            long downloaded = start;
            long lastTime = System.currentTimeMillis();
            long lastBytes = start;
            long lastActivityTime = System.currentTimeMillis();
            long lastFlushTime = System.currentTimeMillis();

            while (!cancelled) {
                // 总大小已知且已下满则结束
                if (totalSize > 0 && downloaded >= totalSize) {
                    break;
                }
                if (in.available() > 0) {
                    // 零拷贝传输数据
                    long transferred = fileChannel.transferFrom(
                            inChannel, downloaded, getBufferSize());

                    if (transferred == 0) {
                        break; // 服务器关闭连接，数据流结束
                    }

                    downloaded += transferred;
                    lastActivityTime = System.currentTimeMillis();

                    // 按照配置的间隔刷新缓冲区
                    if (System.currentTimeMillis() - lastFlushTime > getFlushIntervalMs()) {
                        fileChannel.force(true); // 强制刷新到磁盘
                        lastFlushTime = System.currentTimeMillis();
                    }

                    long now = System.currentTimeMillis();
                    if (now - lastTime >= getProgressIntervalMs()) {
                        long speed = (long) ((downloaded - lastBytes)
                                * 1000.0 / (now - lastTime));
                        callback.onProgress(downloaded, totalSize, speed);
                        lastTime = now;
                        lastBytes = downloaded;
                    }
                } else {
                    // 超过 30 秒没有任何数据视为连接停滞，抛错触发重试
                    if (System.currentTimeMillis() - lastActivityTime > 30_000) {
                        throw new IOException("连接 30 秒无数据");
                    }
                    Thread.sleep(10);
                }
            }

            if (cancelled) {
                return;
            }
            // 总大小已知但没下满：视为失败并重试，绝不允许静默截断的"完成"
            if (totalSize > 0 && downloaded < totalSize) {
                throw new IOException("下载不完整: " + downloaded + "/" + totalSize + " 字节");
            }
        }
    }

    @Override
    public void cancel() {
        cancelled = true;
        if (downloadFutures != null) {
            for (Future<?> future : downloadFutures) {
                future.cancel(true);
            }
        }
    }

    @Override
    public void close() {
        // 不置空 httpClient：正在运行的分片线程仍在使用该实例，
        // 置空会在读取时触发 NPE 导致线程意外终止
        downloadFutures = null;
    }
}
