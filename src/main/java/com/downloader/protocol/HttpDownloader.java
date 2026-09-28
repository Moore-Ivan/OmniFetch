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
                .header("User-Agent",
                        "Mozilla/5.0 (MultiProtocolDownloader/2.0)")
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
        AtomicLong downloadedTotal = new AtomicLong(downloadedSize);
        long lastTime = System.currentTimeMillis();
        long lastBytes = downloadedSize;

        for (int i = 0; i < threadCount; i++) {
            final long start = i * chunkSize;
            final long end = (i == threadCount - 1) ? totalSize - 1 : (i + 1) * chunkSize - 1;
            final int threadIndex = i;

            // 跳过已下载的部分
            if (start >= totalSize) {
                continue;
            }

            Future<?> future = executor.submit(() -> {
                try {
                    if (!cancelled) {
                        downloadChunk(saveFile, start, end, downloadedTotal);
                    }
                } catch (Exception e) {
                    if (!cancelled) {
                        // 不立即标记任务失败，让其他线程继续下载
                        System.err.println("线程 " + threadIndex + " 下载失败: " + e.getMessage());
                    }
                }
            });
            downloadFutures.add(future);
        }

        // 监控进度
        long lastProgressUpdate = System.currentTimeMillis();
        while (!cancelled && downloadedTotal.get() < totalSize) {
            long now = System.currentTimeMillis();
            if (now - lastTime >= getProgressIntervalMs()) {
                long downloaded = downloadedTotal.get();
                long speed = (long) ((downloaded - lastBytes) * 1000.0 / (now - lastTime));
                callback.onProgress(downloaded, totalSize, speed);
                lastTime = now;
                lastBytes = downloaded;
                lastProgressUpdate = now;
            }
            // 检查是否超过60秒没有进度更新，超时退出
            if (now - lastProgressUpdate > 60000) {
                break;
            }
            Thread.sleep(100);
        }
        
        // 如果已取消，立即退出
        if (cancelled) {
            return;
        }
        
        // 检查下载完成情况，允许99%以上就算完成
        long downloaded = downloadedTotal.get();
        if (downloaded >= totalSize * 99 / 100) {
            // 认为下载完成
            return;
        }

        // 等待所有线程完成，但设置超时
        for (Future<?> future : downloadFutures) {
            try {
                // 如果已取消，不等待线程完成
                if (!cancelled) {
                    // 设置5秒超时，避免卡在最后
                    future.get(5, TimeUnit.SECONDS);
                } else {
                    future.cancel(true);
                }
            } catch (TimeoutException e) {
                // 超时异常，取消该线程
                future.cancel(true);
            } catch (Exception e) {
                // 忽略所有异常，让其他线程继续完成
                System.err.println("线程完成时出现异常: " + e.getMessage());
            }
        }

        executor.shutdownNow();
        try {
            // 减少等待时间
            executor.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
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
                .header("User-Agent", "Mozilla/5.0 (MultiProtocolDownloader/2.0)")
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

    private void downloadChunk(File saveFile, long start, long end, AtomicLong downloadedTotal) {
        // 检查是否已取消
        if (cancelled) {
            return;
        }

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(fileURL))
                    .header("User-Agent",
                            "Mozilla/5.0 (MultiProtocolDownloader/2.0)")
                    .header("Accept", "*/*")
                    .header("Range", "bytes=" + start + "-" + end)
                    .GET()
                    .build();

            // 检查是否已取消
            if (cancelled) {
                return;
            }

            HttpResponse<InputStream> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofInputStream());

            // 检查是否已取消
            if (cancelled) {
                return;
            }

            if (response.statusCode() != 206) {
                System.err.println("HTTP " + response.statusCode() + " (Expected 206 for range request)");
                return;
            }

            try (InputStream in = response.body();
                 FileChannel fileChannel = FileChannel.open(saveFile.toPath(), WRITE, CREATE)) {
                
                // 使用FileChannel.transferFrom实现零拷贝
                long currentPosition = start;
                long lastActivityTime = System.currentTimeMillis();
                long lastFlushTime = System.currentTimeMillis();
                
                // 创建ReadableByteChannel从输入流
                try (ReadableByteChannel inChannel = Channels.newChannel(in)) {
                    while (!cancelled) {
                        // 检查是否有数据可读
                        if (in.available() > 0) {
                            try {
                                // 零拷贝传输数据
                                long transferred = fileChannel.transferFrom(
                                        inChannel, currentPosition, getBufferSize());
                                
                                if (transferred == 0) {
                                    break; // 没有更多数据
                                }
                                
                                downloadedTotal.addAndGet(transferred);
                                currentPosition += transferred;
                                lastActivityTime = System.currentTimeMillis();
                                
                                // 按照配置的间隔刷新缓冲区
                                if (System.currentTimeMillis() - lastFlushTime > getFlushIntervalMs()) {
                                    fileChannel.force(true); // 强制刷新到磁盘
                                    lastFlushTime = System.currentTimeMillis();
                                }
                            } catch (Exception e) {
                                // 读取或写入失败，退出该线程
                                System.err.println("数据读写失败: " + e.getMessage());
                                break;
                            }
                        } else {
                            // 检查是否超过30秒没有活动，超时退出
                            if (System.currentTimeMillis() - lastActivityTime > 30000) {
                                break;
                            }
                            // 短暂休眠，避免CPU占用过高
                            try {
                                Thread.sleep(10);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                break;
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            // 捕获所有异常，避免线程崩溃
            if (!cancelled) {
                System.err.println("线程下载失败: " + e.getMessage());
            }
        }
    }

    private void downloadWithSingleThread(File saveFile) throws Exception {
        HttpRequest request;
        long totalSize;
        long start = downloadedSize;

        if (start > 0) {
            // 支持断点续传，发送Range请求
            request = HttpRequest.newBuilder()
                    .uri(URI.create(fileURL))
                    .header("User-Agent",
                            "Mozilla/5.0 (MultiProtocolDownloader/2.0)")
                    .header("Accept", "*/*")
                    .header("Range", "bytes=" + start + "-")
                    .GET()
                    .build();
        } else {
            // 正常请求
            request = HttpRequest.newBuilder()
                    .uri(URI.create(fileURL))
                    .header("User-Agent",
                            "Mozilla/5.0 (MultiProtocolDownloader/2.0)")
                    .header("Accept", "*/*")
                    .GET()
                    .build();
        }

        HttpResponse<InputStream> response = httpClient.send(
                request, HttpResponse.BodyHandlers.ofInputStream());

        if (start > 0) {
            if (response.statusCode() != 206) {
                throw new IOException("HTTP " + response.statusCode() + " (Expected 206 for range request)");
            }
        } else {
            if (response.statusCode() != 200) {
                throw new IOException("HTTP " + response.statusCode());
            }
        }

        totalSize = response.headers()
                .firstValueAsLong("Content-Length").orElse(-1);

        // 如果是续传，需要计算实际的总大小
        if (start > 0 && totalSize > 0) {
            totalSize += start;
        }

        try (InputStream in = response.body();
             FileChannel fileChannel = FileChannel.open(saveFile.toPath(), WRITE, CREATE)) {
            
            // 使用FileChannel.transferFrom实现零拷贝
            long downloaded = start;
            long currentPosition = start;
            long lastTime = System.currentTimeMillis();
            long lastBytes = start;
            long lastActivityTime = System.currentTimeMillis();
            long lastFlushTime = System.currentTimeMillis();

            // 创建ReadableByteChannel从输入流
            try (ReadableByteChannel inChannel = Channels.newChannel(in)) {
                while (!cancelled) {
                    // 检查是否有数据可读
                    if (in.available() > 0) {
                        try {
                            // 零拷贝传输数据
                            long transferred = fileChannel.transferFrom(
                                    inChannel, currentPosition, getBufferSize());
                            
                            if (transferred == 0) {
                                break; // 没有更多数据
                            }
                            
                            downloaded += transferred;
                            currentPosition += transferred;
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
                        } catch (Exception e) {
                            // 读取或写入失败，退出
                            System.err.println("数据读写失败: " + e.getMessage());
                            break;
                        }
                    } else {
                        // 检查是否超过30秒没有活动，超时退出
                        if (System.currentTimeMillis() - lastActivityTime > 30000) {
                            break;
                        }
                        // 短暂休眠，避免CPU占用过高
                        Thread.sleep(10);
                    }
                }

                if (cancelled) {
                    return;
                }
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
        httpClient = null;
        downloadFutures = null;
    }
}
