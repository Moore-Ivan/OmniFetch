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

import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;

/**
 * 123 云盘分享解析：{@code /b/api/share/get} 列目录，{@code /b/api/share/download/info} 取直链。
 * <p>分享列表匿名可读；下载信息接口需要登录 token 并携带 CRC32 派生的 auth-key/auth-value 签名头。
 * 网络请求头、签名算法、DownloadURL 的 Base64 二次解码与 302 redirect_url 跟随均按参考实现移植。
 */
public final class Pan123Provider implements CloudProvider {

    private static final String YUN_BASE = "https://yun.123pan.cn";
    private static final String DOWNLOAD_BASE = "https://www.123865.com";

    private static final String SHARE_GET_PATH = "/b/api/share/get";
    private static final String SHARE_DOWNLOAD_PATH = "/b/api/share/download/info";

    private static final String WEB_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Safari/537.36";
    private static final String DART_UA = "Dart/3.12 (dart:io)";
    private static final String DOWNLOAD_REFERER = "https://yun.123pan.cn/";

    private static final String PLATFORM_WEB = "web";
    private static final String PLATFORM_ANDROID = "android";
    private static final String APP_VERSION_WEB = "3";
    private static final String APP_VERSION_ANDROID = "39";

    /** timeSign 的数字替换表（索引 = 数字 0-9） */
    private static final String SIGN_TABLE = "adefghlmyijnopkqrstubcvwsz";
    /** 签名时间基准：ts + 16 小时，再按 UTC 取分钟 */
    private static final long SIGN_OFFSET_SECONDS = 57600L;

    private static final int PAGE_LIMIT = 100;
    private static final int MAX_PAGES = 50;
    /** 设备标识，进程内保持不变（123 把它当设备指纹参与风控） */
    private static final String LOGIN_UUID =
            UUID.randomUUID().toString().replace("-", "");

    private static final Pattern SHARE_URL = Pattern.compile(
            "123(?:pan|684|865|912)\\.(?:com|cn|net|org)/s/([A-Za-z0-9-]+)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SHARE_SUB = Pattern.compile(
            "share\\.123pan\\.cn/123pan/([A-Za-z0-9-]+)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SHARE_SRR = Pattern.compile(
            "api/srr\\?sk=([A-Za-z0-9-]+)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern MIRROR_HOST = Pattern.compile(
            "(?:www\\.)?123(?:684|865|912)\\.(?:com|cn|net|org)",
            Pattern.CASE_INSENSITIVE);

    @Override
    public CloudDrive drive() {
        return CloudDrive.PAN123;
    }

    @Override
    public boolean matches(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        return SHARE_URL.matcher(text).find()
                || SHARE_SUB.matcher(text).find()
                || SHARE_SRR.matcher(text).find();
    }

    @Override
    public CloudShare parse(String text) throws CloudException {
        String url = CloudLinks.extractUrl(text);
        String shareKey = "";
        Matcher matcher = SHARE_URL.matcher(url);
        if (matcher.find()) {
            shareKey = matcher.group(1);
        }
        if (shareKey.isBlank()) {
            matcher = SHARE_SUB.matcher(url);
            if (matcher.find()) {
                shareKey = matcher.group(1);
            }
        }
        if (shareKey.isBlank()) {
            matcher = SHARE_SRR.matcher(url);
            if (matcher.find()) {
                shareKey = matcher.group(1);
            }
        }
        if (shareKey.isBlank()) {
            throw new CloudException("未在文本中找到 123 云盘分享链接");
        }
        String host = CloudLinks.hostOf(url);
        if (host.isBlank()) {
            host = "www.123pan.com";
        }
        String passcode = CloudLinks.extractPasscode(text);
        return new CloudShare(CloudDrive.PAN123, shareKey, passcode, url, host);
    }

    @Override
    public List<CloudFile> list(CloudContext ctx, CloudShare share, CloudFile dir) throws Exception {
        ctx.seedCookiesForHost(share.host());
        String parentFileId = dir == null ? "0" : dir.id();
        if (parentFileId.isBlank()) {
            parentFileId = "0";
        }
        String basePath = dir == null ? "/" : dir.displayPath();

        List<CloudFile> result = new ArrayList<>();
        int page = 1;
        while (page <= MAX_PAGES) {
            String url = apiBase(share) + SHARE_GET_PATH
                    + "?limit=" + PAGE_LIMIT
                    + "&next=0"
                    + "&orderBy=file_name&orderDirection=asc"
                    + "&shareKey=" + encode(share.shareKey())
                    + "&ParentFileId=" + encode(parentFileId)
                    + "&Page=" + page;
            // 参考实现明确：无提取码时不传 SharePwd（传空值会 400）
            if (!share.passcode().isBlank()) {
                url += "&SharePwd=" + encode(share.passcode());
            }

            String body = ctx.http().getText(url,
                    CloudHttp.headers("User-Agent", DART_UA));
            Object json = CloudHttp.json(body);
            checkOk(json, "获取文件列表失败");

            Object dataNode = CloudHttp.at(json, "data");
            if (dataNode == null) {
                break;
            }
            Map<String, Object> data = CloudHttp.map(json, "data");
            if (CloudHttp.bool(data, "Expired")) {
                throw new CloudException("分享已失效");
            }

            List<Object> items = CloudHttp.list(data, "InfoList");
            for (Object entry : items) {
                if (!(entry instanceof Map<?, ?>)) {
                    continue;
                }
                Map<String, Object> item = CloudHttp.map(entry);
                String id = CloudHttp.str(item, "FileId");
                if (id.isBlank()) {
                    continue;
                }
                boolean folder = CloudHttp.lng(item, "Type") == 1L;
                long size = folder ? 0L : Math.max(0L, CloudHttp.lng(item, "Size"));
                CloudFile file = new CloudFile(id, CloudHttp.str(item, "FileName"), size, folder)
                        .parentId(CloudHttp.str(item, "ParentFileId"))
                        .path(basePath);
                String s3KeyFlag = CloudHttp.str(item, "S3KeyFlag");
                String etag = CloudHttp.str(item, "Etag");
                String storageNode = CloudHttp.str(item, "StorageNode");
                if (!s3KeyFlag.isBlank()) {
                    file.put("s3KeyFlag", s3KeyFlag);
                }
                if (!etag.isBlank()) {
                    file.put("etag", etag);
                }
                if (!storageNode.isBlank()) {
                    file.put("storageNode", storageNode);
                }
                result.add(file);
            }

            // Next=="-1" 表示末页；空串表示还有下一页；翻页靠 Page 递增
            String next = CloudHttp.str(data, "Next");
            boolean hasMore = !items.isEmpty() && !"-1".equals(next);
            if (!hasMore) {
                break;
            }
            page++;
        }
        return result;
    }

    @Override
    public CloudLink resolve(CloudContext ctx, CloudShare share, CloudFile file) throws Exception {
        ctx.seedCookiesForHost(share.host());
        // 部分公开分享无需登录也能取到直链，因此先按匿名请求，失败后再提示补充凭据
        String token = loginToken(ctx);

        String s3KeyFlag = extraString(file, "s3KeyFlag");
        String etag = extraString(file, "etag");
        if (s3KeyFlag.isBlank() || etag.isBlank()) {
            throw new CloudException("文件信息缺少 S3KeyFlag/Etag，请重新解析 123 分享链接");
        }
        long size = file.size() < 0 ? 0L : file.size();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ShareKey", share.shareKey());
        payload.put("FileID", file.id());
        payload.put("S3KeyFlag", s3KeyFlag);
        payload.put("Size", size);
        payload.put("Etag", etag);
        String body = MiniJson.write(payload);

        long ts = System.currentTimeMillis() / 1000L;
        String[] sign = makeSign(SHARE_DOWNLOAD_PATH, ts);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("platform", PLATFORM_ANDROID);
        headers.put("app-version", APP_VERSION_ANDROID);
        if (!token.isBlank()) {
            headers.put("authorization", "Bearer " + token);
        }
        headers.put("loginuuid", LOGIN_UUID);
        headers.put("auth-key", sign[0]);
        headers.put("auth-value", sign[1]);
        headers.put("Content-Type", "application/json;charset=UTF-8");
        headers.put("User-Agent", WEB_UA);

        String text = ctx.http().postJson(downloadBase(share) + SHARE_DOWNLOAD_PATH, body, headers);
        Object json = CloudHttp.json(text);
        long code = CloudHttp.lng(json, "code");
        if (code != 0L && token.isBlank()) {
            throw new CloudException("该 123 分享需要登录才能下载，请填写网页版 Cookie（含 authorToken）或直接粘贴 token"
                    + "（接口返回 code=" + code + "）");
        }
        checkOk(json, "获取下载链接失败");

        String downloadUrl = CloudHttp.str(json, "data", "DownloadURL");
        if (downloadUrl.isBlank()) {
            throw new CloudException("获取下载链接失败：123 未返回 DownloadURL");
        }
        String decoded = decodeDownloadUrl(downloadUrl);
        if (decoded == null) {
            decoded = downloadUrl;
        }
        String finalUrl = followRedirect(ctx, decoded);
        return new CloudLink(finalUrl, CloudHttp.headers(
                "Referer", DOWNLOAD_REFERER,
                "User-Agent", DART_UA));
    }

    // ---------- 签名（参考实现 makeSign） ----------

    /**
     * 生成 auth-key / auth-value：
     * auth-key = crc32hex(数字替换后的 UTC "YYYYMMDDHHmm"，基准 ts+16h)；
     * auth-value = {@code ts-random-crc32hex(ts|random|path|web|3|authKey)}。
     */
    private static String[] makeSign(String path, long ts) {
        Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        calendar.setTimeInMillis((ts + SIGN_OFFSET_SECONDS) * 1000L);
        String minute = String.format(Locale.ROOT, "%04d%02d%02d%02d%02d",
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH) + 1,
                calendar.get(Calendar.DAY_OF_MONTH),
                calendar.get(Calendar.HOUR_OF_DAY),
                calendar.get(Calendar.MINUTE));

        StringBuilder substituted = new StringBuilder(minute.length());
        for (int i = 0; i < minute.length(); i++) {
            char c = minute.charAt(i);
            substituted.append(SIGN_TABLE.charAt(c - '0'));
        }
        String authKey = crc32Hex(substituted.toString());

        int random = ThreadLocalRandom.current().nextInt(10_000_000);
        String data = ts + "|" + random + "|" + path + "|"
                + PLATFORM_WEB + "|" + APP_VERSION_WEB + "|" + authKey;
        String authValue = ts + "-" + random + "-" + crc32Hex(data);
        return new String[]{authKey, authValue};
    }

    private static String crc32Hex(String text) {
        CRC32 crc = new CRC32();
        crc.update(text.getBytes(StandardCharsets.UTF_8));
        return Long.toHexString(crc.getValue());
    }

    // ---------- DownloadURL 解码与 302 跟随 ----------

    /** 兼容两种形态：整段 Base64，或 download-v2?params=&lt;Base64 URL-safe&gt; */
    private static String decodeDownloadUrl(String raw) {
        String value = raw.trim();
        if (!value.contains("://")) {
            try {
                String decoded = new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
                return decoded.regionMatches(true, 0, "http", 0, 4) ? decoded : null;
            } catch (Exception e) {
                return null;
            }
        }
        int index = value.indexOf("params=");
        if (index < 0) {
            return null;
        }
        String params = value.substring(index + "params=".length());
        int amp = params.indexOf('&');
        if (amp >= 0) {
            params = params.substring(0, amp);
        }
        String normalized = params.replace('-', '+').replace('_', '/');
        int padding = normalized.length() % 4;
        if (padding > 0) {
            StringBuilder builder = new StringBuilder(normalized);
            for (int i = 0; i < 4 - padding; i++) {
                builder.append('=');
            }
            normalized = builder.toString();
        }
        try {
            String decoded = new String(Base64.getDecoder().decode(normalized), StandardCharsets.UTF_8);
            return decoded.regionMatches(true, 0, "http", 0, 4) ? decoded : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 带 auto_redirect=0 的 CDN 地址会返回 JSON（data.redirect_url），最多跟 5 跳 */
    private String followRedirect(CloudContext ctx, String initialUrl) {
        String url = initialUrl;
        for (int i = 0; i < 5; i++) {
            String next = probeJsonRedirect(ctx, url);
            if (next == null) {
                break;
            }
            url = next;
        }
        return url;
    }

    /**
     * 用 HEAD 探测是否为小 JSON 跳转页，避免把实际文件内容整段读进内存。
     * 非 JSON 或体积不明时视为最终可下载地址。
     */
    private String probeJsonRedirect(CloudContext ctx, String url) {
        Map<String, String> headers = CloudHttp.headers(
                "Referer", DOWNLOAD_REFERER,
                "User-Agent", DART_UA);
        try {
            HttpResponse<String> response = ctx.http().send("HEAD", url, headers, null, true);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return null;
            }
            String contentType = response.headers().firstValue("Content-Type")
                    .orElse("").toLowerCase(Locale.ROOT);
            long length = parseLong(response.headers().firstValue("Content-Length").orElse(""));
            boolean maybeJson = length >= 0 && length <= 8192
                    && (contentType.isEmpty()
                    || contentType.contains("json")
                    || contentType.contains("text/plain")
                    || contentType.contains("text/html"));
            if (!maybeJson) {
                return null;
            }
            String body = ctx.http().getText(url, headers);
            String trimmed = body == null ? "" : body.trim();
            if (!trimmed.startsWith("{")) {
                return null;
            }
            String next = CloudHttp.str(CloudHttp.json(trimmed), "data", "redirect_url");
            return next.isBlank() ? null : next;
        } catch (Exception e) {
            return null;
        }
    }

    // ---------- 鉴权与错误处理 ----------

    /** 从 Cookie 文本中取 123 的登录 token（authorToken），也允许直接粘贴 token */
    private static String loginToken(CloudContext ctx) {
        String raw = ctx.cookieText();
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String text = raw.trim();
        for (String part : text.split(";")) {
            String pair = part.trim();
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String name = pair.substring(0, eq).trim();
            String value = stripQuotes(pair.substring(eq + 1).trim());
            if (name.equalsIgnoreCase("authorToken")
                    || name.equalsIgnoreCase("token")
                    || name.equalsIgnoreCase("access_token")
                    || name.equalsIgnoreCase("accessToken")
                    || name.equalsIgnoreCase("authorization")) {
                return stripBearer(value);
            }
            if (value.startsWith("eyJ")) {
                return value;
            }
        }
        String single = stripBearer(stripQuotes(text));
        if (!single.contains(";") && !single.contains("=") && !single.isBlank()) {
            return single;
        }
        return "";
    }

    private static String stripQuotes(String value) {
        String text = value == null ? "" : value.trim();
        if (text.length() >= 2
                && ((text.startsWith("\"") && text.endsWith("\""))
                || (text.startsWith("'") && text.endsWith("'")))) {
            return text.substring(1, text.length() - 1).trim();
        }
        return text;
    }

    private static String stripBearer(String value) {
        String text = value == null ? "" : value.trim();
        if (text.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return text.substring(7).trim();
        }
        return text;
    }

    /** 成功判定：code == 0 */
    private static void checkOk(Object json, String fallback) throws CloudException {
        long code = CloudHttp.lng(json, "code");
        if (code == 0L) {
            return;
        }
        String message = CloudHttp.str(json, "message");
        if (message.isBlank()) {
            message = fallback;
        }
        throw new CloudException(message + "（code=" + code + "）");
    }

    // ---------- 基址与工具 ----------

    private static String apiBase(CloudShare share) {
        String host = normalizedHost(share.host());
        return MIRROR_HOST.matcher(host).matches() ? "https://" + host : YUN_BASE;
    }

    private static String downloadBase(CloudShare share) {
        String host = normalizedHost(share.host());
        return MIRROR_HOST.matcher(host).matches() ? "https://" + host : DOWNLOAD_BASE;
    }

    private static String normalizedHost(String host) {
        String value = host == null ? "" : host.trim().toLowerCase(Locale.ROOT);
        int colon = value.indexOf(':');
        if (colon >= 0) {
            value = value.substring(0, colon);
        }
        return value;
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value.trim());
        } catch (Exception e) {
            return -1;
        }
    }

    private static String extraString(CloudFile file, String key) {
        Object value = file.extra().get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}
