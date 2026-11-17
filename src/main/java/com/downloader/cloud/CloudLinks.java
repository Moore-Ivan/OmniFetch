package com.downloader.cloud;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 分享文本中的链接/提取码提取工具。
 * 用户经常直接粘贴「链接: https://xxx 提取码: abcd」这类整段文案。
 */
public final class CloudLinks {

    private static final Pattern URL_PATTERN =
            Pattern.compile("https?://[\\w\\-._~:/?#\\[\\]@!$&'()*+,;=%]+",
                    Pattern.CASE_INSENSITIVE);

    private static final Pattern PWD_PATTERN = Pattern.compile(
            "(?:提取码|提取碼|访问码|訪問碼|密码|密碼|口令|code|pwd|password|passcode)"
                    + "\\s*[:：=]?\\s*([A-Za-z0-9]{4,8})",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern QUERY_PWD = Pattern.compile(
            "[?&](?:pwd|password|passcode|code)=([A-Za-z0-9]{4,8})",
            Pattern.CASE_INSENSITIVE);

    private CloudLinks() {
    }

    public static boolean containsUrl(String text) {
        return text != null && URL_PATTERN.matcher(text).find();
    }

    /** 提取文本中第一个 http(s) 链接，去掉尾部标点 */
    public static String extractUrl(String text) {
        if (text == null) {
            return "";
        }
        Matcher m = URL_PATTERN.matcher(text);
        if (!m.find()) {
            return text.trim();
        }
        String url = m.group();
        while (url.endsWith(".") || url.endsWith(",") || url.endsWith("。")
                || url.endsWith("，") || url.endsWith(")") || url.endsWith("）")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    /** 优先从链接 query 取提取码，其次从整段文案中取 */
    public static String extractPasscode(String text) {
        if (text == null) {
            return "";
        }
        String url = extractUrl(text);
        Matcher q = QUERY_PWD.matcher(url);
        if (q.find()) {
            return q.group(1);
        }
        Matcher m = PWD_PATTERN.matcher(text);
        if (m.find()) {
            return m.group(1);
        }
        return "";
    }

    public static String hostOf(String url) {
        try {
            URI uri = URI.create(url);
            return uri.getHost() == null ? "" : uri.getHost();
        } catch (Exception e) {
            return "";
        }
    }

    public static String pathOf(String url) {
        try {
            URI uri = URI.create(url);
            return uri.getPath() == null ? "" : uri.getPath();
        } catch (Exception e) {
            return "";
        }
    }

    /** 解析 query 参数为 Map（同名参数保留第一个） */
    public static Map<String, String> queryOf(String url) {
        Map<String, String> map = new LinkedHashMap<>();
        try {
            String raw = URI.create(url).getRawQuery();
            if (raw == null) {
                return map;
            }
            for (String part : raw.split("&")) {
                int eq = part.indexOf('=');
                if (eq > 0) {
                    String key = java.net.URLDecoder.decode(
                            part.substring(0, eq), java.nio.charset.StandardCharsets.UTF_8);
                    String value = java.net.URLDecoder.decode(
                            part.substring(eq + 1), java.nio.charset.StandardCharsets.UTF_8);
                    map.putIfAbsent(key, value);
                }
            }
        } catch (Exception ignored) {
        }
        return map;
    }
}
