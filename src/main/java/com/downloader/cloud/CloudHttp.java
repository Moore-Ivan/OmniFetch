package com.downloader.cloud;

import com.downloader.util.HttpTrust;
import com.downloader.util.MiniJson;

import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 网盘接口专用 HTTP 客户端。
 * <p>与下载引擎的 HttpClient 分离：这里需要 Cookie 会话、可关闭的自动重定向，
 * 以及手动读取 302 Location 的能力（部分网盘直链要经过一次跳转才落地）。
 */
public final class CloudHttp implements AutoCloseable {

    private final CookieManager cookieManager;
    private final HttpClient followClient;
    private final HttpClient plainClient;
    private final int responseTimeoutSec;

    CloudHttp(int connectTimeoutSec, int responseTimeoutSec) {
        this.responseTimeoutSec = responseTimeoutSec;
        this.cookieManager = new CookieManager(null, CookiePolicy.ACCEPT_ALL);

        HttpClient.Builder base = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .cookieHandler(cookieManager)
                .connectTimeout(Duration.ofSeconds(connectTimeoutSec));
        if (HttpTrust.SSL_CONTEXT != null) {
            base.sslContext(HttpTrust.SSL_CONTEXT);
        }
        this.followClient = base.followRedirects(HttpClient.Redirect.NORMAL).build();
        this.plainClient = base.followRedirects(HttpClient.Redirect.NEVER).build();
    }

    /**
     * 把用户粘贴的 Cookie 文本（“k=v; k2=v2”）注入会话。
     * 域名使用平台主域，子域名按 RFC 规则自动匹配。
     */
    public void seedCookies(String domain, String cookieText) {
        if (cookieText == null || cookieText.isBlank()) {
            return;
        }
        String host = domain == null ? "" : domain.trim();
        if (host.startsWith(".")) {
            host = host.substring(1);
        }
        if (host.isEmpty()) {
            return;
        }
        for (String part : cookieText.split(";")) {
            String pair = part.trim();
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String name = pair.substring(0, eq).trim();
            String value = pair.substring(eq + 1).trim();
            try {
                HttpCookie cookie = new HttpCookie(name, value);
                cookie.setDomain(host);
                cookie.setPath("/");
                cookieManager.getCookieStore()
                        .add(URI.create("https://" + host + "/"), cookie);
            } catch (IllegalArgumentException ignored) {
                // 个别浏览器扩展字段不是合法 Cookie 名/值，跳过即可
            }
        }
    }

    public HttpResponse<String> send(String method, String url,
                                     Map<String, String> headers,
                                     String body, boolean followRedirect)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(responseTimeoutSec));
        applyHeaders(builder, headers);
        if (body == null || body.isEmpty()) {
            builder.method(method.toUpperCase(), HttpRequest.BodyPublishers.noBody());
        } else {
            builder.method(method.toUpperCase(),
                    HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        }
        return (followRedirect ? followClient : plainClient)
                .send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    public HttpResponse<byte[]> sendBytes(String method, String url,
                                          Map<String, String> headers,
                                          String body, boolean followRedirect)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(responseTimeoutSec));
        applyHeaders(builder, headers);
        if (body == null || body.isEmpty()) {
            builder.method(method.toUpperCase(), HttpRequest.BodyPublishers.noBody());
        } else {
            builder.method(method.toUpperCase(),
                    HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        }
        return (followRedirect ? followClient : plainClient)
                .send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    public String getText(String url, Map<String, String> headers)
            throws IOException, InterruptedException {
        return send("GET", url, headers, null, true).body();
    }

    public byte[] getBytes(String url, Map<String, String> headers)
            throws IOException, InterruptedException {
        return sendBytes("GET", url, headers, null, true).body();
    }

    public String postForm(String url, Map<String, String> form,
                           Map<String, String> headers)
            throws IOException, InterruptedException {
        Map<String, String> h = new LinkedHashMap<>();
        if (headers != null) {
            h.putAll(headers);
        }
        h.putIfAbsent("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
        return send("POST", url, h, encodeForm(form), true).body();
    }

    public String postJson(String url, String json, Map<String, String> headers)
            throws IOException, InterruptedException {
        Map<String, String> h = new LinkedHashMap<>();
        if (headers != null) {
            h.putAll(headers);
        }
        h.putIfAbsent("Content-Type", "application/json; charset=UTF-8");
        return send("POST", url, h, json, true).body();
    }

    /**
     * 手动跟随 302：部分网盘返回的“直链”实际是校验入口，需要带着
     * Referer/UA 再跳一次才得到 CDN 地址。返回最终地址。
     */
    public String followLocation(String url, Map<String, String> headers, int maxHops)
            throws IOException, InterruptedException {
        String current = url;
        for (int i = 0; i < maxHops; i++) {
            HttpResponse<String> resp = send("GET", current, headers, null, false);
            int code = resp.statusCode();
            if (code >= 300 && code < 400) {
                String location = resp.headers().firstValue("Location").orElse(null);
                if (location == null || location.isBlank()) {
                    return current;
                }
                current = absoluteUrl(current, location);
                continue;
            }
            return current;
        }
        return current;
    }

    public static String absoluteUrl(String base, String location) {
        try {
            return URI.create(base).resolve(location).toString();
        } catch (Exception e) {
            return location;
        }
    }

    private static void applyHeaders(HttpRequest.Builder builder, Map<String, String> headers) {
        boolean hasEncoding = false;
        if (headers != null) {
            for (String key : headers.keySet()) {
                if (key != null && key.equalsIgnoreCase("Accept-Encoding")) {
                    hasEncoding = true;
                    break;
                }
            }
        }
        if (!hasEncoding) {
            // 网盘接口返回的 JSON/HTML 统一要明文，避免个别 CDN 强制 gzip 导致解析失败。
            builder.header("Accept-Encoding", "identity");
        }
        if (headers == null) {
            return;
        }
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey() != null && e.getValue() != null) {
                builder.header(e.getKey(), e.getValue());
            }
        }
    }

    private static String encodeForm(Map<String, String> form) {
        if (form == null || form.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : form.entrySet()) {
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8))
              .append('=')
              .append(URLEncoder.encode(
                      e.getValue() == null ? "" : e.getValue(), StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    @Override
    public void close() {
        // Java 21 的 HttpClient 实现 Closeable，及时释放连接池线程
        try {
            followClient.close();
        } catch (Exception ignored) {
        }
        try {
            plainClient.close();
        } catch (Exception ignored) {
        }
    }

    // ══════════════════════════════════════════
    //  JSON 便捷访问（配合项目内置 MiniJson）
    // ══════════════════════════════════════════

    public static Object json(String text) throws CloudException {
        try {
            return MiniJson.parse(text);
        } catch (Exception e) {
            throw new CloudException("接口返回非 JSON 内容: " + preview(text), e);
        }
    }

    private static String preview(String text) {
        if (text == null) {
            return "null";
        }
        String s = text.replaceAll("\\s+", " ").trim();
        return s.length() > 120 ? s.substring(0, 120) + "..." : s;
    }

    /** 按路径访问 JSON：对象用 key，数组用数字下标 */
    public static Object at(Object node, Object... path) {
        Object current = node;
        for (Object key : path) {
            if (current == null) {
                return null;
            }
            if (current instanceof Map<?, ?> map) {
                current = map.get(String.valueOf(key));
            } else if (current instanceof List<?> list && key instanceof Number n) {
                int index = n.intValue();
                current = (index >= 0 && index < list.size()) ? list.get(index) : null;
            } else {
                return null;
            }
        }
        return current;
    }

    public static String str(Object node, Object... path) {
        Object v = at(node, path);
        return v == null ? "" : String.valueOf(v);
    }

    public static long lng(Object node, Object... path) {
        Object v = at(node, path);
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return v == null ? -1 : Long.parseLong(String.valueOf(v));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    public static boolean bool(Object node, Object... path) {
        Object v = at(node, path);
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof Number n) {
            return n.intValue() != 0;
        }
        return v != null && ("true".equalsIgnoreCase(String.valueOf(v))
                || "1".equals(String.valueOf(v)));
    }

    @SuppressWarnings("unchecked")
    public static List<Object> list(Object node, Object... path) {
        Object v = at(node, path);
        if (v instanceof List<?> l) {
            return (List<Object>) l;
        }
        return new ArrayList<>();
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> map(Object node, Object... path) {
        Object v = at(node, path);
        if (v instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        return new LinkedHashMap<>();
    }

    /** 组装请求头，参数按 key1, value1, key2, value2 ... 传入 */
    public static Map<String, String> headers(String... pairs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }
}
