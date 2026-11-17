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

import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/**
 * 蓝奏云优享版（ILANZOU）分享解析。
 *
 * <p>分享列表与取链接口都在 {@code apix.ilanzou.com}，匿名可用；设备标识
 * {@code uuid} 从 {@code apis.ilanzou.com/unproved/getUuid} 获取。取链参数
 * 需要使用 AES/ECB/PKCS5Padding 加密（downloadId / auth / timestamp）。</p>
 */
public final class ILanzouProvider implements CloudProvider {

    private static final String API_BASE = "https://apis.ilanzou.com";
    private static final String WEB_SITE = "https://www.ilanzou.com";
    private static final String SHARE_API_BASE = "https://apix.ilanzou.com";

    private static final String GET_UUID = API_BASE + "/unproved/getUuid";
    private static final String SHARE_LIST = SHARE_API_BASE + "/unproved/share/list";
    private static final String SHARE_REDIRECT = SHARE_API_BASE + "/unproved/file/redirect";

    private static final String WEB_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";
    private static final String DEV_TYPE = "6";
    private static final String DEV_MODEL = "chrome";
    private static final String SHARE_DEV_MODEL = "Chrome";
    private static final String DEV_VERSION = "125";
    private static final int PAGE_SIZE = 60;
    private static final String AES_KEY = "lanZouY-disk-app";
    private static final String FOLDER_PREFIX = "d:";
    private static final String FILE_PREFIX = "f:";

    private static final Pattern ILANZOU_HOST = Pattern.compile(
            "^(?:[a-z0-9-]+\\.)*ilanzou\\.(?:com|net|org|cn)$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SHARE_PATH = Pattern.compile(
            "^/s/([A-Za-z0-9_-]+)(?:/.*)?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern UUID_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{8,128}$");
    private static final Pattern CODE_QUERY = Pattern.compile(
            "[?&]code=([A-Za-z0-9]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern CHARSET_PATTERN = Pattern.compile(
            "charset\\s*=\\s*[\"']?([A-Za-z0-9_-]+)", Pattern.CASE_INSENSITIVE);

    /** 设备标识与账号无关，可在同一 provider 实例内复用。 */
    private volatile String cachedUuid;

    @Override
    public CloudDrive drive() {
        return CloudDrive.ILANZOU;
    }

    @Override
    public boolean matches(String text) {
        if (!CloudLinks.containsUrl(text)) {
            return false;
        }
        String url = CloudLinks.extractUrl(text);
        String host = CloudLinks.hostOf(url);
        if (host.isEmpty() || !ILANZOU_HOST.matcher(host).matches()) {
            return false;
        }
        return SHARE_PATH.matcher(CloudLinks.pathOf(url)).matches();
    }

    @Override
    public CloudShare parse(String text) throws CloudException {
        if (!matches(text)) {
            throw new CloudException("无法识别蓝奏云优享版分享链接（应为 https://www.ilanzou.com/s/...）");
        }
        String url = CloudLinks.extractUrl(text);
        Matcher matcher = SHARE_PATH.matcher(CloudLinks.pathOf(url));
        if (!matcher.find()) {
            throw new CloudException("无法识别蓝奏云优享版分享链接中的分享编号");
        }
        String shareId = matcher.group(1);
        return new CloudShare(drive(), shareId, passcodeFrom(text, url),
                url, CloudLinks.hostOf(url));
    }

    @Override
    public List<CloudFile> list(CloudContext ctx, CloudShare share, CloudFile dir)
            throws Exception {
        String uuid = ensureUuid(ctx);
        String folderId = folderIdOf(dir);
        String parentId = dir == null ? "" : dir.id();
        String basePath = dir == null ? "/" : dir.displayPath();

        List<CloudFile> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int page = 1;
        while (page <= 1000) {
            Map<String, String> params = new LinkedHashMap<>();
            if (folderId != null) {
                params.put("folderId", folderId);
            }
            params.put("offset", String.valueOf(page));
            params.put("limit", String.valueOf(PAGE_SIZE));
            if (!share.passcode().isEmpty()) {
                params.put("code", share.passcode());
            }
            String url = shareUrl(SHARE_LIST, uuid, share.shareKey(), params);
            Map<String, Object> json = execShare(ctx, url);
            List<Object> items = CloudHttp.list(json, "list");
            if (items.isEmpty()) {
                break;
            }
            int added = 0;
            for (Object item : items) {
                CloudFile file = parseItem(item);
                if (file != null && seen.add(file.id())) {
                    file.parentId(parentId).path(basePath);
                    result.add(file);
                    added++;
                }
            }
            if (added == 0) {
                break;
            }
            long totalPage = CloudHttp.lng(json, "totalPage");
            if (totalPage > 0 && page >= totalPage) {
                break;
            }
            if (totalPage <= 0 && items.size() < PAGE_SIZE) {
                break;
            }
            page++;
        }
        return result;
    }

    @Override
    public CloudLink resolve(CloudContext ctx, CloudShare share, CloudFile file)
            throws Exception {
        if (file.folder()) {
            throw new CloudException("文件夹无法直接下载，请选择其中的文件");
        }
        String fileId = file.id();
        if (fileId.startsWith(FILE_PREFIX)) {
            fileId = fileId.substring(FILE_PREFIX.length());
        }
        if (fileId.isEmpty()) {
            throw new CloudException("蓝奏优享文件标识为空，请重新打开分享");
        }

        String uuid = ensureUuid(ctx);
        long timestamp = System.currentTimeMillis();
        Map<String, String> params = new LinkedHashMap<>();
        // 匿名分享 userId 传空串；大文件若被要求登录，服务端会在 msg 中提示。
        params.put("downloadId", aesHex(fileId + "|"));
        params.put("enable", "1");
        params.put("devType", DEV_TYPE);
        params.put("uuid", uuid);
        params.put("timestamp", aesHex(Long.toString(timestamp)));
        params.put("auth", aesHex(fileId + "|" + timestamp));
        params.put("shareId", share.shareKey());
        if (!share.passcode().isEmpty()) {
            params.put("code", share.passcode());
        }
        String url = SHARE_REDIRECT + "?" + encodeParts(params);

        HttpResponse<byte[]> resp = ctx.http().sendBytes(
                "GET", url, commonHeaders(), null, false);
        int status = resp.statusCode();
        String location = resp.headers().firstValue("Location").orElse("");
        if (status >= 300 && status < 400 && !location.isBlank()) {
            return new CloudLink(CloudHttp.absoluteUrl(SHARE_API_BASE, location), downloadHeaders());
        }

        Map<String, Object> json;
        try {
            json = parseJsonMap(decodeBody(resp), "蓝奏优享未返回下载地址");
        } catch (CloudException e) {
            throw new CloudException("蓝奏优享未返回下载地址（HTTP " + status + "）", e);
        }
        String link = firstNonBlank(
                CloudHttp.str(json, "url"),
                CloudHttp.str(json, "data", "url"));
        if (!link.isEmpty()) {
            return new CloudLink(link, downloadHeaders());
        }

        String msg = firstNonBlank(
                CloudHttp.str(json, "msg"),
                CloudHttp.str(json, "message"),
                CloudHttp.str(json, "data", "msg"));
        if (!msg.isEmpty()) {
            if (msg.contains("登录") || msg.toLowerCase().contains("login")) {
                throw new CloudException(
                        "蓝奏优享下载该文件需要登录，请先在网页版登录后重试（当前未提供账号凭据）");
            }
            throw new CloudException(msg);
        }
        throw new CloudException("蓝奏优享未返回下载地址，请检查文件权限或稍后重试");
    }

    // ───────────────────────── 内部实现 ─────────────────────────

    private static String passcodeFrom(String text, String url) {
        Matcher matcher = CODE_QUERY.matcher(url);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return CloudLinks.extractPasscode(text);
    }

    /** 根目录不传 folderId；子目录传去掉 d: 前缀后的数字 id。 */
    private static String folderIdOf(CloudFile dir) {
        if (dir == null) {
            return null;
        }
        String id = dir.id();
        if (id.startsWith(FOLDER_PREFIX)) {
            id = id.substring(FOLDER_PREFIX.length());
        }
        if (id.isEmpty() || "0".equals(id)) {
            return null;
        }
        return id;
    }

    private CloudFile parseItem(Object node) {
        long fileType = CloudHttp.lng(node, "fileType");
        if (fileType == 2) {
            String id = CloudHttp.str(node, "folderId");
            if (id.isEmpty() || "0".equals(id)) {
                return null;
            }
            String name = CloudHttp.str(node, "folderName");
            return new CloudFile(FOLDER_PREFIX + id, name.isEmpty() ? id : name, 0L, true);
        }
        String id = CloudHttp.str(node, "fileId");
        if (id.isEmpty() || "0".equals(id)) {
            return null;
        }
        String name = CloudHttp.str(node, "fileName");
        long size = CloudHttp.lng(node, "fileSize");
        if (size < 0) {
            size = 0;
        }
        size *= 1024L;
        return new CloudFile(FILE_PREFIX + id, name.isEmpty() ? id : name, size, false);
    }

    private String ensureUuid(CloudContext ctx) throws Exception {
        String cached = cachedUuid;
        if (cached != null && UUID_PATTERN.matcher(cached).matches()) {
            return cached;
        }
        String url = apiUrl(GET_UUID, "", null, true, Collections.emptyMap());
        Map<String, Object> json = execGet(ctx, url);
        String uuid = CloudHttp.str(json, "uuid");
        if (!UUID_PATTERN.matcher(uuid).matches()) {
            throw new CloudException("蓝奏优享未返回有效设备标识");
        }
        cachedUuid = uuid;
        return uuid;
    }

    private Map<String, Object> execGet(CloudContext ctx, String url) throws Exception {
        HttpResponse<byte[]> resp = ctx.http().sendBytes(
                "GET", url, commonHeaders(), null, true);
        int status = resp.statusCode();
        Map<String, Object> json;
        try {
            json = parseJsonMap(decodeBody(resp), "蓝奏优享请求失败");
        } catch (CloudException e) {
            throw new CloudException("蓝奏优享请求失败（HTTP " + status + "）", e);
        }
        long code = CloudHttp.lng(json, "code");
        if (code == 200) {
            return json;
        }
        if (code == -1 || code == -2) {
            throw new CloudException("蓝奏优享登录已过期，请重新登录");
        }
        String msg = CloudHttp.str(json, "msg");
        throw new CloudException(msg.isEmpty()
                ? "蓝奏优享请求失败（HTTP " + status + "）" : msg);
    }

    private Map<String, Object> execShare(CloudContext ctx, String url) throws Exception {
        HttpResponse<byte[]> resp = ctx.http().sendBytes(
                "GET", url, commonHeaders(), null, true);
        int status = resp.statusCode();
        Map<String, Object> json;
        try {
            json = parseJsonMap(decodeBody(resp), "蓝奏优享分享请求失败");
        } catch (CloudException e) {
            throw new CloudException("蓝奏优享分享请求失败（HTTP " + status + "）", e);
        }
        long code = CloudHttp.lng(json, "code");
        if (code != 200) {
            String msg = CloudHttp.str(json, "msg");
            throw new CloudException(msg.isEmpty()
                    ? "蓝奏优享分享请求失败（" + code + "）" : msg);
        }
        return json;
    }

    private String apiUrl(String base, String uuid, String appToken, boolean extra,
                          Map<String, String> params) throws CloudException {
        List<String> parts = new ArrayList<>();
        addPart(parts, "uuid", uuid);
        addPart(parts, "devType", DEV_TYPE);
        addPart(parts, "devCode", uuid);
        addPart(parts, "devModel", DEV_MODEL);
        addPart(parts, "devVersion", DEV_VERSION);
        addPart(parts, "appVersion", "");
        addPart(parts, "timestamp", aesHex(Long.toString(System.currentTimeMillis())));
        if (appToken != null && !appToken.isBlank()) {
            addPart(parts, "appToken", appToken);
        }
        if (extra) {
            addPart(parts, "extra", "2");
        }
        for (Map.Entry<String, String> entry : params.entrySet()) {
            addPart(parts, entry.getKey(), entry.getValue());
        }
        return base + "?" + String.join("&", parts);
    }

    private String shareUrl(String base, String uuid, String shareId,
                            Map<String, String> params) throws CloudException {
        List<String> parts = new ArrayList<>();
        addPart(parts, "uuid", uuid);
        addPart(parts, "devType", DEV_TYPE);
        addPart(parts, "devCode", uuid);
        addPart(parts, "devModel", SHARE_DEV_MODEL);
        addPart(parts, "devVersion", DEV_VERSION);
        addPart(parts, "appVersion", "");
        addPart(parts, "timestamp", aesHex(Long.toString(System.currentTimeMillis())));
        addPart(parts, "extra", "2");
        addPart(parts, "shareId", shareId);
        for (Map.Entry<String, String> entry : params.entrySet()) {
            addPart(parts, entry.getKey(), entry.getValue());
        }
        return base + "?" + String.join("&", parts);
    }

    private static void addPart(List<String> parts, String key, String value) {
        parts.add(key + "=" + encodeToken(value));
    }

    private static String encodeParts(Map<String, String> params) {
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            parts.add(entry.getKey() + "=" + encodeToken(entry.getValue()));
        }
        return String.join("&", parts);
    }

    /** 标准 query 编码后把 %3A 还原为冒号（与蓝奏优享文档一致）。 */
    private static String encodeToken(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8)
                .replace("%3A", ":");
    }

    private static Map<String, String> commonHeaders() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("User-Agent", WEB_UA);
        headers.put("Origin", WEB_SITE);
        headers.put("Referer", WEB_SITE + "/");
        headers.put("Accept", "application/json, text/plain, */*");
        headers.put("Accept-Language", "zh-CN,zh;q=0.9");
        headers.put("sec-ch-ua",
                "\"Google Chrome\";v=\"131\", \"Chromium\";v=\"131\", \"Not_A Brand\";v=\"24\"");
        headers.put("sec-ch-ua-mobile", "?0");
        headers.put("sec-ch-ua-platform", "\"Windows\"");
        headers.put("sec-fetch-dest", "empty");
        headers.put("sec-fetch-mode", "cors");
        headers.put("sec-fetch-site", "same-site");
        return headers;
    }

    /** 最终直链由 CDN 提供，下载需要 UA + Referer + Origin（不带 Cookie）。 */
    private static Map<String, String> downloadHeaders() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("User-Agent", WEB_UA);
        headers.put("Referer", WEB_SITE + "/");
        headers.put("Origin", WEB_SITE);
        return headers;
    }

    private static Map<String, Object> parseJsonMap(String body, String fallback)
            throws CloudException {
        try {
            Object parsed = CloudHttp.json(body);
            if (parsed instanceof Map<?, ?>) {
                return CloudHttp.map(parsed);
            }
        } catch (CloudException e) {
            throw new CloudException(fallback, e);
        }
        throw new CloudException(fallback);
    }

    private static String decodeBody(HttpResponse<byte[]> resp) {
        byte[] bytes = resp.body() == null ? new byte[0] : resp.body();
        Charset charset = charsetOf(
                resp.headers().firstValue("Content-Type").orElse(""));
        if (charset != null) {
            return new String(bytes, charset);
        }
        String utf8 = new String(bytes, StandardCharsets.UTF_8);
        return utf8.indexOf('\uFFFD') < 0
                ? utf8 : new String(bytes, Charset.forName("GBK"));
    }

    private static Charset charsetOf(String contentType) {
        Matcher matcher = CHARSET_PATTERN.matcher(contentType == null ? "" : contentType);
        if (!matcher.find()) {
            return null;
        }
        String name = matcher.group(1);
        if ("gb2312".equalsIgnoreCase(name) || "gbk".equalsIgnoreCase(name)
                || "gb18030".equalsIgnoreCase(name)) {
            return Charset.forName("GBK");
        }
        try {
            return Charset.forName(name);
        } catch (Exception e) {
            return null;
        }
    }

    /** AES/ECB/PKCS5Padding → 小写十六进制（取链的 downloadId / auth / timestamp）。 */
    private static String aesHex(String plain) throws CloudException {
        try {
            Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
            SecretKeySpec key = new SecretKeySpec(
                    AES_KEY.getBytes(StandardCharsets.UTF_8), "AES");
            cipher.init(Cipher.ENCRYPT_MODE, key);
            byte[] out = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(out.length * 2);
            for (byte b : out) {
                hex.append(String.format("%02x", b & 0xFF));
            }
            return hex.toString();
        } catch (GeneralSecurityException e) {
            throw new CloudException("蓝奏优享加密参数生成失败", e);
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return "";
    }
}
