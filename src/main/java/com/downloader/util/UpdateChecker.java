package com.downloader.util;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

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

    /** HttpClient：信任所有证书（与下载器共用 HttpTrust），解决运行环境 cacerts 不完整问题 */
    private static final HttpClient HTTP_CLIENT;

    static {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL);
        if (HttpTrust.SSL_CONTEXT != null) {
            builder.sslContext(HttpTrust.SSL_CONTEXT);
        }
        HTTP_CLIENT = builder.build();
    }

    private UpdateChecker() {
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
     * GET latest.json 静态清单 → 解析版本号与当前平台下载地址 → 语义化版本比较。
     *
     * @param currentVersion 当前版本号，如 "1.0.0"
     */
    public static Result check(String currentVersion) {
        try {
            return fetchAndParse(currentVersion, LATEST_JSON_URL);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.error("检查更新被中断");
        } catch (IOException e) {
            String msg = e.getMessage();
            return Result.error(msg != null ? msg : e.getClass().getSimpleName());
        }
    }

    private static Result fetchAndParse(String currentVersion, String manifestUrl)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(manifestUrl))
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/json")
                .header("User-Agent", "OmniFetch-UpdateChecker")
                .GET()
                .build();

        HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            return Result.error("HTTP " + response.statusCode()
                    + "（无法获取 latest.json：" + manifestUrl + "）");
        }

        // 用内置最小 JSON 解析器解析（项目不引入第三方依赖）
        Object root;
        try {
            root = MiniJson.parse(response.body());
        } catch (IOException e) {
            return Result.error("latest.json 解析失败");
        }
        if (!(root instanceof Map<?, ?> rootMap)) {
            return Result.error("latest.json 格式不正确");
        }

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
