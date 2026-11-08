package com.downloader.util;

import com.downloader.config.ConfigManager;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * GitHub 下载加速（应用内实现，无需用户安装 Clash 等第三方工具）。
 *
 * 思路：先只探测直连原始地址；直连可用且速度达标就直接使用，不再打扰镜像。
 * 只有直连不可用或明显偏慢时，才串行探测内置加速镜像并挑选更快的一条。
 * 单条线路做一次 32KB 的 Range 探测，按"是否支持 Range + 实测速度"排序。
 *
 * 关键约束：GitHub 的 CDN 对同一 IP 的并发非常敏感，短时间内并发探测多条线路
 * 会直接触发 429/503 限流，反而让下载"断断续续"。因此探测必须串行、有总时限，
 * 并缓存选中的线路，避免每次重试/续传都重新发起一轮探测。
 *
 * 线路地址仅为"入口"：部分镜像是真正的反向代理（自己回源并回传字节），
 * 部分会 302 到签名 CDN。探测会跟随跳转，因此"能真正取到字节"的线路才会胜出。
 */
public final class GitHubAccelerator {

    private static final ConfigManager CONFIG = ConfigManager.getInstance();

    /** 单条线路探测读取的字节数（足以区分正常文件与少量字节的错误页） */
    private static final int PROBE_BYTES = 32 * 1024;
    /** 有效探测至少应收到的字节数 */
    private static final int MIN_PROBE_BYTES = 4 * 1024;
    /** 直连速度达到该值即认为足够快，不再探测镜像（探测本身也是并发请求） */
    private static final long DIRECT_GOOD_SPEED_BPS = 1536 * 1024L;
    /** 线路选择结果缓存时长：同一文件的重试/续传直接复用，避免探测风暴 */
    private static final long ROUTE_CACHE_TTL_MS = 5 * 60_000L;

    private static final Map<String, CachedRoute> ROUTE_CACHE = new ConcurrentHashMap<>();

    /**
     * 内置镜像：均为"前缀 + 原始 GitHub 地址"形式的公共加速服务。
     * 顺序不影响选择结果（按速度取胜），仅作为同等条件下的倾向。
     */
    private static final String[] BUILTIN_MIRRORS = {
            "https://gh-proxy.com/",
            "https://ghproxy.net/",
            "https://ghproxy.cc/",
            "https://ghfast.top/",
            "https://github.moeyy.xyz/"
    };

    private GitHubAccelerator() {
    }

    /** 一条候选线路 */
    public static final class Route {
        /** 线路名称（"直连"或镜像主机名），用于日志 */
        public final String name;
        /** 线路实际请求地址（镜像线路已拼接原始地址） */
        public final String url;
        /** true=镜像线路，false=直连原始地址 */
        public final boolean mirror;
        /** 探测是否确认支持 Range（决定能否多线程分片） */
        public final boolean rangeCapable;
        /** 探测实测速度（字节/秒） */
        public final long speed;

        private Route(String name, String url, boolean mirror, boolean rangeCapable, long speed) {
            this.name = name;
            this.url = url;
            this.mirror = mirror;
            this.rangeCapable = rangeCapable;
            this.speed = speed;
        }

        @Override
        public String toString() {
            return name + "（" + FileUtils.formatSize(speed) + "/s"
                    + (rangeCapable ? "" : "，不支持分片") + "）";
        }
    }

    /** 是否启用 GitHub 内置加速 */
    public static boolean enabled() {
        return CONFIG.getBoolean("network.githubAccelerator", true);
    }

    /** 是否为 GitHub 相关域名（只有这些链接才值得做线路选择） */
    public static boolean isGitHubUrl(String url) {
        if (url == null) {
            return false;
        }
        String host;
        try {
            host = URI.create(url).getHost();
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (host == null) {
            return false;
        }
        host = host.toLowerCase();
        return host.equals("github.com")
                || host.equals("www.github.com")
                || host.equals("codeload.github.com")
                || host.equals("gist.github.com")
                || host.endsWith(".githubusercontent.com");
    }

    /**
     * 探测并返回最优线路；所有线路都不可用时返回 null。
     *
     * <p>执行顺序刻意做成"先直连、达标即止"，而不是一次性并发探测全部线路：
     * GitHub CDN 对并发请求极为敏感，探测风暴会让后续真正的下载请求被 429/503
     * 拒绝，表现为"刚开始下载就断断续续"。
     *
     * @param originalUrl 原始下载地址（GitHub release 链接等）
     * @param userAgent   请求 UA（与下载保持一致，避免个别站点按 UA 拒绝）
     * @param timeoutSec  全部探测的总时限（秒）
     * @param avoidUrl    尽量避开的线路地址（线路故障后切换时传入当前线路）；实在只有它可用时仍会返回
     */
    public static Route select(String originalUrl, String userAgent, int timeoutSec, String avoidUrl) {
        if (!enabled() || originalUrl == null) {
            return null;
        }
        // 同一文件的重试/续传直接复用上次的结果：反复探测本身就是限流诱因
        if (avoidUrl == null) {
            Route cached = cachedRoute(originalUrl);
            if (cached != null) {
                return cached;
            }
        }
        List<Candidate> candidates = buildCandidates(originalUrl);
        if (candidates.isEmpty()) {
            return null;
        }

        HttpClient client = buildClient();
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        long deadline = System.currentTimeMillis() + Math.max(1, timeoutSec) * 1000L;
        StringBuilder summary = new StringBuilder();
        List<ProbeResult> available = new ArrayList<>();
        try {
            // 1) 先只探测直连，且给它一半左右的预算：直连被墙时会一直挂到超时，
            //    必须给镜像留出探测时间
            Candidate direct = candidates.get(0);
            int directBudgetSec = (int) Math.min(5, Math.max(2, timeoutSec / 2));
            ProbeResult directResult = probeOne(client, executor, direct, userAgent, directBudgetSec);
            appendSummary(summary, direct, directResult);
            if (directResult.ok) {
                available.add(directResult);
                if (directResult.speed >= DIRECT_GOOD_SPEED_BPS) {
                    AppLogger.info("GitHub 线路探测: " + summary + "（直连速度达标，使用直连）");
                    return remember(originalUrl, directResult);
                }
            }

            // 2) 直连不可用或偏慢，才逐条探测镜像（串行 + 总时限，避免并发风暴）
            for (int i = 1; i < candidates.size(); i++) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining < 1_000L) {
                    break;
                }
                Candidate candidate = candidates.get(i);
                int perProbeSec = (int) Math.min(6, Math.max(1, remaining / 1000));
                ProbeResult result = probeOne(client, executor, candidate, userAgent, perProbeSec);
                appendSummary(summary, candidate, result);
                if (result.ok) {
                    available.add(result);
                }
            }

            AppLogger.info("GitHub 线路探测: " + summary);
            ProbeResult best = pickBest(available, avoidUrl);
            if (best == null) {
                return null;
            }
            return remember(originalUrl, best);
        } finally {
            executor.shutdownNow();
            client.close();
        }
    }

    /** 探测单条线路（可被超时中断：关闭响应体解除阻塞中的 read） */
    private static ProbeResult probeOne(HttpClient client, ExecutorService executor,
                                        Candidate candidate, String userAgent, int timeoutSec) {
        ProbeTask task = new ProbeTask(client, candidate, userAgent, timeoutSec);
        Future<ProbeResult> future = executor.submit(task);
        try {
            return future.get(Math.max(1, timeoutSec) * 1000L + 500L, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            task.abort();
            return ProbeResult.failed(candidate, "探测超时");
        } catch (Exception e) {
            task.abort();
            return ProbeResult.failed(candidate,
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    private static void appendSummary(StringBuilder summary, Candidate candidate, ProbeResult result) {
        if (summary.length() > 0) {
            summary.append("；");
        }
        summary.append(candidate.name).append('=').append(result);
    }

    /** 记录选中线路并写入缓存 */
    private static Route remember(String originalUrl, ProbeResult result) {
        Route route = new Route(result.candidate.name, result.candidate.url,
                result.candidate.mirror, result.rangeCapable, result.speed);
        if (originalUrl != null) {
            // 缓存只服务于当前会话内的重试/续传，条目极少；异常增长时直接清空兜底
            if (ROUTE_CACHE.size() > 200) {
                ROUTE_CACHE.clear();
            }
            ROUTE_CACHE.put(originalUrl, new CachedRoute(route, System.currentTimeMillis()));
        }
        return route;
    }

    private static Route cachedRoute(String originalUrl) {
        CachedRoute cached = ROUTE_CACHE.get(originalUrl);
        if (cached == null) {
            return null;
        }
        if (System.currentTimeMillis() - cached.createdMs <= ROUTE_CACHE_TTL_MS) {
            return cached.route;
        }
        ROUTE_CACHE.remove(originalUrl);
        return null;
    }

    /** 丢弃某地址的线路缓存：下载中途线路失效时，下一次选择必须重新探测 */
    public static void invalidate(String originalUrl) {
        if (originalUrl != null) {
            ROUTE_CACHE.remove(originalUrl);
        }
    }

    /** 线路缓存项 */
    private static final class CachedRoute {
        final Route route;
        final long createdMs;

        CachedRoute(Route route, long createdMs) {
            this.route = route;
            this.createdMs = createdMs;
        }
    }

    /** 镜像地址列表：配置优先，留空用内置 */
    public static List<String> mirrorBases() {
        String configured = CONFIG.getString("network.githubMirrors", "").trim();
        List<String> bases = new ArrayList<>();
        if (configured.isEmpty()) {
            for (String builtin : BUILTIN_MIRRORS) {
                bases.add(builtin);
            }
        } else {
            for (String part : configured.split("[,;\\s]+")) {
                if (!part.isBlank()) {
                    bases.add(part.trim());
                }
            }
        }
        return bases;
    }

    /**
     * 该 GitHub 地址在全部镜像下的入口地址（不含直连）。
     * 更新检查用它把固定的 latest.json 地址改写到可访问的加速入口：
     * GitHub 不可达时，清单请求也能走镜像。
     */
    public static List<String> mirrorUrls(String originalUrl) {
        List<String> urls = new ArrayList<>();
        if (originalUrl == null) {
            return urls;
        }
        for (String base : mirrorBases()) {
            String normalized = base.trim();
            if (normalized.isEmpty()) {
                continue;
            }
            String url;
            if (normalized.contains("{url}")) {
                url = normalized.replace("{url}", originalUrl);
            } else {
                if (!normalized.endsWith("/")) {
                    normalized = normalized + "/";
                }
                url = normalized + originalUrl;
            }
            // 同一镜像重复配置或与已有入口相同时跳过
            if (!urls.contains(url)) {
                urls.add(url);
            }
        }
        return urls;
    }

    private static List<Candidate> buildCandidates(String originalUrl) {
        List<Candidate> candidates = new ArrayList<>();
        candidates.add(new Candidate("直连", originalUrl, false));
        for (String url : mirrorUrls(originalUrl)) {
            candidates.add(new Candidate(hostOf(url), url, true));
        }
        return candidates;
    }

    private static String hostOf(String url) {
        try {
            String host = URI.create(url).getHost();
            return host == null ? url : host;
        } catch (IllegalArgumentException e) {
            return url;
        }
    }

    /** 择优：优先支持 Range 的线路，其次速度更快 */
    private static ProbeResult pickBest(List<ProbeResult> available, String avoidUrl) {
        ProbeResult best = null;
        for (ProbeResult result : available) {
            if (avoidUrl != null && result.candidate.url.equals(avoidUrl)) {
                continue;
            }
            if (best == null || result.compareTo(best) > 0) {
                best = result;
            }
        }
        if (best == null) {
            // 其余线路都不可用时，当前线路仍是唯一可用的
            for (ProbeResult result : available) {
                if (best == null || result.compareTo(best) > 0) {
                    best = result;
                }
            }
        }
        return best;
    }

    /** 探测用客户端：跟随跳转 + 信任证书（hosts 指向本机加速工具时仍需可用）+ 继承代理 */
    private static HttpClient buildClient() {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .connectTimeout(Duration.ofSeconds(8))
                .followRedirects(HttpClient.Redirect.NORMAL);
        if (HttpTrust.SSL_CONTEXT != null) {
            builder.sslContext(HttpTrust.SSL_CONTEXT);
        }
        NetworkProxy.Config proxy = NetworkProxy.initial();
        if (proxy != null) {
            builder.proxy(proxy.selector);
            if (proxy.authenticator != null) {
                builder.authenticator(proxy.authenticator);
            }
        }
        return builder.build();
    }

    private static final class Candidate {
        final String name;
        final String url;
        final boolean mirror;

        Candidate(String name, String url, boolean mirror) {
            this.name = name;
            this.url = url;
            this.mirror = mirror;
        }
    }

    private static final class ProbeResult {
        final Candidate candidate;
        final boolean ok;
        final boolean rangeCapable;
        final long speed;
        final String detail;

        private ProbeResult(Candidate candidate, boolean ok, boolean rangeCapable,
                            long speed, String detail) {
            this.candidate = candidate;
            this.ok = ok;
            this.rangeCapable = rangeCapable;
            this.speed = speed;
            this.detail = detail;
        }

        static ProbeResult failed(Candidate candidate, String detail) {
            return new ProbeResult(candidate, false, false, 0, detail);
        }

        int compareTo(ProbeResult other) {
            if (rangeCapable != other.rangeCapable) {
                return rangeCapable ? 1 : -1;
            }
            return Long.compare(speed, other.speed);
        }

        @Override
        public String toString() {
            if (!ok) {
                return "失败(" + detail + ")";
            }
            return FileUtils.formatSize(speed) + "/s" + (rangeCapable ? "" : "(无Range)");
        }
    }

    /**
     * 单线路探测：请求前 32KB，按实际收到字节数/耗时计算速度。
     * 读取可能被服务器"只发少量数据然后挂起"拖住，外层超时后会调用 abort() 关闭响应体解除阻塞。
     */
    private static final class ProbeTask implements Callable<ProbeResult> {
        private final HttpClient client;
        private final Candidate candidate;
        private final String userAgent;
        private final int timeoutSec;
        private volatile InputStream body;

        ProbeTask(HttpClient client, Candidate candidate, String userAgent, int timeoutSec) {
            this.client = client;
            this.candidate = candidate;
            this.userAgent = userAgent;
            this.timeoutSec = timeoutSec;
        }

        /** 超时/取消时关闭响应体，解除阻塞中的 read */
        void abort() {
            InputStream in = body;
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                }
            }
        }

        @Override
        public ProbeResult call() {
            long startNanos = System.nanoTime();
            InputStream in = null;
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(candidate.url))
                        .timeout(Duration.ofSeconds(timeoutSec))
                        .header("User-Agent", userAgent)
                        .header("Accept", "*/*")
                        .header("Accept-Encoding", "identity")
                        .header("Range", "bytes=0-" + (PROBE_BYTES - 1))
                        .GET()
                        .build();
                HttpResponse<InputStream> response = client.send(
                        request, HttpResponse.BodyHandlers.ofInputStream());
                in = response.body();
                body = in;
                int code = response.statusCode();
                if (code < 200 || code >= 300) {
                    return ProbeResult.failed(candidate, "HTTP " + code);
                }
                String contentType = response.headers().firstValue("Content-Type").orElse("");
                if (contentType.toLowerCase().contains("text/html")) {
                    return ProbeResult.failed(candidate, "返回网页而非文件");
                }
                boolean rangeCapable = code == 206;
                long bytes = 0;
                boolean eof = false;
                byte[] buffer = new byte[8192];
                int read;
                while (bytes < PROBE_BYTES) {
                    read = in.read(buffer);
                    if (read < 0) {
                        eof = true;
                        break;
                    }
                    bytes += read;
                }
                // 小文件会在读满 32KB 前正常结束（eof），不能因此判为失败；
                // 只有"既没读完又收得很少"（错误页/半截响应）才算无效线路
                if (bytes <= 0 || (!eof && bytes < MIN_PROBE_BYTES)) {
                    return ProbeResult.failed(candidate, "仅收到 " + bytes + " 字节");
                }
                long elapsedMs = Math.max(1L, (System.nanoTime() - startNanos) / 1_000_000L);
                long speed = bytes * 1000L / elapsedMs;
                return new ProbeResult(candidate, true, rangeCapable, speed, null);
            } catch (Exception e) {
                return ProbeResult.failed(candidate, e.getMessage() == null
                        ? e.getClass().getSimpleName() : e.getMessage());
            } finally {
                if (in != null) {
                    try {
                        in.close();
                    } catch (IOException ignored) {
                    }
                }
            }
        }
    }
}
