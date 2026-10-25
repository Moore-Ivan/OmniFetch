package com.downloader.protocol;

import com.downloader.config.ConfigManager;
import com.downloader.model.ProgressCallback;
import com.downloader.util.AppLogger;
import com.downloader.util.FileUtils;
import com.downloader.util.HttpTrust;
import com.downloader.util.RateLimiter;
import com.downloader.util.UpdateDownloader;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.channels.*;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import static java.nio.file.StandardOpenOption.*;
import java.time.Duration;
import javax.net.ssl.SSLHandshakeException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
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
    /** 任务级限速器（字节/秒，0=不限）；可为 null（插件/旧构造通道） */
    private final RateLimiter limiter;

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
        this(fileURL, savePath, callback, 0, null, null);
    }

    public HttpDownloader(String fileURL, String savePath,
                          ProgressCallback callback, long downloadedSize) {
        this(fileURL, savePath, callback, downloadedSize, null, null);
    }

    public HttpDownloader(String fileURL, String savePath,
                          ProgressCallback callback, long downloadedSize, String saveFilePath) {
        this(fileURL, savePath, callback, downloadedSize, saveFilePath, null);
    }

    public HttpDownloader(String fileURL, String savePath,
                          ProgressCallback callback, long downloadedSize, String saveFilePath,
                          RateLimiter limiter) {
        this.fileURL = fileURL;
        this.savePath = savePath;
        this.callback = callback;
        this.downloadedSize = downloadedSize;
        this.saveFilePath = saveFilePath;
        this.limiter = limiter;
    }

    @Override
    public void download() throws Exception {
        HttpClient.Builder clientBuilder = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .connectTimeout(Duration.ofSeconds(getConnectTimeoutSec()))
                .followRedirects(HttpClient.Redirect.NORMAL);
        // 与更新检查/更新包下载共用 HttpTrust：信任所有证书。GitHub 等站点在本机
        // 常被加速工具（hosts 指向 127.0.0.1）用自签证书接管，或运行环境 cacerts 不全，
        // 不配置会以 PKIX/certificate_unknown 直接失败（更新链路早已这样处理，下载器此前漏配）
        if (HttpTrust.SSL_CONTEXT != null) {
            clientBuilder.sslContext(HttpTrust.SSL_CONTEXT);
        }
        httpClient = clientBuilder.build();

        // 先检查是否已取消
        if (cancelled) {
            return;
        }

        // 先发送 HEAD 请求获取文件信息
        HttpRequest headRequest = HttpRequest.newBuilder()
                .uri(URI.create(fileURL))
                .header("User-Agent", getUserAgent())
                .header("Accept", "*/*")
                // 显式要求不压缩传输：压缩响应会让落盘内容与原始文件不一致
                .header("Accept-Encoding", "identity")
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .build();

        HttpResponse<Void> headResponse;
        try {
            headResponse = httpClient.send(
                    headRequest, HttpResponse.BodyHandlers.discarding());
        } catch (IOException e) {
            // 首次连接阶段失败统一翻译为带主机与排查建议的错误，避免只看到笼统异常
            throw describeConnectionError(e);
        }

        if (cancelled) {
            return;
        }

        String fileName;
        long totalSize;
        boolean supportRange;
        HttpClient.Version version;

        if (headResponse.statusCode() >= 200 && headResponse.statusCode() < 300) {
            fileName = FileUtils.extractFileName(
                    headResponse.headers().firstValue("Content-Disposition").orElse(null),
                    fileURL);
            totalSize = headResponse.headers()
                    .firstValueAsLong("Content-Length").orElse(-1);
            // 仅 Accept-Ranges: bytes 视为支持续传（缺失或 none 都按不支持处理）
            supportRange = headResponse.headers().firstValue("Accept-Ranges")
                    .map(v -> v.trim().equalsIgnoreCase("bytes"))
                    .orElse(false);
            version = headResponse.version();
        } else {
            // HEAD 被拒（部分服务器对 HEAD 返回 403/405，但 GET 完全可用）：
            // 退化用 GET bytes=0-0 探测文件名/大小/Range 支持
            HttpResponse<InputStream> probe = httpClient.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create(fileURL))
                            .header("User-Agent", getUserAgent())
                            .header("Accept", "*/*")
                            .header("Accept-Encoding", "identity")
                            .header("Range", "bytes=0-0")
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream probeBody = probe.body()) {
                if (cancelled) {
                    return;
                }
                int code = probe.statusCode();
                if (code == 206) {
                    supportRange = true;
                    totalSize = UpdateDownloader.parseTotalFromContentRange(
                            probe.headers().firstValue("Content-Range").orElse(null));
                    if (totalSize <= 0) {
                        totalSize = probe.headers().firstValueAsLong("Content-Length").orElse(-1);
                    }
                } else if (code >= 200 && code < 300) {
                    supportRange = false;
                    totalSize = probe.headers().firstValueAsLong("Content-Length").orElse(-1);
                } else {
                    throw new IOException("HTTP " + headResponse.statusCode()
                            + "（HEAD）/ " + code + "（GET 探测）");
                }
                fileName = FileUtils.extractFileName(
                        probe.headers().firstValue("Content-Disposition").orElse(null),
                        fileURL);
                version = probe.version();
            }
        }

        callback.onConnected(fileName, totalSize);
        callback.onStatusUpdate("HTTP/"
                + (version == HttpClient.Version.HTTP_2 ? "2" : "1.1"));

        File saveFile;
        if (saveFilePath != null && !saveFilePath.isEmpty()) {
            saveFile = new File(saveFilePath);
        } else {
            saveFile = FileUtils.buildSaveFile(savePath, fileName);
        }
        // 重名避让后的真实落盘路径回报给任务（续传/打开文件都以它为准）
        callback.onSaveFile(saveFile);

        // 检查是否支持 Range 请求
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
        // 分片续传元数据侧车：记录分片参数与每个分片已完成字节数，
        // 参数与当前任务不匹配（如换了速度策略/文件总大小变化）时整体重下
        File metaFile = new File(saveFile.getAbsolutePath() + ".partmeta");
        ResumeMeta resume = loadResumeMeta(metaFile, saveFile, totalSize);

        int threadCount;
        long chunkSize;
        long[] chunkDone;

        // 创建文件并预分配空间：首次下载（无有效元数据）必须截断清零，
        // 否则同名旧文件内容会与新分片数据混杂造成损坏
        try (RandomAccessFile raf = new RandomAccessFile(saveFile, "rw")) {
            if (resume == null) {
                raf.setLength(0);
                raf.setLength(totalSize);
            } else if (raf.length() != totalSize) {
                raf.setLength(totalSize);
            }
        }

        if (resume == null) {
            // 先进行速度测试，确定合适的分片策略（仅全新下载时测速，续传沿用旧参数）
            long testSpeed = testConnectionSpeed();
            threadCount = determineThreadCount(testSpeed, totalSize);
            chunkSize = determineChunkSize(testSpeed, totalSize, threadCount);
            chunkDone = new long[threadCount];

            AppLogger.info("连接速度测试: " + FileUtils.formatSize(testSpeed) + "/s"
                    + "，使用线程数: " + threadCount
                    + "，分片大小: " + FileUtils.formatSize(chunkSize));
        } else {
            threadCount = resume.threadCount;
            chunkSize = resume.chunkSize;
            chunkDone = resume.done;

            long resumed = 0;
            for (long d : chunkDone) resumed += d;
            AppLogger.info("断点续传: 线程数=" + threadCount
                    + "，分片大小=" + FileUtils.formatSize(chunkSize)
                    + "，已下载=" + FileUtils.formatSize(resumed));
        }

        // 分片参数确定后先落一次盘，异常退出也能保留续传上下文
        saveResumeMeta(metaFile, totalSize, chunkSize, threadCount, chunkDone);

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        downloadFutures = new ArrayList<>();
        long baseline = 0;
        for (long d : chunkDone) baseline += d;
        AtomicLong downloadedTotal = new AtomicLong(baseline);
        AtomicInteger failedChunks = new AtomicInteger(0);

        try {
            for (int i = 0; i < threadCount; i++) {
                final long start = i * chunkSize;
                final long end = (i == threadCount - 1) ? totalSize - 1 : (i + 1) * chunkSize - 1;
                final int threadIndex = i;
                final long chunkLength = end - start + 1;
                final long resumeAt = Math.min(chunkDone[i], chunkLength);

                // 跳过超出文件末尾或已完整完成的分片
                if (start >= totalSize || resumeAt >= chunkLength) {
                    continue;
                }

                Future<?> future = executor.submit(() -> {
                    try {
                        if (!cancelled) {
                            downloadChunk(saveFile, start, end, resumeAt, downloadedTotal,
                                    newBytes -> {
                                        synchronized (chunkDone) {
                                            chunkDone[threadIndex] = resumeAt + newBytes;
                                            // 仅在分片推进时节制刷盘，避免每个块都重写元数据
                                        }
                                    },
                                    () -> {
                                        // 多个分片线程会并发推进元数据，
                                        // 必须与 chunkDone 的更新使用同一把锁串行化读写
                                        synchronized (chunkDone) {
                                            saveResumeMeta(metaFile, totalSize, chunkSize,
                                                    threadCount, chunkDone);
                                        }
                                    });
                        }
                    } catch (Exception e) {
                        failedChunks.incrementAndGet();
                        if (!cancelled) {
                            AppLogger.warn("分片 " + threadIndex + " 下载失败: " + e.getMessage());
                        }
                    }
                });
                downloadFutures.add(future);
            }

            // 监控进度：只在有字节数增长时刷新活动时间，避免"零进度也刷新"导致停滞检测失效
            long lastTime = System.currentTimeMillis();
            long lastBytes = downloadedTotal.get();
            long lastProgressBytes = downloadedTotal.get();
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
        // 全部完成并校验通过后删除续传元数据；
        // 只有取消/失败时才保留，供下一次任务从断点继续
        if (metaFile.exists() && !metaFile.delete()) {
            AppLogger.warn("无法删除续传元数据文件: " + metaFile.getAbsolutePath());
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
                .header("Accept-Encoding", "identity")
                .header("Range", "bytes=0-2097151") // 测试2MB，增加测试数据量
                .GET()
                .build();

        HttpResponse<InputStream> response = httpClient.send(testRequest, HttpResponse.BodyHandlers.ofInputStream());

        // 非 206（如服务器不支持 Range 返回 200/403）也必须关闭响应体，避免连接泄漏
        if (response.statusCode() != 206) {
            response.body().close();
            return 0;
        }

        try (InputStream in = response.body()) {
            byte[] buffer = new byte[16384]; // 增大测试缓冲区
            int read;
            long endTime = startTime + testDuration;

            while (System.currentTimeMillis() < endTime && (read = in.read(buffer)) != -1) {
                bytesRead += read;
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
     * 下载单个分片（支持分片内断点：从 start+resumeAt 继续）。
     * 任何失败都抛出异常（由调用方计数并触发统一重试），
     * 保证不完整的数据永远不会被标记为完成。
     *
     * @param resumeAt    该分片此前已完成的字节数
     * @param progress    本分片本次尝试累计写入字节数的回调
     * @param persist     持久化续传元数据的回调（按字节增量节流调用）
     */
    private void downloadChunk(File saveFile, long start, long end, long resumeAt,
                               AtomicLong downloadedTotal,
                               java.util.function.LongConsumer progress,
                               Runnable persist) throws Exception {
        long requestStart = start + resumeAt;
        long expected = end - requestStart + 1;

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(fileURL))
                .header("User-Agent", getUserAgent())
                .header("Accept", "*/*")
                .header("Accept-Encoding", "identity")
                .header("Range", "bytes=" + requestStart + "-" + end)
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
            throw new IOException("HTTP " + response.statusCode() + "（分片续传请求期望 206）");
        }

        try (InputStream in = response.body();
             FileChannel fileChannel = FileChannel.open(saveFile.toPath(), WRITE, CREATE);
             ReadableByteChannel inChannel = Channels.newChannel(in);
             StallWatchdog watchdog = new StallWatchdog(in)) {

            long currentPosition = requestStart;
            long transferredTotal = 0;
            long lastPersisted = 0;
            long lastFlushTime = System.currentTimeMillis();

            while (transferredTotal < expected) {
                if (cancelled) {
                    // 取消时把已收字节落盘到元数据，下次可从断点继续
                    persist.run();
                    return;
                }
                // 阻塞式传输：不依赖 available()（部分 JDK/HTTP 实现长期返回 0，
                // 会导致空转误报）。连接停滞由看门狗负责——持续 30 秒无数据时
                // 关闭输入流解除阻塞，此处抛错触发统一重试
                long transferred = fileChannel.transferFrom(
                        inChannel, currentPosition, getBufferSize());

                if (transferred == 0) {
                    throw new IOException("数据流意外提前结束");
                }

                downloadedTotal.addAndGet(transferred);
                currentPosition += transferred;
                transferredTotal += transferred;
                watchdog.activity();

                // 限速：分片共享同一限速器，总速率被钳制在配额内（不限速时空操作）
                RateLimiter.acquire(limiter, transferred);

                progress.accept(transferredTotal);

                // 每推进约 1MB 刷一次续传元数据（崩溃最多回退 1MB）；
                // 元数据记录的是分片完成字节数，必须先 force 落盘数据再写元数据，
                // 否则崩溃后元数据"领先"磁盘造成续传文件出现空洞
                if (transferredTotal - lastPersisted >= 1024 * 1024) {
                    fileChannel.force(true);
                    persist.run();
                    lastPersisted = transferredTotal;
                }

                // 按照配置的间隔刷新缓冲区
                if (System.currentTimeMillis() - lastFlushTime > getFlushIntervalMs()) {
                    fileChannel.force(true); // 强制刷新到磁盘
                    lastFlushTime = System.currentTimeMillis();
                }
            }
            // 分片完整收尾：先强制落盘数据，再标记元数据（顺序不可颠倒）
            fileChannel.force(true);
            persist.run();
        }
    }

    /**
     * 读取停滞看门狗：HttpClient 的响应流没有读超时，服务器假死时
     * 阻塞中的 transferFrom 永远不会返回。看门狗持续监测最后活跃时间，
     * 超过阈值即关闭输入流，使阻塞中的读取立即抛出 IOException 触发重试。
     */
    private final class StallWatchdog implements AutoCloseable {

        private static final long STALL_TIMEOUT_MS = 30_000;

        private final InputStream stream;
        private final AtomicLong lastActivity = new AtomicLong(System.currentTimeMillis());
        private volatile boolean stopped = false;
        private final Thread thread;

        StallWatchdog(InputStream stream) {
            this.stream = stream;
            thread = new Thread(this::watch, "http-stall-watchdog");
            thread.setDaemon(true);
            thread.start();
        }

        /** 每次成功读到数据时调用，重置停滞计时 */
        void activity() {
            lastActivity.set(System.currentTimeMillis());
        }

        private void watch() {
            while (!stopped) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    return;
                }
                if (System.currentTimeMillis() - lastActivity.get() > STALL_TIMEOUT_MS) {
                    try {
                        stream.close(); // 解除阻塞中的 read/transferFrom
                    } catch (IOException ignored) {
                    }
                    return;
                }
            }
        }

        @Override
        public void close() {
            stopped = true;
            thread.interrupt();
        }
    }

    private void downloadWithSingleThread(File saveFile) throws Exception {
        // 单线程路径不使用多线程分片元数据，清掉历史残留，避免完成后留下误导性侧车
        File staleMeta = new File(saveFile.getAbsolutePath() + ".partmeta");
        if (staleMeta.exists()) {
            staleMeta.delete();
        }

        long start = downloadedSize;

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(fileURL))
                .header("User-Agent", getUserAgent())
                .header("Accept", "*/*")
                .header("Accept-Encoding", "identity");
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
        if (start == 0 && response.statusCode() != 200 && response.statusCode() != 206) {
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
             ReadableByteChannel inChannel = Channels.newChannel(in);
             StallWatchdog watchdog = new StallWatchdog(in)) {

            long downloaded = start;
            long lastTime = System.currentTimeMillis();
            long lastBytes = start;
            long lastFlushTime = System.currentTimeMillis();

            while (!cancelled) {
                // 总大小已知且已下满则结束
                if (totalSize > 0 && downloaded >= totalSize) {
                    break;
                }
                // 阻塞式传输：不依赖 available()；停滞与假死由看门狗解除
                long transferred = fileChannel.transferFrom(
                        inChannel, downloaded, getBufferSize());

                if (transferred == 0) {
                    // 服务器正常关闭连接，数据流结束（总大小未知时以此判断完成）
                    break;
                }

                downloaded += transferred;
                watchdog.activity();

                // 限速：按本次传输字节数获取令牌（不限速时空操作）
                RateLimiter.acquire(limiter, transferred);

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

    /** 多线程分片续传元数据（与 .partmeta 侧车文件一一对应） */
    private static final class ResumeMeta {
        final long totalSize;
        final long chunkSize;
        final int threadCount;
        final long[] done;

        ResumeMeta(long totalSize, long chunkSize, int threadCount, long[] done) {
            this.totalSize = totalSize;
            this.chunkSize = chunkSize;
            this.threadCount = threadCount;
            this.done = done;
        }
    }

    /**
     * 加载并校验续传元数据。任何不一致（文件缺失/损坏、总大小或分片数对不上、
     * 目标文件不存在或长度不符、分片进度越界）都返回 null，调用方按全新下载处理。
     */
    private ResumeMeta loadResumeMeta(File metaFile, File saveFile, long totalSize) {
        if (!metaFile.exists() || !saveFile.exists() || saveFile.length() != totalSize) {
            return null;
        }
        Properties props = new Properties();
        try (FileInputStream in = new FileInputStream(metaFile)) {
            props.load(in);
        } catch (Exception e) {
            return null;
        }
        try {
            long metaTotal = Long.parseLong(props.getProperty("totalSize", ""));
            long chunkSize = Long.parseLong(props.getProperty("chunkSize", ""));
            int threadCount = Integer.parseInt(props.getProperty("threadCount", ""));
            if (metaTotal != totalSize || chunkSize <= 0 || threadCount <= 0) {
                return null;
            }
            long[] done = new long[threadCount];
            for (int i = 0; i < threadCount; i++) {
                long start = (long) i * chunkSize;
                if (start >= totalSize) {
                    // 分片数比实际可覆盖的还多，元数据与总大小不自洽
                    return null;
                }
                long end = (i == threadCount - 1) ? totalSize - 1
                        : (long) (i + 1) * chunkSize - 1;
                long chunkLength = end - start + 1;
                long d = Long.parseLong(props.getProperty("done." + i, ""));
                if (d < 0 || d > chunkLength) {
                    return null;
                }
                done[i] = d;
            }
            return new ResumeMeta(totalSize, chunkSize, threadCount, done);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 保存续传元数据（原子替换，避免崩溃时写坏侧车） */
    private void saveResumeMeta(File metaFile, long totalSize, long chunkSize,
                                int threadCount, long[] done) {
        Properties props = new Properties();
        props.setProperty("totalSize", Long.toString(totalSize));
        props.setProperty("chunkSize", Long.toString(chunkSize));
        props.setProperty("threadCount", Integer.toString(threadCount));
        for (int i = 0; i < threadCount; i++) {
            props.setProperty("done." + i, Long.toString(done[i]));
        }
        File tmp = new File(metaFile.getAbsolutePath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            props.store(out, "OmniFetch HTTP chunk resume metadata");
        } catch (IOException e) {
            // 元数据写失败不致命：最坏情况是下次从头重下，不影响本次数据正确性
            tmp.delete();
            AppLogger.warn("续传元数据写入失败: " + e.getMessage());
            return;
        }
        // 必须用 Files.move(REPLACE_EXISTING) 而非 File.renameTo：
        // Windows 上 renameTo 在目标文件已存在时会失败，导致侧车只在首次写入后再不更新
        // （分片进度永远停留在 0，分片级断点续传形同失效）
        try {
            Files.move(tmp.toPath(), metaFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            try {
                Files.move(tmp.toPath(), metaFile.toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException fallback) {
                tmp.delete();
                AppLogger.warn("续传元数据替换失败: " + metaFile.getAbsolutePath());
            }
        } catch (IOException e) {
            tmp.delete();
            AppLogger.warn("续传元数据替换失败: " + metaFile.getAbsolutePath());
        }
    }

    /**
     * 把连接阶段的底层异常翻译成带主机与排查建议的错误。
     * 本机常见做法是用 hosts 把 GitHub 域名指向 127.0.0.1 的加速工具，
     * 工具未运行或证书未受信任时，直接抛出的是笼统的 PKIX / Connection refused。
     */
    private IOException describeConnectionError(IOException e) {
        String host;
        try {
            host = URI.create(fileURL).getHost();
        } catch (IllegalArgumentException ignored) {
            host = fileURL;
        }
        boolean handshake = false;
        boolean refused = false;
        boolean unknownHost = false;
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SSLHandshakeException) {
                handshake = true;
            } else if (t instanceof ConnectException) {
                refused = true;
            } else if (t instanceof UnknownHostException) {
                unknownHost = true;
            }
        }
        String hint;
        if (handshake) {
            hint = "HTTPS 证书校验失败；若本机使用 GitHub 加速/代理工具，"
                    + "请确认其证书已受信任，或改为直连（检查 hosts 与系统代理）";
        } else if (refused) {
            hint = "连接被拒绝；请检查 hosts 中的加速条目是否失效、"
                    + "系统代理是否开启、防火墙是否拦截";
        } else if (unknownHost) {
            hint = "域名解析失败；请检查网络连接与 hosts 配置";
        } else {
            return e;
        }
        String detail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return new IOException(detail + "（主机 " + host + "：" + hint + "）", e);
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
