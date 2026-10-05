package com.downloader.util;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 应用更新检查器。
 * 通过 GitHub Releases API 查询最新版本，与本地版本比较，
 * 并解析发布资产（安装包下载地址、大小）与发布说明。
 */
public final class UpdateChecker {

    private static final String REPO_API =
            "https://api.github.com/repos/Moore-Ivan/OmniFetch/releases/latest";

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
        public final String releaseUrl;      // GitHub Release 页面 URL
        public final String errorMsg;        // 出错时有值
        public final List<Asset> assets;     // 发布资产列表
        public final String releaseNotes;    // 发布说明（Markdown 原文，可为空）

        private Result(boolean hasUpdate, String latestVersion, String releaseUrl, String errorMsg,
                       List<Asset> assets, String releaseNotes) {
            this.hasUpdate = hasUpdate;
            this.latestVersion = latestVersion;
            this.releaseUrl = releaseUrl;
            this.errorMsg = errorMsg;
            this.assets = assets != null ? assets : List.of();
            this.releaseNotes = releaseNotes;
        }

        static Result noUpdate(String current) {
            return new Result(false, current, null, null, null, null);
        }

        static Result hasUpdate(String latest, String url, List<Asset> assets, String notes) {
            return new Result(true, latest, url, null, assets, notes);
        }

        static Result error(String msg) {
            return new Result(false, null, null, msg, null, null);
        }
    }

    /**
     * 同步检查更新（调用方应在线程中执行）。
     *
     * @param currentVersion 当前版本号，如 "1.0.0"
     */
    public static Result check(String currentVersion) {
        try {
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
            String htmlUrl = extractText(body, "html_url");
            String notes = extractText(body, "body");

            if (tag == null || htmlUrl == null) {
                return Result.error("无法解析版本信息");
            }

            // tag 形如 "v1.0.0"，去掉前缀
            String latest = tag.startsWith("v") ? tag.substring(1) : tag;

            if (isNewerVersion(currentVersion, latest)) {
                return Result.hasUpdate(latest, htmlUrl, extractAssets(body), notes);
            } else {
                return Result.noUpdate(latest);
            }
        } catch (IOException | InterruptedException e) {
            return Result.error(e.getMessage());
        }
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
