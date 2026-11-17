package com.downloader.cloud.providers;

import com.downloader.cloud.CloudContext;
import com.downloader.cloud.CloudDrive;
import com.downloader.cloud.CloudException;
import com.downloader.cloud.CloudFile;
import com.downloader.cloud.CloudHttp;
import com.downloader.cloud.CloudLink;
import com.downloader.cloud.CloudLinks;
import com.downloader.cloud.CloudProvider;
import com.downloader.cloud.CloudShare;
import com.downloader.util.MiniJson;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * 139 网盘（和彩云）分享解析器。
 * <p>分享接口使用 AES-CBC（IV 前置）加密请求体，列表接口匿名调用，下载接口按需附带登录态。
 */
public final class Yun139Provider implements CloudProvider {

    private static final String SHARE_BASE = "https://share-kd-njs.yun.139.com";
    private static final String LIST_URL = SHARE_BASE
            + "/yun-share/richlifeApp/devapp/IOutLink/getOutLinkInfoV6";
    private static final String DOWNLOAD_URL = SHARE_BASE
            + "/yun-share/richlifeApp/devapp/IOutLink/dlFromOutLinkV3";

    private static final String AES_KEY = "PVGDwmcvfs1uV3d1";
    private static final String DEVICE_INFO = "||3|12.27.0|||||chrome 150.0.0.0|360X444|zh-cn|||";
    private static final String HUAWEI_CHANNELSRC = "10245500";
    private static final String MM_SOURCE = "0002";
    private static final String SHARE_UA =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/150.0.0.0 Mobile Safari/537.36";
    private static final String DEFAULT_HOST = "yun.139.com";

    private static final int PAGE_SIZE = 200;
    private static final int MAX_BEGIN = 20_000;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private static final Pattern SHAREWEB_HASH = Pattern.compile(
            "/shareweb/(?:[^#\\s]*)?#/w/i/([A-Za-z0-9_-]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern SHAREWEB_PATH = Pattern.compile(
            "/shareweb/.*?/w/i/([A-Za-z0-9_-]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern PATH_STYLE = Pattern.compile(
            "139\\.com/(?:m|w)/i/([A-Za-z0-9_-]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern QUERY_STYLE = Pattern.compile(
            "139\\.com/(?:m|w)/i\\?([^#&\\s]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern QUERY_LINK_ID = Pattern.compile(
            "[?&](?:linkID|linkId|link_id)=([A-Za-z0-9_-]+)", Pattern.CASE_INSENSITIVE);

    @Override
    public CloudDrive drive() {
        return CloudDrive.YUN139;
    }

    @Override
    public boolean matches(String text) {
        if (text == null) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        if (!lower.contains("139.com")) {
            return false;
        }
        if (!lower.contains("/shareweb") && !lower.contains("/m/i")
                && !lower.contains("/w/i") && !lower.contains("linkid=")) {
            return false;
        }
        return extractLinkId(normalizedUrl(text)) != null;
    }

    @Override
    public CloudShare parse(String text) throws CloudException {
        String url = normalizedUrl(text);
        String linkId = extractLinkId(url);
        if (linkId == null || linkId.isBlank()) {
            throw new CloudException("无法识别 139 网盘分享链接");
        }
        String host = CloudLinks.hostOf(url);
        if (host.isEmpty()) {
            host = DEFAULT_HOST;
        }
        return new CloudShare(CloudDrive.YUN139, linkId,
                CloudLinks.extractPasscode(text), url, host);
    }

    @Override
    public List<CloudFile> list(CloudContext ctx, CloudShare share, CloudFile dir) throws Exception {
        String pCaID = dir == null || dir.id().isBlank() ? "root" : dir.id();
        String parentId = dir == null ? "" : dir.id();
        String basePath = dir == null ? "/" : dir.displayPath();

        List<CloudFile> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int begin = 1;
        int pages = 0;
        while (begin <= MAX_BEGIN && pages++ < 100) {
            Map<String, Object> json = requestList(ctx, share, pCaID, begin);
            checkResult(json, "获取 139 网盘文件列表失败");
            Map<String, Object> data = CloudHttp.map(json, "data");
            List<Object> folders = CloudHttp.list(data, "caLst");
            List<Object> files = CloudHttp.list(data, "coLst");
            int batch = folders.size() + files.size();

            for (Object raw : folders) {
                Map<String, Object> item = CloudHttp.map(raw);
                String id = CloudHttp.str(item, "caID");
                if (id.isBlank() || !seen.add("d:" + id)) {
                    continue;
                }
                String name = CloudHttp.str(item, "caName");
                result.add(new CloudFile(id, name, 0, true)
                        .parentId(parentId)
                        .path(basePath));
            }
            for (Object raw : files) {
                Map<String, Object> item = CloudHttp.map(raw);
                String id = CloudHttp.str(item, "coID");
                if (id.isBlank() || !seen.add("f:" + id)) {
                    continue;
                }
                boolean folder = item.containsKey("isdir")
                        ? CloudHttp.bool(item, "isdir")
                        : CloudHttp.lng(item, "coType") == 2;
                String name = CloudHttp.str(item, "coName");
                long size = CloudHttp.lng(item, "coSize");
                result.add(new CloudFile(id, name, size, folder)
                        .parentId(parentId)
                        .path(basePath));
            }
            if (batch < PAGE_SIZE) {
                break;
            }
            begin += PAGE_SIZE;
        }
        return result;
    }

    @Override
    public CloudLink resolve(CloudContext ctx, CloudShare share, CloudFile file) throws Exception {
        if (file.folder()) {
            throw new CloudException("目录不能直接下载，请选择文件");
        }
        if (file.id().isBlank()) {
            throw new CloudException("文件标识缺失，无法解析下载直链");
        }

        String account = extractAccount(ctx.cookieText());
        String authorization = extractAuthorization(ctx.cookieText());
        if (account.isBlank() && authorization.isBlank()) {
            throw new CloudException(
                    "139 网盘下载需要登录，请填写 139 网页版 Cookie（需包含 account 与 authorization）");
        }
        Map<String, Object> json = requestDownload(ctx, share, file.id(), account, authorization);
        checkResult(json, "获取 139 网盘下载直链失败");

        String redrUrl = CloudHttp.str(json, "data", "redrUrl");
        if (redrUrl.isBlank()) {
            throw new CloudException("139 网盘接口未返回下载直链");
        }
        Map<String, String> headers = CloudHttp.headers(
                "User-Agent", SHARE_UA,
                "Referer", "https://yun.139.com/",
                "Accept", "*/*");
        return new CloudLink(redrUrl, headers);
    }

    private Map<String, Object> requestList(CloudContext ctx, CloudShare share,
                                            String pCaID, int begin) throws Exception {
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("account", "");
        req.put("linkID", share.shareKey());
        req.put("passwd", share.passcode());
        req.put("caSrt", 1);
        req.put("coSrt", 1);
        req.put("srtDr", 0);
        req.put("bNum", begin);
        req.put("pCaID", pCaID);
        req.put("eNum", begin + PAGE_SIZE - 1);

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("getOutLinkInfoReq", req);
        String plain = compactJson(root);
        String body = ctx.http().postJson(LIST_URL, encryptBody(plain), shareHeaders(null, null));
        return parseEncrypted(body);
    }

    private Map<String, Object> requestDownload(CloudContext ctx, CloudShare share,
                                                String coId, String account,
                                                String authorization) throws Exception {
        Map<String, Object> coIdList = new LinkedHashMap<>();
        coIdList.put("item", Arrays.asList(coId));

        Map<String, Object> common = new LinkedHashMap<>();
        common.put("account", account);
        common.put("accountType", 1);

        Map<String, Object> req = new LinkedHashMap<>();
        req.put("account", account);
        req.put("linkID", share.shareKey());
        req.put("coIDLst", coIdList);
        req.put("commonAccountInfo", common);

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("dlFromOutLinkReqV3", req);
        String plain = compactJson(root);
        String body = ctx.http().postJson(DOWNLOAD_URL, encryptBody(plain),
                shareHeaders(plain, authorization));
        return parseEncrypted(body);
    }

    private static Map<String, String> shareHeaders(String plainForSign,
                                                    String authorization) throws Exception {
        Map<String, String> headers = CloudHttp.headers(
                "hcy-cool-flag", "1",
                "x-deviceinfo", DEVICE_INFO,
                "x-huawei-channelsrc", HUAWEI_CHANNELSRC,
                "x-mm-source", MM_SOURCE,
                "Content-Type", "application/json;charset=UTF-8",
                "User-Agent", SHARE_UA,
                "Origin", "https://yun.139.com",
                "Referer", "https://yun.139.com/",
                "Accept", "application/json, text/plain, */*");
        if (plainForSign != null) {
            headers.put("mcloud-sign", signHeader(plainForSign));
        }
        if (authorization != null && !authorization.isBlank()) {
            headers.put("Authorization", authorization);
        }
        return headers;
    }

    private static String signHeader(String plain) throws Exception {
        String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(new Date());
        String rand = randomText(16);
        return ts + "," + rand + "," + calSign(plain, ts, rand);
    }

    private static String calSign(String body, String ts, String rand) throws Exception {
        String encoded = encodeUriComponent(body);
        char[] chars = encoded.toCharArray();
        Arrays.sort(chars);
        String sorted = new String(chars);
        String b64 = Base64.getEncoder().encodeToString(sorted.getBytes(StandardCharsets.UTF_8));
        String result = md5Hex(b64) + md5Hex(ts + ":" + rand);
        return md5Hex(result).toUpperCase(Locale.ROOT);
    }

    private static String encodeUriComponent(String value) {
        String encoded = URLEncoder.encode(value, StandardCharsets.UTF_8);
        return encoded.replace("+", "%20")
                .replace("%21", "!")
                .replace("%27", "'")
                .replace("%28", "(")
                .replace("%29", ")")
                .replace("%2A", "*");
    }

    private static String md5Hex(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("MD5")
                .digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            int v = b & 0xFF;
            sb.append(HEX[v >>> 4]).append(HEX[v & 0x0F]);
        }
        return sb.toString();
    }

    private static String randomText(int length) {
        StringBuilder sb = new StringBuilder(length);
        String pool = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        for (int i = 0; i < length; i++) {
            sb.append(pool.charAt(RANDOM.nextInt(pool.length())));
        }
        return sb.toString();
    }

    private static String encryptBody(String plain) throws Exception {
        byte[] iv = new byte[16];
        RANDOM.nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(
                AES_KEY.getBytes(StandardCharsets.UTF_8), "AES"), new IvParameterSpec(iv));
        byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
        byte[] result = new byte[iv.length + encrypted.length];
        System.arraycopy(iv, 0, result, 0, iv.length);
        System.arraycopy(encrypted, 0, result, iv.length, encrypted.length);
        return Base64.getEncoder().encodeToString(result);
    }

    private static String decryptBody(String base64) throws Exception {
        byte[] raw = Base64.getDecoder().decode(base64.replaceAll("\\s+", ""));
        if (raw.length < 32) {
            throw new IllegalArgumentException("加密数据长度不足");
        }
        byte[] iv = Arrays.copyOfRange(raw, 0, 16);
        byte[] encrypted = Arrays.copyOfRange(raw, 16, raw.length);
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(
                AES_KEY.getBytes(StandardCharsets.UTF_8), "AES"), new IvParameterSpec(iv));
        byte[] decrypted = cipher.doFinal(encrypted);
        if (decrypted.length > 2 && decrypted[0] == 0x1f && decrypted[1] == (byte) 0x8b) {
            decrypted = gunzip(decrypted);
        }
        return new String(decrypted, StandardCharsets.UTF_8);
    }

    private static byte[] gunzip(byte[] data) throws Exception {
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(data));
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }

    private static Map<String, Object> parseEncrypted(String body) throws CloudException {
        if (body == null || body.isBlank()) {
            throw new CloudException("139 网盘接口返回为空");
        }
        try {
            return mapOf(MiniJson.parse(decryptBody(body.trim())));
        } catch (Exception ignored) {
            try {
                return mapOf(MiniJson.parse(body));
            } catch (Exception e) {
                throw new CloudException("139 网盘接口返回内容无法解析", e);
            }
        }
    }

    private static Map<String, Object> mapOf(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                result.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            return result;
        }
        return new LinkedHashMap<>();
    }

    private static void checkResult(Map<String, Object> json, String fallback)
            throws CloudException {
        String code = CloudHttp.str(json, "resultCode");
        if (code.isBlank()) {
            code = CloudHttp.str(json, "code");
        }
        boolean success = true;
        if (json.containsKey("success")) {
            success = CloudHttp.bool(json, "success");
        }
        String desc = CloudHttp.str(json, "desc");
        if ((!code.isBlank() && !"0".equals(code)) || !success) {
            throw new CloudException(errorText(code, desc, fallback));
        }
    }

    private static String errorText(String code, String desc, String fallback) {
        String message = desc.isBlank() ? fallback : desc;
        if ("9188".equals(code)) {
            return "提取码错误，请检查提取码";
        }
        if ("9530".equals(code)) {
            return "139 网盘分享接口校验失败（9530），请稍后重试或填写 Cookie";
        }
        if ("200000401".equals(code)) {
            return "139 网盘下载需要登录或当前 IP 未授权，请填写 139 Cookie（含 account/authorization）";
        }
        return code.isBlank() ? message : message + "（" + code + "）";
    }

    private static String extractAuthorization(String cookie) {
        return cookieValue(cookie, "authorization");
    }

    private static String extractAccount(String cookie) {
        String encrypted = cookieValue(cookie, "ORCHES-I-ACCOUNT-ENCRYPT");
        if (!encrypted.isBlank()) {
            try {
                String decoded = new String(Base64.getDecoder().decode(encrypted),
                        StandardCharsets.UTF_8);
                if (!decoded.isBlank()) {
                    return decoded;
                }
            } catch (Exception ignored) {
                // 解码失败时继续尝试其它来源
            }
        }
        String authorization = extractAuthorization(cookie);
        if (!authorization.isBlank()) {
            try {
                String raw = authorization.replaceFirst("(?i)^Basic\\s*", "");
                String decoded = new String(Base64.getDecoder().decode(raw),
                        StandardCharsets.UTF_8);
                String[] parts = decoded.split(":");
                if (parts.length > 1 && !parts[1].isBlank()) {
                    return parts[1];
                }
            } catch (Exception ignored) {
                // 忽略非法 authorization
            }
        }
        return cookieValue(cookie, "Login_UserNumber");
    }

    private static String cookieValue(String cookie, String name) {
        if (cookie == null || cookie.isBlank()) {
            return "";
        }
        for (String part : cookie.split(";")) {
            String pair = part.trim();
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).trim().equalsIgnoreCase(name)) {
                return pair.substring(eq + 1).trim();
            }
        }
        return "";
    }

    private static String compactJson(Object value) {
        StringBuilder sb = new StringBuilder();
        appendJson(sb, value);
        return sb.toString();
    }

    private static void appendJson(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
            return;
        }
        if (value instanceof String text) {
            appendJsonString(sb, text);
            return;
        }
        if (value instanceof Number || value instanceof Boolean) {
            sb.append(value);
            return;
        }
        if (value instanceof Map<?, ?> map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                appendJsonString(sb, String.valueOf(entry.getKey()));
                sb.append(':');
                appendJson(sb, entry.getValue());
            }
            sb.append('}');
            return;
        }
        if (value instanceof List<?> list) {
            sb.append('[');
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                appendJson(sb, list.get(i));
            }
            sb.append(']');
            return;
        }
        appendJsonString(sb, String.valueOf(value));
    }

    private static void appendJsonString(StringBuilder sb, String text) {
        sb.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    private static String extractLinkId(String url) {
        Matcher m = SHAREWEB_HASH.matcher(url);
        if (m.find()) {
            return m.group(1);
        }
        m = SHAREWEB_PATH.matcher(url);
        if (m.find()) {
            return m.group(1);
        }
        m = PATH_STYLE.matcher(url);
        if (m.find()) {
            return m.group(1);
        }
        m = QUERY_STYLE.matcher(url);
        if (m.find()) {
            String raw = m.group(1);
            try {
                raw = URLDecoder.decode(raw, StandardCharsets.UTF_8);
            } catch (Exception ignored) {
                // 保留原始值
            }
            if (raw.regionMatches(true, 0, "linkID=", 0, "linkID=".length())) {
                raw = raw.substring("linkID=".length());
            }
            return raw.trim();
        }
        m = QUERY_LINK_ID.matcher(url);
        if (m.find()) {
            return m.group(1);
        }
        return null;
    }

    private static String normalizedUrl(String text) {
        String url = CloudLinks.extractUrl(text);
        if (url.isEmpty()) {
            url = text == null ? "" : text.trim();
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://" + url;
        }
        return url;
    }
}
