package com.downloader.util;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 应用更新检查器（Tauri latest.json 方案）。
 * 从固定静态地址拉取 latest.json（与 Tauri updater 相同的清单格式），
 * 与本地版本比较，并解析当前平台的安装包下载地址与发布说明：
 * <pre>
 * {
 *   "version": "1.2.0",
 *   "pub_date": "2026-11-02T00:00:00Z",
 *   "notes": "本次更新内容…",
 *   "platforms": {
 *     "windows-x86_64": {
 *       "signature": "",
 *       "url": "https://github.com/Moore-Ivan/OmniFetch/releases/download/v1.2.0/OmniFetch-1.2.0.exe"
 *     }
 *   }
 * }
 * </pre>
 * 清单是静态文件，随 Release Assets 发布（由 release.yml 在发版时自动生成），
 * 不依赖 GitHub API，无接口限流问题；同时兼容 Tauri v1 的平铺格式
 * （根级 url/signature，无 platforms 映射）。
 */
public final class UpdateChecker {

    /** latest.json 唯一地址：最新 Release Assets 内的清单（固定 URL，自动 302 到最新版本，无限流）。
     * 清单由 release.yml 在发版时按 tag 自动生成上传，无需手动维护 */
    static final String LATEST_JSON_URL =
            "https://github.com/Moore-Ivan/OmniFetch/releases/latest/download/latest.json";
    /** Releases 列表页（「查看发布页」入口） */
    public static final String RELEASES_PAGE_URL =
            "https://github.com/Moore-Ivan/OmniFetch/releases";
    /** 具体版本页前缀（"查看发布页"直接进入对应 tag，形如 /releases/tag/v1.0.1） */
    private static final String RELEASE_PAGE_PREFIX = RELEASES_PAGE_URL + "/tag/";

    /** 本应用当前平台的 Tauri 清单平台键 */
    private static final String PLATFORM_KEY = "windows-x86_64";

    /** 直连清单请求超时：GitHub 被墙时尽快失败，把时间留给加速线路 */
    private static final Duration DIRECT_TIMEOUT = Duration.ofSeconds(6);
    /** 单条加速线路请求超时 */
    private static final Duration MIRROR_TIMEOUT = Duration.ofSeconds(8);
    /** 多条加速线路并发探测的总时限 */
    private static final long MIRROR_BATCH_TIMEOUT_MS = 12_000L;

    /**
     * 上一次成功取到清单的来源（直连或某个镜像入口）。
     * 后续检查优先复用，命中时只需一次请求；失败则回落到完整探测流程。
     */
    private static volatile String preferredSource;

    private UpdateChecker() {
    }

    /**
     * 每次检查按当前代理配置重建客户端：
     * 与下载器一致地继承手动/环境变量/系统代理，解决"浏览器能开 GitHub、本程序不能"的情况。
     * 同时信任所有证书（与 HttpTrust 共用），解决运行环境 cacerts 不完整问题。
     */
    private static HttpClient buildHttpClient() {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
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

    /** 发布资产（可下载的安装包） */
    public static final class Asset {
        public final String name;
        public final String downloadUrl;
        public final long size;

        public Asset(String name, String downloadUrl, long size) {
            this.name = name;
            this.downloadUrl = downloadUrl;
            this.size = size;
        }
    }

    /** 检查结果 */
    public static final class Result {
        public final boolean hasUpdate;
        public final String latestVersion;   // 不带 v 前缀，如 "1.0.1"
        public final String errorMsg;        // 出错时有值
        public final List<Asset> assets;     // 安装包资产（latest.json 单地址，至多一项）
        public final String releaseNotes;    // 发布说明（Markdown 原文，可为空）

        private Result(boolean hasUpdate, String latestVersion, String errorMsg,
                       List<Asset> assets, String releaseNotes) {
            this.hasUpdate = hasUpdate;
            this.latestVersion = latestVersion;
            this.errorMsg = errorMsg;
            this.assets = assets != null ? assets : List.of();
            this.releaseNotes = releaseNotes;
        }

        static Result noUpdate(String current) {
            return new Result(false, current, null, null, null);
        }

        static Result hasUpdate(String latest, List<Asset> assets, String notes) {
            return new Result(true, latest, null, assets, notes);
        }

        static Result error(String msg) {
            return new Result(false, null, msg, null, null);
        }
    }

    /**
     * 具体版本的发布页 URL（形如 /releases/tag/v1.0.1）。
     *
     * @param version 版本号，带不带 v 前缀均可
     */
    public static String releasePageUrl(String version) {
        if (version == null || version.isBlank()) {
            return RELEASES_PAGE_URL;
        }
        String v = version.trim();
        return RELEASE_PAGE_PREFIX + (v.startsWith("v") ? v : "v" + v);
    }

    /**
     * 同步检查更新（调用方应在线程中执行）。
     * 依次尝试 GitHub 直连与各加速镜像获取 latest.json：任意一条可用即完成检查，
     * 因此本地无法访问 GitHub 时仍能正常发现更新。
     *
     * @param currentVersion 当前版本号，如 "1.0.0"
     */
    public static Result check(String currentVersion) {
        try {
            return checkFromSources(currentVersion);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.error("检查更新被中断");
        }
    }

    private static Result checkFromSources(String currentVersion) throws InterruptedException {
        List<String> mirrors = mirrorSources();
        StringBuilder failures = new StringBuilder();
        HttpClient client = buildHttpClient();
        Map<?, ?> manifest = null;
        String source = null;

        // 1) 上次成功的来源优先：多数情况下只需一次请求（上次走镜像时直连大概率仍不通）
        String preferred = preferredSource;
        if (preferred != null && mirrors.contains(preferred)) {
            try {
                manifest = fetchManifest(client, preferred, DIRECT_TIMEOUT);
                source = preferred;
            } catch (IOException e) {
                appendFailure(failures, preferred, e);
            }
        }

        // 2) GitHub 直连：网络可达时是最短路径
        if (manifest == null) {
            try {
                manifest = fetchManifest(client, LATEST_JSON_URL, DIRECT_TIMEOUT);
                source = LATEST_JSON_URL;
            } catch (IOException e) {
                appendFailure(failures, LATEST_JSON_URL, e);
            }
        }

        // 3) 直连失败：并发尝试全部镜像，取最先返回的合法清单
        if (manifest == null && !mirrors.isEmpty()) {
            FetchedManifest fetched = fetchFirstMirror(client, mirrors, failures);
            if (fetched != null) {
                manifest = fetched.json;
                source = fetched.source;
            }
        }

        if (manifest == null) {
            String detail = failures.length() == 0 ? "" : "（" + failures + "）";
            if (mirrors.isEmpty()) {
                return Result.error("无法获取更新清单" + detail
                        + "；可在设置中开启「GitHub 加速」后重试");
            }
            return Result.error("无法获取更新清单，GitHub 直连与加速线路均不可用" + detail);
        }

        preferredSource = source;
        AppLogger.info("更新清单来源: " + source);
        return buildResult(currentVersion, manifest);
    }

    /** 候选镜像入口：GitHub 加速关闭时为空（仅走直连） */
    private static List<String> mirrorSources() {
        if (!GitHubAccelerator.enabled()) {
            return List.of();
        }
        return new ArrayList<>(GitHubAccelerator.mirrorUrls(LATEST_JSON_URL));
    }

    /**
     * 并发请求全部镜像，返回最先拿到的合法清单。
     * 镜像互不相同，并发不会向同一主机施压；只要有一条线路可用即结束等待。
     */
    private static FetchedManifest fetchFirstMirror(HttpClient client, List<String> mirrors,
                                                    StringBuilder failures) throws InterruptedException {
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        List<String> mirrorErrors = Collections.synchronizedList(new ArrayList<>());
        try {
            List<Callable<FetchedManifest>> tasks = new ArrayList<>(mirrors.size());
            for (String url : mirrors) {
                tasks.add(() -> {
                    try {
                        return new FetchedManifest(url, fetchManifest(client, url, MIRROR_TIMEOUT));
                    } catch (IOException e) {
                        mirrorErrors.add(hostOf(url) + "=" + messageOf(e));
                        throw e;
                    }
                });
            }
            return executor.invokeAny(tasks, MIRROR_BATCH_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (ExecutionException | TimeoutException e) {
            synchronized (mirrorErrors) {
                for (String error : mirrorErrors) {
                    if (failures.length() > 0) {
                        failures.append('；');
                    }
                    failures.append(error);
                }
            }
            return null;
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * 拉取并校验清单：只有能解析出 version 的响应才算有效，
     * 避免个别加速线路用 HTML 错误页冒充文件，导致误判为"已是最新"。
     */
    private static Map<?, ?> fetchManifest(HttpClient client, String manifestUrl, Duration timeout)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(manifestUrl))
                .timeout(timeout)
                .header("Accept", "application/json")
                .header("User-Agent", "OmniFetch-UpdateChecker")
                .header("Cache-Control", "no-cache")
                .GET()
                .build();

        HttpResponse<String> response = client.send(
                request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode());
        }

        // 用内置最小 JSON 解析器解析（项目不引入第三方依赖）
        Object root;
        try {
            root = MiniJson.parse(response.body());
        } catch (IOException e) {
            throw new IOException("清单不是合法 JSON（可能被加速线路替换成了错误页）", e);
        }
        if (!(root instanceof Map<?, ?> map)) {
            throw new IOException("清单格式不正确");
        }
        if (!(map.get("version") instanceof String tag) || tag.isBlank()) {
            throw new IOException("清单缺少 version 字段");
        }
        return map;
    }

    private static void appendFailure(StringBuilder failures, String url, IOException e) {
        if (failures.length() > 0) {
            failures.append('；');
        }
        failures.append(url.equals(LATEST_JSON_URL) ? "直连" : hostOf(url))
                .append('=')
                .append(messageOf(e));
    }

    private static String messageOf(Exception e) {
        String msg = e.getMessage();
        return msg != null && !msg.isBlank() ? msg : e.getClass().getSimpleName();
    }

    private static String hostOf(String url) {
        try {
            String host = URI.create(url).getHost();
            return host != null ? host : url;
        } catch (IllegalArgumentException e) {
            return url;
        }
    }

    /** 清单内容 → 检查结果（版本比较与平台条目解析） */
    private static Result buildResult(String currentVersion, Map<?, ?> rootMap) {
        // 版本号：允许带 v 前缀（Tauri 清单两种写法都存在）
        Object versionValue = rootMap.get("version");
        if (!(versionValue instanceof String tag) || tag.isBlank()) {
            return Result.error("latest.json 缺少 version 字段");
        }
        String latest = tag.startsWith("v") ? tag.substring(1) : tag.trim();

        String notes = rootMap.get("notes") instanceof String b && !b.isBlank() ? b : null;

        // 平台条目：优先精确匹配 windows-x86_64，其次任意 windows-* 键；
        // 兼容 Tauri v1 平铺格式（根级 url，无 platforms）。
        // size 为非标准 Tauri 字段，由 release.yml 生成清单时写入，
        // 用于更新窗口在下载前即显示安装包总大小（缺失时为 -1，连接后由 Content-Length 得知）
        Map<?, ?> platformEntry = null;
        if (rootMap.get("platforms") instanceof Map<?, ?> platforms) {
            platformEntry = validPlatformEntry(platforms.get(PLATFORM_KEY));
            if (platformEntry == null) {
                for (Object key : platforms.keySet()) {
                    if (key instanceof String k && k.startsWith("windows-")) {
                        platformEntry = validPlatformEntry(platforms.get(k));
                        if (platformEntry != null) {
                            break;
                        }
                    }
                }
            }
        }
        String downloadUrl;
        long assetSize = -1;
        if (platformEntry != null) {
            downloadUrl = (String) platformEntry.get("url");
            if (platformEntry.get("size") instanceof Number num) {
                assetSize = num.longValue();
            }
        } else {
            downloadUrl = rootMap.get("url") instanceof String u && !u.isBlank() ? u : null;
        }

        if (!isNewerVersion(currentVersion, latest)) {
            return Result.noUpdate(latest);
        }

        // 清单只提供单个安装包地址，包装为单元素资产列表
        List<Asset> assets = downloadUrl != null
                ? List.of(new Asset(assetNameFromUrl(downloadUrl), downloadUrl, assetSize))
                : List.<Asset>of();
        return Result.hasUpdate(latest, assets, notes);
    }

    /** 命中的清单来源与其内容 */
    private static final class FetchedManifest {
        final String source;
        final Map<?, ?> json;

        FetchedManifest(String source, Map<?, ?> json) {
            this.source = source;
            this.json = json;
        }
    }

    /** 平台条目形如 {"signature": "...", "url": "...", "size": 123}，url 合法时原样返回 */
    private static Map<?, ?> validPlatformEntry(Object platformEntry) {
        if (platformEntry instanceof Map<?, ?> entry
                && entry.get("url") instanceof String url && !url.isBlank()) {
            return entry;
        }
        return null;
    }

    /** 从下载地址提取文件名（URL 解码），非法时回退默认安装包名 */
    private static String assetNameFromUrl(String url) {
        try {
            String path = URI.create(url).getPath();
            if (path != null && path.lastIndexOf('/') >= 0) {
                String name = path.substring(path.lastIndexOf('/') + 1);
                if (!name.isBlank()) {
                    return URLDecoder.decode(name, StandardCharsets.UTF_8);
                }
            }
        } catch (IllegalArgumentException ignore) {
            // 非法 URL：走默认名回退
        }
        return "OmniFetch-Setup.exe";
    }

    /**
     * 语义版本比较：v1.0.0 vs v1.0.1 → true（后者更新）。
     */
    static boolean isNewerVersion(String current, String latest) {
        int[] cur = parseVersion(current);
        int[] lat = parseVersion(latest);
        int len = Math.max(cur.length, lat.length);
        for (int i = 0; i < len; i++) {
            int c = i < cur.length ? cur[i] : 0;
            int l = i < lat.length ? lat[i] : 0;
            if (l > c) return true;
            if (l < c) return false;
        }
        return false;
    }

    private static int[] parseVersion(String v) {
        if (v == null || v.isEmpty()) return new int[]{0};
        String s = v.startsWith("v") ? v.substring(1) : v;
        String[] parts = s.split("\\.");
        int[] result = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                result[i] = Integer.parseInt(parts[i].trim());
            } catch (NumberFormatException e) {
                result[i] = 0;
            }
        }
        return result;
    }
}
