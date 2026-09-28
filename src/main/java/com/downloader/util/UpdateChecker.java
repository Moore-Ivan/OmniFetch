package com.downloader.util;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.cert.X509Certificate;
import java.time.Duration;

/**
 * 应用更新检查器。
 * 通过 GitHub Releases API 查询最新版本，与本地版本比较。
 */
public final class UpdateChecker {

    private static final String REPO_API =
            "https://api.github.com/repos/Moore-Ivan/OmniFetch/releases/latest";

    /** 信任所有证书的 HttpClient（仅用于更新检查，读取公开 API） */
    private static final HttpClient HTTP_CLIENT;

    static {
        HttpClient client;
        try {
            TrustManager[] trustAll = {
                new X509TrustManager() {
                    @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                    @Override public void checkClientTrusted(X509Certificate[] c, String t) { }
                    @Override public void checkServerTrusted(X509Certificate[] c, String t) { }
                }
            };
            SSLContext sslCtx = SSLContext.getInstance("TLS");
            sslCtx.init(null, trustAll, new java.security.SecureRandom());
            client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .sslContext(sslCtx)
                    .build();
        } catch (Exception e) {
            client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();
        }
        HTTP_CLIENT = client;
    }

    private UpdateChecker() {
    }

    /** 检查结果 */
    public static final class Result {
        public final boolean hasUpdate;
        public final String latestVersion;   // 不带 v 前缀，如 "1.0.1"
        public final String releaseUrl;      // GitHub Release 页面 URL
        public final String errorMsg;        // 出错时有值

        private Result(boolean hasUpdate, String latestVersion, String releaseUrl, String errorMsg) {
            this.hasUpdate = hasUpdate;
            this.latestVersion = latestVersion;
            this.releaseUrl = releaseUrl;
            this.errorMsg = errorMsg;
        }

        static Result noUpdate(String current) {
            return new Result(false, current, null, null);
        }

        static Result hasUpdate(String latest, String url) {
            return new Result(true, latest, url, null);
        }

        static Result error(String msg) {
            return new Result(false, null, null, msg);
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
            String tag = extractString(body, "tag_name");
            String htmlUrl = extractString(body, "html_url");

            if (tag == null || htmlUrl == null) {
                return Result.error("无法解析版本信息");
            }

            // tag 形如 "v1.0.0"，去掉前缀
            String latest = tag.startsWith("v") ? tag.substring(1) : tag;

            if (isNewerVersion(currentVersion, latest)) {
                return Result.hasUpdate(latest, htmlUrl);
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
     * 从 JSON 字符串中提取字符串值（简单实现，不依赖 JSON 库）。
     * 匹配 "key": "value" 格式。
     */
    private static String extractString(String json, String key) {
        String search = "\"" + key + "\"";
        int idx = json.indexOf(search);
        if (idx < 0) return null;
        idx = json.indexOf(":", idx);
        if (idx < 0) return null;
        idx = json.indexOf("\"", idx);
        if (idx < 0) return null;
        idx++; // 跳过开头的引号
        int end = json.indexOf("\"", idx);
        if (end < 0) return null;
        return json.substring(idx, end);
    }
}
