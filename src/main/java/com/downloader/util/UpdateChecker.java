package com.downloader.util;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 应用更新检查器。
 * 通过 GitHub Releases API 查询最新版本，与本地版本比较，
 * 并解析发布资产（安装包下载地址、大小）与发布说明。
 */
public final class UpdateChecker {

    private static final String REPO_API =
            "https://api.github.com/repos/Moore-Ivan/OmniFetch/releases/latest";
    /** Releases 列表页（"查看发布页"统一打开此页，不进入具体 tag） */
    public static final String RELEASES_PAGE_URL =
            "https://github.com/Moore-Ivan/OmniFetch/releases";
    /** 网页端 latest：302 跳转到最新 tag 页面，不受 GitHub API 每 IP 限流（403/429）影响 */
    private static final String REPO_LATEST_PAGE = RELEASES_PAGE_URL + "/latest";

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
        public final List<Asset> assets;     // 发布资产列表
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
     * 同步检查更新（调用方应在线程中执行）。
     * 先走 GitHub API（信息最全）；遇到 403/429（IP 限流、代理拦截，国内网络常见）
     * 或网络层失败时，自动降级到网页端 releases/latest（不受 API 限流）。
     *
     * @param currentVersion 当前版本号，如 "1.0.0"
     */
    public static Result check(String currentVersion) {
        Result apiResult;
        boolean apiBlocked;
        try {
            apiResult = checkViaApi(currentVersion);
            apiBlocked = apiResult.errorMsg != null && isRateLimitOrNetworkError(apiResult.errorMsg);
        } catch (IOException | InterruptedException e) {
            apiResult = Result.error(e.getMessage());
            apiBlocked = true;
        }

        if (!apiBlocked) {
            return apiResult;
        }

        // 降级：网页端重定向取最新版本，HTML 中解析资产链接
        try {
            Result fallback = checkViaLatestPage(currentVersion);
            if (fallback != null) {
                return fallback;
            }
        } catch (IOException | InterruptedException e) {
            // 两条链路都失败，返回 API 的原始错误（通常是 HTTP 403）
        }
        return apiResult;
    }

    private static Result checkViaApi(String currentVersion) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(REPO_API))
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "OmniFetch-UpdateChecker")
                .GET()
                .build();

        HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            return Result.error("HTTP " + response.statusCode());
        }

        String body = response.body();

        // 简单解析 JSON（不引入第三方依赖）
        String tag = extractText(body, "tag_name");
        String notes = extractText(body, "body");

        if (tag == null) {
            return Result.error("无法解析版本信息");
        }

        // tag 形如 "v1.0.0"，去掉前缀
        String latest = tag.startsWith("v") ? tag.substring(1) : tag;

        if (isNewerVersion(currentVersion, latest)) {
            return Result.hasUpdate(latest, extractAssets(body), notes);
        } else {
            return Result.noUpdate(latest);
        }
    }

    /**
     * 网页端降级：GET releases/latest 会 302 到 /releases/tag/vX.Y.Z，
     * 跟随重定向后的最终地址包含版本号，响应体是版本页 HTML，可解析出资产下载链接。
     *
     * @return 检查结果；无法确定版本（如页面不存在）时返回 null
     */
    static Result checkViaLatestPage(String currentVersion)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(REPO_LATEST_PAGE))
                .timeout(Duration.ofSeconds(20))
                .header("Accept", "text/html")
                .header("User-Agent", "OmniFetch-UpdateChecker")
                .GET()
                .build();

        HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            return null;
        }

        String path = response.uri().getPath();
        int tagIdx = path == null ? -1 : path.lastIndexOf("/tag/");
        if (tagIdx < 0) {
            return null;
        }
        String tag = path.substring(tagIdx + "/tag/".length());
        if (tag.isEmpty()) {
            return null;
        }
        String latest = tag.startsWith("v") ? tag.substring(1) : tag;
        if (!isNewerVersion(currentVersion, latest)) {
            return Result.noUpdate(latest);
        }

        // 资产列表在版本页中通过 expanded_assets 片段异步加载，需再取一次该片段；
        // 片段缺失或抓取失败时退化为直接解析版本页 HTML
        List<Asset> assets = List.of();
        String expandedPath = extractExpandedAssetsPath(response.body());
        if (expandedPath == null) {
            expandedPath = "/Moore-Ivan/OmniFetch/releases/expanded_assets/" + tag;
        }
        try {
            assets = extractAssetsFromHtml(fetchPage("https://github.com" + expandedPath));
        } catch (IOException | InterruptedException ignore) {
            // 片段不可达时直接解析主页面（部分老版本/镜像会内联资产链接）
        }
        if (assets.isEmpty()) {
            assets = extractAssetsFromHtml(response.body());
        }
        // 降级链路无发布说明与资产大小（下载开始后由 Content-Length 得知总大小）
        return Result.hasUpdate(latest, assets, null);
    }

    private static String fetchPage(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("Accept", "text/html")
                .header("User-Agent", "OmniFetch-UpdateChecker")
                .GET()
                .build();
        HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        return response.statusCode() == 200 ? response.body() : "";
    }

    /** 从版本页 HTML 中找 expanded_assets 片段地址，找不到返回 null。 */
    static String extractExpandedAssetsPath(String html) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("src=\"(/Moore-Ivan/OmniFetch/releases/expanded_assets/[^\"]+)\"")
                .matcher(html);
        return m.find() ? m.group(1) : null;
    }

    /** 403/429（限流、代理拦截）或网络异常信息值得走网页端降级；其余（如 404/解析失败）不走。 */
    private static boolean isRateLimitOrNetworkError(String msg) {
        if (msg == null) {
            return false;
        }
        return msg.startsWith("HTTP 403") || msg.startsWith("HTTP 429")
                || msg.startsWith("HTTP 5"); // 网关层临时故障同样值得重试降级
    }

    /**
     * 从 release 版本页 HTML 中提取资产链接：
     * 形如 href="/Moore-Ivan/OmniFetch/releases/download/v1.0.0/OmniFetch-1.0.0.exe"。
     * 优先返回 .exe；大小无法从页面获知，记为 -1（下载连接建立后取 Content-Length）。
     */
    static List<Asset> extractAssetsFromHtml(String html) {
        List<Asset> assets = new ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("href=\"(/Moore-Ivan/OmniFetch/releases/download/[^\"]+)\"")
                .matcher(html);
        java.util.Set<String> seen = new java.util.HashSet<>();
        while (m.find()) {
            String path;
            try {
                path = java.net.URLDecoder.decode(m.group(1), java.nio.charset.StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                path = m.group(1);
            }
            if (!seen.add(path)) {
                continue;
            }
            String name = path.substring(path.lastIndexOf('/') + 1);
            assets.add(new Asset(name, "https://github.com" + path, -1));
        }
        // .exe 排前（选择器按顺序取第一个 .exe）
        assets.sort((a, b) -> Boolean.compare(!a.name.toLowerCase(Locale.ROOT).endsWith(".exe"),
                !b.name.toLowerCase(Locale.ROOT).endsWith(".exe")));
        return assets;
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

    /**
     * 解析 assets 数组中的每个资产对象（name / browser_download_url / size）。
     */
    private static List<Asset> extractAssets(String json) {
        List<Asset> assets = new ArrayList<>();
        int keyIdx = json.indexOf("\"assets\"");
        if (keyIdx < 0) {
            return assets;
        }
        int arrStart = json.indexOf('[', keyIdx);
        if (arrStart < 0) {
            return assets;
        }
        // 匹配数组闭合括号
        int depth = 0;
        int arrEnd = -1;
        for (int i = arrStart; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '[') {
                depth++;
            } else if (c == ']') {
                depth--;
                if (depth == 0) {
                    arrEnd = i;
                    break;
                }
            }
        }
        if (arrEnd < 0) {
            return assets;
        }
        String arr = json.substring(arrStart + 1, arrEnd);

        // 逐对象截取（资产字段值不含大括号，简单括号配对即可）
        int objStart = -1;
        int objDepth = 0;
        for (int i = 0; i < arr.length(); i++) {
            char c = arr.charAt(i);
            if (c == '{') {
                if (objDepth == 0) {
                    objStart = i;
                }
                objDepth++;
            } else if (c == '}') {
                objDepth--;
                if (objDepth == 0 && objStart >= 0) {
                    Asset asset = parseAsset(arr.substring(objStart, i + 1));
                    if (asset != null) {
                        assets.add(asset);
                    }
                    objStart = -1;
                }
            }
        }
        return assets;
    }

    private static Asset parseAsset(String objJson) {
        String name = extractText(objJson, "name");
        String url = extractText(objJson, "browser_download_url");
        long size = extractLong(objJson, "size");
        if (name == null || url == null) {
            return null;
        }
        return new Asset(name, url, size);
    }

    /**
     * 从 JSON 中提取字符串值，正确处理反斜杠转义（\"、\n 等）。
     */
    private static String extractText(String json, String key) {
        String search = "\"" + key + "\"";
        int idx = json.indexOf(search);
        if (idx < 0) return null;
        idx = json.indexOf(':', idx);
        if (idx < 0) return null;
        idx = json.indexOf('"', idx);
        if (idx < 0) return null;

        StringBuilder sb = new StringBuilder();
        for (int j = idx + 1; j < json.length(); j++) {
            char c = json.charAt(j);
            if (c == '\\' && j + 1 < json.length()) {
                char next = json.charAt(j + 1);
                switch (next) {
                    case 'n'  -> sb.append('\n');
                    case 'r'  -> sb.append('\r');
                    case 't'  -> sb.append('\t');
                    case '"'  -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/'  -> sb.append('/');
                    default   -> sb.append(next);
                }
                j++;
            } else if (c == '"') {
                return sb.toString();
            } else {
                sb.append(c);
            }
        }
        return null;
    }

    /**
     * 从 JSON 中提取整数值。
     */
    private static long extractLong(String json, String key) {
        String search = "\"" + key + "\"";
        int idx = json.indexOf(search);
        if (idx < 0) return -1;
        idx = json.indexOf(':', idx);
        if (idx < 0) return -1;
        int j = idx + 1;
        while (j < json.length() && !Character.isDigit(json.charAt(j))) {
            j++;
        }
        int start = j;
        while (j < json.length() && Character.isDigit(json.charAt(j))) {
            j++;
        }
        if (start == j) {
            return -1;
        }
        try {
            return Long.parseLong(json.substring(start, j));
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
