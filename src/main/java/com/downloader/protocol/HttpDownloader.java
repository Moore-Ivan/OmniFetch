package com.downloader.protocol;

import com.downloader.config.ConfigManager;
import com.downloader.model.ProgressCallback;
import com.downloader.util.AppLogger;
import com.downloader.util.FileUtils;
import com.downloader.util.GitHubAccelerator;
import com.downloader.util.HttpTrust;
import com.downloader.util.NetworkProxy;
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
 * 使用 Java 17+ HttpClient（HTTP/1.1，每个并发分片独占连接），支持多线程分段下载
 */
public class HttpDownloader implements DownloadProtocol {

    private final String fileURL;
    private final String savePath;
    private final ProgressCallback callback;
    private volatile boolean cancelled = false;
    private volatile HttpClient httpClient;
    /** 当前使用的代理（null=直连）；直连失败时会切换到自动检测到的代理 */
    private volatile NetworkProxy.Config activeProxy;
    /** GitHub 加速线路（null=直连；非空=内置镜像入口地址），仅对 GitHub 域名启用 */
    private volatile GitHubAccelerator.Route route;
    /** 上次线路探测时间（节流，避免多个分片同时失败时反复探测） */
    private volatile long lastRouteSelectMs = 0L;
    /** 线路切换次数（每个任务最多切换两次，防止坏网络下无限重试） */
    private final AtomicInteger routeSwitches = new AtomicInteger();
    /** 分片并发/限流自适应（仅多线程路径使用，null 表示未启用） */
    private volatile DownloadThrottle throttle;
    private List<Future<?>> downloadFutures;
    private long downloadedSize = 0;
    private String saveFilePath;
    /** 任务级限速器（字节/秒，0=不限）；可为 null（插件/旧构造通道） */
    private final RateLimiter limiter;

    /**
     * 实际下载地址：GitHub release 原始链接会 302 到带签名的 CDN 地址
     * （release-assets.githubusercontent.com，有效期约 45 分钟）。
     * 缓存它可以让所有分片直接访问 CDN，避免每个分片/每次重连都先经过
     * github.com——该跳转在部分网络下很慢或会被重置，是"断断续续"的根源之一。
     */
    private volatile String resolvedDownloadUrl;
    private final Object resolvedUrlLock = new Object();
    /** 上次尝试重新解析地址的时间（仅用于刷新节流，避免多个分片同时 403 时刷爆 GitHub） */
    private volatile long lastRefreshMs = 0L;
    /** 地址刷新代次：只增不减，用于防止较早发出请求的旧地址覆盖刷新后的新地址 */
    private volatile long refreshEpoch = 0L;

    private final ConfigManager configManager = ConfigManager.getInstance();
    
    private int getBufferSize() {
        return configManager.getInt("download.bufferSize", 262144);
    }
    
    private int getConnectTimeoutSec() {
        return configManager.getInt("download.connectTimeoutSec", 15);
    }

    /**
     * 等待响应头的最长时限（秒）。connectTimeout 只覆盖建立连接：
     * 服务器接受连接后不返回响应（防火墙静默丢包、加速工具假死）时，
     * HttpClient 会无限期等待响应头，任务将永久卡在"下载中"，因此每个
     * 请求都必须带上该超时，把"无声挂起"变成可重试的明确失败。
     */
    private int getResponseTimeoutSec() {
        return configManager.getInt("download.responseTimeoutSec", 30);
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

    /**
     * 创建 HttpClient：HTTP/1.1 + 自动跟随重定向 + 全局信任证书。
     *
     * <p>刻意使用 HTTP/1.1 而非 HTTP/2：GitHub CDN（release-assets.githubusercontent.com
     * 等）会把 HTTP/2 的多条并发流多路复用到一个 TCP 连接上，所有分片共享同一拥塞窗口
     * 与流控窗口——一条流被限速/卡住，其余分片一起停顿；看门狗关闭某个响应体时还会
     * 把整条连接池污染。HTTP/1.1 下每个并发分片独占一条 TCP 连接，吞吐随连接数增长，
     * 单条连接出问题也不会影响其它分片。
     *
     * <p>与更新检查/更新包下载共用 HttpTrust：GitHub 等站点在本机常被加速工具
     * （hosts 指向 127.0.0.1）用自签证书接管，或运行环境 cacerts 不全，
     * 不配置会以 PKIX/certificate_unknown 直接失败
     */
    private HttpClient newHttpClient() {
        HttpClient.Builder clientBuilder = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(getConnectTimeoutSec()))
                .followRedirects(HttpClient.Redirect.NORMAL);
        if (HttpTrust.SSL_CONTEXT != null) {
            clientBuilder.sslContext(HttpTrust.SSL_CONTEXT);
        }
        NetworkProxy.Config proxy = activeProxy;
        if (proxy != null) {
            clientBuilder.proxy(proxy.selector);
            if (proxy.authenticator != null) {
                clientBuilder.authenticator(proxy.authenticator);
            }
        }
        return clientBuilder.build();
    }

    /** 构建元数据 HEAD 请求（地址可能因线路切换/地址刷新而变化，因此每次重新构建） */
    private HttpRequest buildHeadRequest() {
        return HttpRequest.newBuilder()
                .uri(URI.create(currentDownloadUrl()))
                .header("User-Agent", getUserAgent())
                .header("Accept", "*/*")
                // 显式要求不压缩传输：压缩响应会让落盘内容与原始文件不一致
                .header("Accept-Encoding", "identity")
                .timeout(Duration.ofSeconds(getResponseTimeoutSec()))
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .build();
    }

    /** 429/503 等"服务器暂时不可用/限流"状态：值得退避后重试 */
    private static boolean isTransientStatus(int code) {
        return code == 429 || code == 503 || code == 502 || code == 504;
    }

    /** 依据 Retry-After（秒数或 HTTP 日期）或状态码给出本次重试等待时间 */
    private static long transientRetryWait(int code, HttpResponse<?> response) {
        long retryAfter = parseRetryAfterMs(
                response.headers().firstValue("Retry-After").orElse(null));
        if (retryAfter > 0) {
            return retryAfter;
        }
        return code == 429 ? 3_000L : 1_500L;
    }

    /**
     * 解析 Retry-After 头，返回毫秒（上限 60 秒）；无法解析返回 0。
     * 服务器明确给出建议等待时间时应优先遵守，否则会持续打满限流阈值。
     */
    private static long parseRetryAfterMs(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return Math.min(60_000L, Long.parseLong(value.trim()) * 1000L);
        } catch (NumberFormatException ignored) {
            // 不是秒数，继续尝试 HTTP 日期格式
        }
        try {
            java.time.ZonedDateTime at = java.time.ZonedDateTime.parse(value.trim(),
                    java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME);
            long ms = java.time.Duration.between(java.time.ZonedDateTime.now(), at).toMillis();
            return Math.min(60_000L, Math.max(0L, ms));
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(Math.max(1L, ms));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 直连失败时尝试切换到自动检测到的代理（环境变量 / Windows 系统代理）。
     * 已在使用代理、禁用了代理或未检测到代理时返回 false。
     */
    private boolean switchToProxy() {
        if (activeProxy != null) {
            return false;
        }
        NetworkProxy.Config detected = NetworkProxy.detected();
        if (detected == null) {
            return false;
        }
        synchronized (this) {
            if (activeProxy != null) {
                return false;
            }
            activeProxy = detected;
            httpClient = newHttpClient();
            AppLogger.warn("直连失败，已改用代理: " + detected.description);
            return true;
        }
    }

    /**
     * 重新解析实际下载地址。GitHub release 的签名地址有效期约 45 分钟，
     * 过期后返回 403；此时必须重新访问线路入口地址、跟随 302 拿到新的签名地址
     * 再继续断点续传。刷新做了节流：15 秒内不重复解析，避免多个分片
     * 同时遇到 403 时刷爆 GitHub。
     */
    private String refreshDownloadUrl() throws IOException, InterruptedException {
        synchronized (resolvedUrlLock) {
            // 节流键是"上次尝试刷新"的时间：分片成功不会改变它，
            // 因此真正过期（403）时一定能重新解析
            long now = System.currentTimeMillis();
            if (now - lastRefreshMs < 15_000L && resolvedDownloadUrl != null) {
                return resolvedDownloadUrl;
            }
            lastRefreshMs = now;
            // 必须从线路入口重新解析：镜像入口与原始链接都可能 302 到带签名的新地址，
            // 直接重试旧签名地址仍然会 403
            String entryUrl = route != null ? route.url : fileURL;
            HttpClient probeClient = newHttpClient();
            try {
                HttpResponse<Void> resp = probeClient.send(
                        HttpRequest.newBuilder()
                                .uri(URI.create(entryUrl))
                                .header("User-Agent", getUserAgent())
                                .header("Accept", "*/*")
                                .header("Accept-Encoding", "identity")
                                .timeout(Duration.ofSeconds(getResponseTimeoutSec()))
                                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                                .build(),
                        HttpResponse.BodyHandlers.discarding());
                if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                    // 刷新成功：原子替换地址并推进代次，让在途的旧请求无法把旧地址写回
                    resolvedDownloadUrl = resp.uri().toString();
                    refreshEpoch++;
                }
                return resolvedDownloadUrl != null ? resolvedDownloadUrl : entryUrl;
            } finally {
                probeClient.close();
            }
        }
    }

    /**
     * 记录最终资源地址（可能是 302 后的签名 CDN 地址）。
     * 记录后所有分片都直接请求该地址，不必每个分片都先经过 github.com/镜像入口——
     * 那一次跳转在受限网络下既慢又容易被重置，是"断断续续"的来源之一。
     */
    private void rememberResolvedUrl(URI finalUri) {
        rememberResolvedUrl(finalUri, -1L);
    }

    /**
     * @param expectedEpoch 分片发请求前捕获的刷新代次；期间地址一旦被刷新，旧请求的
     *                      最终地址就不再写回，避免把已失效的签名地址覆盖到新地址上
     */
    private void rememberResolvedUrl(URI finalUri, long expectedEpoch) {
        if (finalUri == null) {
            return;
        }
        synchronized (resolvedUrlLock) {
            if (expectedEpoch >= 0 && expectedEpoch != refreshEpoch) {
                return;
            }
            resolvedDownloadUrl = finalUri.toString();
        }
    }

    /** 判断错误是否为签名地址过期/失效（需要重新解析地址） */
    private static boolean isUrlExpired(String message) {
        return message != null && (message.contains("HTTP 403")
                || message.contains("HTTP 404") || message.contains("HTTP 410"));
    }

    /**
     * 复核服务器是否支持 Range（GET bytes=0-0）。
     * 使用独立临时 client：避免与刚完成的 HEAD 复用同一条连接
     * （GitHub 上 HEAD 后复用连接容易触发超时/被重置）。
     *
     * @return -1=不支持；0=支持但总长度未知；&gt;0=支持且返回总长度
     */
    private long probeRangeSupport() {
        HttpClient client = newHttpClient();
        try {
            HttpResponse<InputStream> response = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create(currentDownloadUrl()))
                            .header("User-Agent", getUserAgent())
                            .header("Accept", "*/*")
                            .header("Accept-Encoding", "identity")
                            .header("Range", "bytes=0-0")
                            .timeout(Duration.ofSeconds(getResponseTimeoutSec()))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = response.body()) {
                if (response.statusCode() != 206) {
                    return -1;
                }
                rememberResolvedUrl(response.uri());
                long total = UpdateDownloader.parseTotalFromContentRange(
                        response.headers().firstValue("Content-Range").orElse(null));
                return total > 0 ? total : 0;
            }
        } catch (Exception e) {
            return -1;
        } finally {
            client.close();
        }
    }

    /**
     * 当前请求应使用的地址：优先使用已解析的最终地址（签名 CDN 或镜像回源地址），
     * 否则退回到线路入口（镜像入口 / 原始链接）。
     *
     * <p>镜像入口只用于"解析"：一旦探测/首次请求跟随 302 拿到了最终地址，就缓存下来
     * 供所有分片直接使用。探测阶段既然能跟随跳转成功，说明最终地址对本机可达，
     * 缓存它只会更快（省去每个分片的一次跳转），并在 403 时自动重新解析。
     */
    private String currentDownloadUrl() {
        String resolved = resolvedDownloadUrl;
        if (resolved != null) {
            return resolved;
        }
        GitHubAccelerator.Route current = route;
        return current != null ? current.url : fileURL;
    }

    /** 只有 GitHub 链接才需要做线路选择 */
    private boolean acceleratorApplicable() {
        return GitHubAccelerator.enabled() && GitHubAccelerator.isGitHubUrl(fileURL);
    }

    /**
     * 下载开始前探测并选定线路（直连或内置镜像中最快且可用的一个）。
     * 探测失败不阻断下载：仍按直连尝试，后续失败还会自动切换。
     */
    private void selectInitialRoute() {
        if (!acceleratorApplicable()) {
            return;
        }
        GitHubAccelerator.Route best = GitHubAccelerator.select(
                fileURL, getUserAgent(), 10, null);
        lastRouteSelectMs = System.currentTimeMillis();
        if (best == null) {
            AppLogger.warn("GitHub 加速线路探测未找到可用线路，继续尝试直连");
            return;
        }
        route = best.mirror ? best : null;
        if (best.mirror) {
            AppLogger.info("已选用 GitHub 加速线路: " + best);
        } else {
            AppLogger.info("GitHub 直连线路最快: " + best);
        }
    }

    /**
     * 线路故障后重新探测并切换到其它线路。
     * 返回 true 表示已切换（调用方可以在同一分片内从断点继续）。
     */
    private boolean trySwitchRoute() {
        if (!acceleratorApplicable() || routeSwitches.get() >= 2) {
            return false;
        }
        synchronized (resolvedUrlLock) {
            long now = System.currentTimeMillis();
            if (now - lastRouteSelectMs < 5_000L) {
                return false;
            }
            lastRouteSelectMs = now;
        }
        String currentUrl = route == null ? fileURL : route.url;
        GitHubAccelerator.Route best = GitHubAccelerator.select(
                fileURL, getUserAgent(), 10, currentUrl);
        if (best == null || best.url.equals(currentUrl)) {
            return false;
        }
        routeSwitches.incrementAndGet();
        route = best.mirror ? best : null;
        // 换线路后旧的签名地址不再适用，必须重新解析
        resolvedDownloadUrl = null;
        lastRefreshMs = 0L;
        refreshEpoch++;
        AppLogger.warn("下载线路多次失败，已切换为: " + best);
        return true;
    }

    @Override
    public void download() throws Exception {
        // 代理优先于直连：手动配置 > 环境变量 > Windows 系统代理（见 NetworkProxy）。
        // 都在没有时才直连；直连探测失败时还会再次检测并自动切换（switchToProxy）
        activeProxy = NetworkProxy.initial();
        if (activeProxy != null) {
            AppLogger.info("使用代理: " + activeProxy.description);
        }
        httpClient = newHttpClient();
        // GitHub 链接：应用内自动探测"直连/内置镜像"，选最快且可用的线路，
        // 用户无需安装第三方加速工具
        selectInitialRoute();
        // 元数据探测使用独立的 client：GitHub release 链接（302 → release-assets.githubusercontent.com）
        // 在同一个连接上先发 HEAD 再发 Range 请求容易被 CDN 判定/复用为失效状态而迟迟收不到响应；
        // 探测与下载分离后可正常下载
        HttpClient probeClient = newHttpClient();

        String fileName;
        long totalSize;
        boolean supportRange;
        HttpClient.Version version;

        try {
            // 先检查是否已取消
            if (cancelled) {
                return;
            }

            // 先发送 HEAD 请求获取文件信息（429/503 与签名地址过期都自动重试，
            // 避免一次瞬时失败就把任务打成"失败→退避→重来"的断续状态）
            HttpRequest headRequest = buildHeadRequest();

            HttpResponse<Void> headResponse;
            int headRetries = 0;
            while (true) {
                if (cancelled) {
                    return;
                }
                try {
                    headResponse = probeClient.send(
                            headRequest, HttpResponse.BodyHandlers.discarding());
                } catch (IOException e) {
                    // 本地无法直连 GitHub 时，若系统/环境代理可用则切换后重试一次
                    if (!switchToProxy()) {
                        // 首次连接阶段失败统一翻译为带主机与排查建议的错误，避免只看到笼统异常
                        throw describeConnectionError(e);
                    }
                    probeClient.close();
                    probeClient = newHttpClient();
                    headRequest = buildHeadRequest();
                    continue;
                }

                int code = headResponse.statusCode();
                boolean expired = resolvedDownloadUrl != null && isUrlExpired("HTTP " + code);
                if ((isTransientStatus(code) || expired) && headRetries++ < 3) {
                    long wait = transientRetryWait(code, headResponse);
                    AppLogger.warn("元数据请求暂时失败（HTTP " + code + "），"
                            + Math.max(1, wait / 1000) + " 秒后重试");
                    sleepQuietly(wait);
                    if (expired) {
                        try {
                            refreshDownloadUrl();
                        } catch (Exception refreshError) {
                            AppLogger.warn("刷新下载地址失败: " + refreshError.getMessage());
                        }
                    }
                    // 失败的连接可能已被对端关闭，换一个干净的 client 再试
                    probeClient.close();
                    probeClient = newHttpClient();
                    headRequest = buildHeadRequest();
                    continue;
                }
                break;
            }

            if (cancelled) {
                return;
            }

            if (headResponse.statusCode() >= 200 && headResponse.statusCode() < 300) {
                rememberResolvedUrl(headResponse.uri());
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
                HttpResponse<InputStream> probe = probeClient.send(
                        HttpRequest.newBuilder()
                                .uri(URI.create(currentDownloadUrl()))
                                .header("User-Agent", getUserAgent())
                                .header("Accept", "*/*")
                                .header("Accept-Encoding", "identity")
                                .header("Range", "bytes=0-0")
                                .timeout(Duration.ofSeconds(getResponseTimeoutSec()))
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
                    rememberResolvedUrl(probe.uri());
                }
            }

            // 部分镜像/服务器 HEAD 不返回 Accept-Ranges，但实际支持 Range：
            // 用一次 bytes=0-0 复核，避免本可多线程的线路退化成单连接下载
            if (!supportRange) {
                long rangeTotal = probeRangeSupport();
                if (rangeTotal >= 0) {
                    supportRange = true;
                    if (totalSize <= 0 && rangeTotal > 0) {
                        totalSize = rangeTotal;
                    }
                }
            }
        } finally {
            // 探测结束立即释放探测 client（Java 21 HttpClient 可关闭），
            // 避免其连接池线程常驻；后续下载只使用 httpClient
            probeClient.close();
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
        // 小文件（<1MB）分片收益为负：切 8 个分片意味着 8 次建连/跳转，反而更慢
        if (supportRange && totalSize > 0 && totalSize >= 1_048_576L) {
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
            // 先进行速度测试，确定合适的分片策略（仅全新下载时测速，续传沿用旧参数）。
            // 小文件不值得测速（测速要额外读最多 2MB）。
            // GitHub 链接不做测速：测速本身是一次额外的 Range 请求，与随后的分片请求
            // 叠加会触发 CDN 限流（日志中常见的 503），而 GitHub 的线程数按文件大小
            // 分档已经足够稳；测速失败也不能中断下载，按低速策略继续
            long testSpeed = 0;
            if (totalSize > 4L * 1024 * 1024 && !GitHubAccelerator.isGitHubUrl(fileURL)) {
                try {
                    testSpeed = testConnectionSpeed();
                } catch (Exception e) {
                    AppLogger.warn("连接速度测试失败，按低速策略继续: " + e.getMessage());
                    // 测速请求超时可能留下不可复用的连接，重建 client 再开始分片
                    httpClient = newHttpClient();
                }
            }
            threadCount = determineThreadCount(testSpeed, totalSize);
            chunkSize = determineChunkSize(totalSize, threadCount);
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

        // 分片并发/限流自适应：初始并发=线程数，遇到 429/503 自动降档冷却
        throttle = new DownloadThrottle(threadCount);

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
                } else if (now - lastProgressTime > 120_000) {
                    // 限流冷却（Retry-After 可能十几秒）不应触发停滞判定；
                    // 真正不可用的线路交给分片重试与线路切换处理
                    throw new IOException("下载停滞超过 120 秒，已中止本次尝试");
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
                .uri(URI.create(currentDownloadUrl()))
                .header("User-Agent", getUserAgent())
                .header("Accept", "*")
                .header("Accept-Encoding", "identity")
                .header("Range", "bytes=0-2097151") // 测试2MB，增加测试数据量
                .timeout(Duration.ofSeconds(getResponseTimeoutSec()))
                .GET()
                .build();

        HttpResponse<InputStream> response = httpClient.send(testRequest, HttpResponse.BodyHandlers.ofInputStream());

        // 非 206（如服务器不支持 Range 返回 200/403）也必须关闭响应体，避免连接泄漏
        if (response.statusCode() != 206) {
            response.body().close();
            return 0;
        }

        // 测速读取同样可能卡死（服务器接受连接后停发数据），
        // 用与分片下载相同的看门狗解除阻塞，避免任务在测速阶段无声挂起
        try (InputStream in = response.body();
             StallWatchdog watchdog = new StallWatchdog(in)) {
            byte[] buffer = new byte[16384]; // 增大测试缓冲区
            int read;
            long endTime = startTime + testDuration;

            while (System.currentTimeMillis() < endTime && (read = in.read(buffer)) != -1) {
                bytesRead += read;
                watchdog.activity();
            }
        }

        long elapsedTime = System.currentTimeMillis() - startTime;
        if (elapsedTime == 0) elapsedTime = 1;

        return (bytesRead * 1000) / elapsedTime;
    }
    
    /**
     * 确定线程数。
     *
     * <p>GitHub CDN 有两面性：单连接带宽被限速（需要多连接聚合），但对"总并发"
     * 也敏感（并发过高直接 429/503）。日志证实 10 个分片并发下载一个十几 MB 的
     * 文件会立刻收到 503，因此 GitHub 链接按文件大小给出 4/6/8 的稳妥档位，
     * 而不是像普通站点那样 8-10 路全开。其他站点沿用测速策略。
     */
    private int determineThreadCount(long speed, long totalSize) {
        int maxThreads = getMaxThreads();

        if (GitHubAccelerator.isGitHubUrl(fileURL)) {
            if (totalSize <= 8L * 1024 * 1024) {
                return Math.min(4, maxThreads);
            }
            if (totalSize <= 64L * 1024 * 1024) {
                return Math.min(6, maxThreads);
            }
            return Math.min(8, maxThreads);
        }

        // 慢速连接（< 1MB/s）：使用更多线程。
        // 慢速 CDN 常按单连接限速，连接数比"每片更大"更能提升总吞吐
        if (speed < 1048576) {
            return Math.min(8, maxThreads);
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
     * 确定分片大小。
     * 必须按线程数均分，不能用 minChunkSize 兜底：否则小于 5MB 的文件
     * （如 GitHub 上 1.6MB 的 7z 安装包）只会切出 1 个分片，多线程配置
     * 形同虚设，退化成单连接慢速下载。分片数本身不会超过线程数，
     * 因此无需额外下限。
     */
    private long determineChunkSize(long totalSize, int threadCount) {
        long chunkSize = Math.max(1, (totalSize + threadCount - 1) / threadCount);
        return Math.min(chunkSize, getMaxChunkSize());
    }

    /**
     * 下载单个分片（支持分片内断点：从 start+resumeAt 继续）。
     * 网络抖动（GitHub CDN 常见的 connection reset / 流提前 EOF）会在同一分片内
     * 从已收位置重连续传，而不是直接抛给上层让整个任务退避重来——
     * 后者表现为下载"断断续续"、每个分片失败都要停顿数秒。
     *
     * <p>429/503 走完全不同的处理路径：那是服务器主动限流，不是线路故障，
     * 必须遵守 Retry-After 全局冷却、降低并发后原地重试，绝不能切换线路
     * （切走会丢掉唯一可用的线路，且新线路同样会被限流）。
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
        long transferredTotal = 0;
        long lastResetProgress = 0;
        int reconnects = 0;
        int throttleRetries = 0;
        final int maxReconnects = 12;
        final int maxThrottleRetries = 20;

        while (transferredTotal < expected) {
            if (cancelled) {
                persist.run();
                return;
            }
            try {
                final long base = transferredTotal;
                long received = downloadChunkOnce(saveFile, requestStart + base, end,
                        downloadedTotal,
                        n -> progress.accept(base + n), persist);
                if (received == 0) {
                    throw new IOException("分片请求未返回数据");
                }
                transferredTotal += received;
                throttleRetries = 0;
                // 累计有 256KB 以上进展就清零重连计数：能稳定推进的连接即使偶尔中断
                // 也不该被判失败；但每次都只回少量字节"假推进"的不能清零，
                // 否则病态连接会让分片永远重试下去
                if (transferredTotal - lastResetProgress >= 256 * 1024) {
                    reconnects = 0;
                    lastResetProgress = transferredTotal;
                }
            } catch (IOException e) {
                if (cancelled) {
                    persist.run();
                    return;
                }

                // 服务器限流：全体分片统一冷却（含 Retry-After）+ 降低并发后原地重试
                if (e instanceof ThrottledException throttled) {
                    DownloadThrottle gate = throttle;
                    if (gate != null) {
                        gate.throttled(throttled.retryAfterMs);
                    }
                    persist.run();
                    if (++throttleRetries > maxThrottleRetries) {
                        throw e;
                    }
                    AppLogger.warn("分片（" + start + "-" + end + "）被限流（HTTP "
                            + throttled.code + "），已收 " + FileUtils.formatSize(transferredTotal)
                            + "，按退避策略重试");
                    continue;
                }

                if (++reconnects > maxReconnects) {
                    // 同一分片反复失败通常意味着整条线路不可用（镜像失效/直连被阻断）：
                    // 换一条探测到的最快可用线路后，从已收字节处继续，而不是让整个任务失败重来
                    if (trySwitchRoute()) {
                        reconnects = 0;
                        persist.run();
                        continue;
                    }
                    throw e;
                }
                // 签名地址过期（GitHub 约 45 分钟）会返回 403/410：先刷新地址再重连
                if (isUrlExpired(e.getMessage())) {
                    try {
                        refreshDownloadUrl();
                    } catch (Exception refreshError) {
                        AppLogger.warn("刷新下载地址失败: " + refreshError.getMessage());
                    }
                }
                // 直连分片连续失败时，尝试切换到自动检测到的系统/环境代理
                // （典型场景：应用启动后才打开 Clash 并开启系统代理）
                if (activeProxy == null && reconnects >= 2 && switchToProxy()) {
                    AppLogger.info("分片将继续通过代理 " + activeProxy.description + " 下载");
                }
                AppLogger.warn("分片（" + start + "-" + end + "）重连（第 " + reconnects
                        + " 次），已收 " + FileUtils.formatSize(transferredTotal)
                        + "，原因: " + e.getMessage());
                persist.run();
                // 指数退避 + 抖动：固定 300ms*N 在受限网络下会持续打满对端
                long backoff = Math.min(8_000L, 500L * (1L << Math.min(reconnects - 1, 4)));
                backoff += ThreadLocalRandom.current().nextLong(0, 500);
                sleepQuietly(backoff);
            }
        }
    }

    /**
     * 单次尝试下载 [requestStart, end] 范围并写入文件。
     *
     * @return 本次实际接收的字节数（取消时返回已接收字节数）
     */
    private long downloadChunkOnce(File saveFile, long requestStart, long end,
                                   AtomicLong downloadedTotal,
                                   java.util.function.LongConsumer progress,
                                   Runnable persist) throws Exception {
        long expected = end - requestStart + 1;

        if (cancelled) {
            return 0;
        }

        DownloadThrottle gate = throttle;
        if (gate != null) {
            gate.awaitCooldown();
            gate.acquire();
        }
        try {
            String requestUrl = currentDownloadUrl();
            long epoch = refreshEpoch;
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(requestUrl))
                    .header("User-Agent", getUserAgent())
                    .header("Accept", "*/*")
                    .header("Accept-Encoding", "identity")
                    .header("Range", "bytes=" + requestStart + "-" + end)
                    .timeout(Duration.ofSeconds(getResponseTimeoutSec()))
                    .GET()
                    .build();

            HttpResponse<InputStream> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofInputStream());

            int code = response.statusCode();
            if (isTransientStatus(code)) {
                long retryAfter = parseRetryAfterMs(
                        response.headers().firstValue("Retry-After").orElse(null));
                response.body().close();
                throw new ThrottledException(code, retryAfter);
            }
            if (cancelled) {
                response.body().close();
                return 0;
            }
            if (code != 206) {
                response.body().close();
                throw new IOException("HTTP " + code + "（分片续传请求期望 206）");
            }
            // 分片请求是跟随 302 之后的最终地址：记住它，后续分片直接命中 CDN，
            // 不必每个分片都先经过 github.com/镜像入口（少一次跳转、少一次被重置的机会）
            rememberResolvedUrl(response.uri(), epoch);

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
                        return transferredTotal;
                    }
                    // 阻塞式传输：不依赖 available()（部分 JDK/HTTP 实现长期返回 0，
                    // 会导致空转误报）。连接停滞由看门狗负责——持续无数据时
                    // 关闭输入流解除阻塞，此处抛错触发分片内重连
                    long transferred = fileChannel.transferFrom(
                            inChannel, currentPosition, getBufferSize());

                    if (transferred == 0) {
                        // 连接被对端重置时常表现为流提前 EOF：先落盘已收数据，
                        // 再抛出异常让外层在同一分片内重连续传
                        fileChannel.force(false);
                        persist.run();
                        throw new IOException("数据流意外提前结束");
                    }

                    downloadedTotal.addAndGet(transferred);
                    currentPosition += transferred;
                    transferredTotal += transferred;
                    watchdog.activity();

                    // 限速：分片共享同一限速器，总速率被钳制在配额内（不限速时空操作）
                    RateLimiter.acquire(limiter, transferred);

                    progress.accept(transferredTotal);

                    // 每推进约 4MB 刷一次续传元数据（崩溃最多回退 4MB）。
                    // 只用 force(false)（数据页）：force(true) 每次都同步元数据/文件大小，
                    // 多分片并发时在 Windows 上会明显拖慢整体吞吐。先落盘数据再写元数据，
                    // 否则崩溃后元数据"领先"磁盘会造成续传文件出现空洞
                    if (transferredTotal - lastPersisted >= 4L * 1024 * 1024) {
                        fileChannel.force(false);
                        persist.run();
                        lastPersisted = transferredTotal;
                    }

                    // 按照配置的间隔刷新缓冲区（同样不做元数据同步）
                    if (System.currentTimeMillis() - lastFlushTime > getFlushIntervalMs()) {
                        fileChannel.force(false);
                        lastFlushTime = System.currentTimeMillis();
                    }
                }
                // 分片完整收尾：先强制落盘数据，再标记元数据（顺序不可颠倒）
                fileChannel.force(false);
                persist.run();
                if (gate != null) {
                    gate.recovered();
                }
                return transferredTotal;
            }
        } finally {
            if (gate != null) {
                gate.release();
            }
        }
    }

    /**
     * 读取停滞看门狗：HttpClient 的响应流没有读超时，服务器假死时
     * 阻塞中的 transferFrom 永远不会返回。看门狗持续监测最后活跃时间，
     * 超过阈值即关闭输入流，使阻塞中的读取立即抛出 IOException 触发重试。
     *
     * <p>阈值取响应超时配置（默认 30 秒，且不低于 20 秒）。原先 15 秒过于激进：
     * GitHub CDN 的数据流常常"停几秒再继续"，被看门狗当成假死掐断后又要重新
     * 握手+重连，正是"断断续续"的直接来源。真正假死的连接交给 120 秒停滞
     * 监控兜底，成本远低于反复误杀。
     */
    private final class StallWatchdog implements AutoCloseable {

        private final long stallTimeoutMs;

        private final InputStream stream;
        private final AtomicLong lastActivity = new AtomicLong(System.currentTimeMillis());
        private volatile boolean stopped = false;
        private final Thread thread;

        StallWatchdog(InputStream stream) {
            this.stream = stream;
            this.stallTimeoutMs = Math.max(20_000L, getResponseTimeoutSec() * 1000L);
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
                if (System.currentTimeMillis() - lastActivity.get() > stallTimeoutMs) {
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

    /**
     * 分片并发与限流自适应。
     *
     * <p>GitHub CDN 在并发过高时会返回 429/503，而且通常只拒绝"新请求"；
     * 此时正确做法是让全体分片统一冷却（优先遵守 Retry-After）、把并发降一档，
     * 然后再继续，而不是各自用短退避不停重试（那只会持续打满阈值）。
     * 持续有实际进展后，每隔一段时间恢复一档并发，避免长期停留在过低并发。
     */
    private static final class DownloadThrottle {

        private int limit;
        private final int maxLimit;
        private int active;
        private final AtomicLong cooldownUntilMs = new AtomicLong();
        private final AtomicInteger streak = new AtomicInteger();
        private long lastDecreaseMs = 0L;
        private long lastRecoverMs = 0L;

        DownloadThrottle(int limit) {
            this.limit = Math.max(1, limit);
            this.maxLimit = this.limit;
        }

        synchronized void acquire() throws InterruptedException {
            while (active >= limit) {
                wait();
            }
            active++;
        }

        synchronized void release() {
            if (active > 0) {
                active--;
            }
            notifyAll();
        }

        /** 等待全局冷却结束（可被取消/中断打断） */
        void awaitCooldown() throws InterruptedException {
            while (true) {
                long remaining = cooldownUntilMs.get() - System.currentTimeMillis();
                if (remaining <= 0) {
                    return;
                }
                Thread.sleep(Math.min(remaining, 1000L));
            }
        }

        /** 遇到 429/503：全体统一冷却 + 适度降低并发 */
        void throttled(long retryAfterMs) {
            int currentStreak = streak.incrementAndGet();
            long backoff = retryAfterMs > 0
                    ? retryAfterMs
                    : Math.min(15_000L, 1_000L * (1L << Math.min(currentStreak, 4)));
            backoff += ThreadLocalRandom.current().nextLong(0, 500);
            long target = System.currentTimeMillis() + backoff;
            long previous;
            do {
                previous = cooldownUntilMs.get();
            } while (target > previous && !cooldownUntilMs.compareAndSet(previous, target));

            synchronized (this) {
                long now = System.currentTimeMillis();
                // 每 2 秒最多降一档，避免多个分片同时 503 时把并发直接砸到 1
                if (now - lastDecreaseMs >= 2_000L && limit > 1) {
                    limit--;
                    lastDecreaseMs = now;
                }
            }
        }

        /** 持续有进展：重置限流计数并尝试恢复一档并发（8 秒最多恢复一次） */
        synchronized void recovered() {
            long now = System.currentTimeMillis();
            if (now - lastRecoverMs < 8_000L) {
                return;
            }
            lastRecoverMs = now;
            streak.set(0);
            if (limit < maxLimit) {
                limit++;
            }
        }
    }

    /** 服务器限流/暂时不可用（429/503/502/504），与普通网络错误区分处理 */
    private static final class ThrottledException extends IOException {

        final int code;
        final long retryAfterMs;

        ThrottledException(int code, long retryAfterMs) {
            super("HTTP " + code + "（服务器限流/暂时不可用）");
            this.code = code;
            this.retryAfterMs = retryAfterMs;
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
                .uri(URI.create(currentDownloadUrl()))
                .header("User-Agent", getUserAgent())
                .header("Accept", "*/*")
                .header("Accept-Encoding", "identity")
                .timeout(Duration.ofSeconds(getResponseTimeoutSec()));
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
        final long chunkSize;
        final int threadCount;
        final long[] done;

        ResumeMeta(long chunkSize, int threadCount, long[] done) {
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
            return new ResumeMeta(chunkSize, threadCount, done);
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
        boolean timedOut = false;
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SSLHandshakeException) {
                handshake = true;
            } else if (t instanceof ConnectException) {
                refused = true;
            } else if (t instanceof UnknownHostException) {
                unknownHost = true;
            } else if (t instanceof HttpTimeoutException) {
                // HttpConnectTimeoutException（连接阶段）与请求级 HttpTimeoutException
                //（等待响应头阶段）都归入超时提示
                timedOut = true;
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
        } else if (timedOut) {
            hint = "连接或响应超时；目标服务器可能被防火墙拦截或无响应，"
                    + "若使用 GitHub 加速工具请检查其是否正常运行";
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
