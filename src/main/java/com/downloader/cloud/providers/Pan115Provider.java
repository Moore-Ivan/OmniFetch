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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 115 网盘分享解析：{@code /share/snap} 列目录，{@code /share/downurl} 取直链。
 * <p>取分享直链需要分享者 {@code user_id}，它只在 snap 响应的 {@code userinfo} 里出现一次，
 * 因此列目录时把它同时写进 {@link CloudShare#extra()} 和每个 {@link CloudFile#extra()}，
 * 重新解析任务后仍能直接取链。
 */
public final class Pan115Provider implements CloudProvider {

    private static final String API_BASE = "https://webapi.115.com";
    private static final String SHARE_SNAP_URL = API_BASE + "/share/snap";
    private static final String SHARE_DOWNLOAD_URL = API_BASE + "/share/downurl";
    private static final String APP_VERSION_URL =
            "https://appversion.115.com/1/web/1.0/api/chrome";

    /** 分享页默认域名；参考实现固定用它拼 Referer */
    private static final String DEFAULT_SHARE_HOST = "115cdn.com";
    private static final String DOWNLOAD_REFERER = "https://115.com/";

    private static final String WEB_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Safari/537.36";
    private static final String X_REQUESTED_WITH = "XMLHttpRequest";

    private static final String ROOT_CID = "0";
    private static final int PAGE_LIMIT = 100;

    /** 客户端版本兜底；取链前会尝试从 appversion 接口刷新 */
    private static final String CLIENT_UA_FALLBACK = "Mozilla/5.0 115Browser/36.0.1";

    private static final Pattern SHARE_URL = Pattern.compile(
            "(?:115(?:cdn|rc)?\\.com|anxia\\.com)/s/([A-Za-z0-9]+)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SHARE_COMMAND = Pattern.compile(
            "(?:115(?:cdn|rc)?\\.com|anxia\\.com)/(sw[A-Za-z0-9]{8,})-([A-Za-z0-9]{4,8})",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern COOKIE_PATH = Pattern.compile(
            "[;\\s]path=([^;]+)", Pattern.CASE_INSENSITIVE);

    private volatile boolean versionFetched;
    private volatile String clientUa = CLIENT_UA_FALLBACK;

    @Override
    public CloudDrive drive() {
        return CloudDrive.PAN115;
    }

    @Override
    public boolean matches(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        return SHARE_URL.matcher(text).find() || SHARE_COMMAND.matcher(text).find();
    }

    @Override
    public CloudShare parse(String text) throws CloudException {
        String url = CloudLinks.extractUrl(text);
        String shareCode = "";
        String passcode = "";
        Matcher command = SHARE_COMMAND.matcher(url);
        if (command.find()) {
            shareCode = command.group(1);
            passcode = command.group(2);
        } else {
            Matcher share = SHARE_URL.matcher(url);
            if (!share.find()) {
                throw new CloudException("未在文本中找到 115 网盘分享链接");
            }
            shareCode = share.group(1);
            passcode = CloudLinks.extractPasscode(text);
        }
        if (shareCode.isBlank()) {
            throw new CloudException("115 分享链接中缺少分享编号");
        }
        String host = CloudLinks.hostOf(url);
        if (host.isBlank()) {
            host = DEFAULT_SHARE_HOST;
        }
        return new CloudShare(CloudDrive.PAN115, shareCode, passcode, url, host);
    }

    @Override
    public List<CloudFile> list(CloudContext ctx, CloudShare share, CloudFile dir) throws Exception {
        ctx.seedCookiesForHost(share.host());
        String cid = dir == null ? ROOT_CID : dir.id();
        if (cid.isBlank()) {
            cid = ROOT_CID;
        }
        String basePath = dir == null ? "/" : dir.displayPath();
        String referer = shareReferer(share);

        List<CloudFile> result = new ArrayList<>();
        int offset = 0;
        int fetched = 0;
        while (true) {
            String url = SHARE_SNAP_URL
                    + "?share_code=" + encode(share.shareKey())
                    + "&receive_code=" + encode(share.passcode())
                    + "&offset=" + offset
                    + "&limit=" + PAGE_LIMIT
                    + "&asc=0&cid=" + encode(cid)
                    + "&format=json";
            String body = ctx.http().getText(url, apiHeaders(referer));
            Object json = CloudHttp.json(body);
            checkState(json, "获取分享列表失败");

            Object dataNode = CloudHttp.at(json, "data");
            if (dataNode == null) {
                break;
            }
            Map<String, Object> data = CloudHttp.map(json, "data");

            String userId = CloudHttp.str(data, "userinfo", "user_id");
            if (!userId.isBlank()) {
                share.extra().put("userId", userId);
            }
            String title = CloudHttp.str(data, "shareinfo", "share_title");
            if (!title.isBlank()) {
                share.extra().put("title", title);
            }

            List<Object> items = CloudHttp.list(data, "list");
            for (Object entry : items) {
                if (!(entry instanceof Map<?, ?>)) {
                    continue;
                }
                Map<String, Object> item = CloudHttp.map(entry);
                String fid = CloudHttp.str(item, "fid");
                Object fcNode = item.get("fc");
                boolean folder = fcNode == null ? fid.isBlank() : CloudHttp.lng(item, "fc") == 0;
                String id = folder ? CloudHttp.str(item, "cid") : fid;
                if (id.isBlank()) {
                    continue;
                }
                long size = folder ? 0L : Math.max(0L, CloudHttp.lng(item, "s"));
                String parentId = folder
                        ? CloudHttp.str(item, "pid")
                        : CloudHttp.str(item, "cid");
                CloudFile file = new CloudFile(id, CloudHttp.str(item, "n"), size, folder)
                        .parentId(parentId)
                        .path(basePath);
                file.put("fid", fid);
                file.put("cid", CloudHttp.str(item, "cid"));
                if (!userId.isBlank()) {
                    file.put("userId", userId);
                }
                String pickCode = CloudHttp.str(item, "pc");
                if (!pickCode.isBlank()) {
                    file.put("pickcode", pickCode);
                }
                result.add(file);
            }

            offset += items.size();
            fetched += items.size();
            int total = (int) CloudHttp.lng(data, "count");
            boolean done = items.isEmpty()
                    || items.size() < PAGE_LIMIT
                    || (total > 0 && fetched >= total);
            if (done) {
                break;
            }
        }
        return result;
    }

    @Override
    public CloudLink resolve(CloudContext ctx, CloudShare share, CloudFile file) throws Exception {
        ctx.seedCookiesForHost(share.host());
        String userId = extraString(file, "userId");
        if (userId.isBlank()) {
            Object value = share.extra().get("userId");
            userId = value == null ? "" : String.valueOf(value).trim();
        }
        if (userId.isBlank()) {
            throw new CloudException("缺少分享者标识，请重新解析 115 分享链接");
        }

        String userAgent = clientUserAgent(ctx);
        String url = SHARE_DOWNLOAD_URL
                + "?dl=1&user_id=" + encode(userId)
                + "&share_code=" + encode(share.shareKey())
                + "&file_id=" + encode(file.id());
        if (!share.passcode().isBlank()) {
            url += "&receive_code=" + encode(share.passcode());
        }

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("User-Agent", userAgent);
        headers.put("Accept", "application/json, text/plain, */*");
        headers.put("X-Requested-With", X_REQUESTED_WITH);
        headers.put("Referer", shareReferer(share));

        HttpResponse<String> response = ctx.http().send("GET", url, headers, null, true);
        String body = response.body();
        if (body == null || body.isBlank()) {
            throw new CloudException("获取下载链接失败：响应为空（HTTP " + response.statusCode() + "）");
        }
        Object json = CloudHttp.json(body);
        checkState(json, "获取下载链接失败");

        Map<String, Object> data = CloudHttp.map(json, "data");
        String fileUrl = CloudHttp.str(data, "file_url_302");
        if (fileUrl.isBlank()) {
            fileUrl = CloudHttp.str(data, "file_url");
        }
        if (fileUrl.isBlank()) {
            fileUrl = CloudHttp.str(data, "url", "url");
        }
        if (fileUrl.isBlank()) {
            throw new CloudException("获取下载链接失败：115 未返回直链（大文件可能只向客户端下发）");
        }

        String cdnCookie = downloadCookie(response.headers().allValues("Set-Cookie"));
        String cookie = mergeCookie(ctx.cookieText(), cdnCookie);
        Map<String, String> linkHeaders = new LinkedHashMap<>();
        linkHeaders.put("Referer", DOWNLOAD_REFERER);
        linkHeaders.put("User-Agent", userAgent);
        linkHeaders.put("Accept", "*/*");
        if (!cookie.isBlank()) {
            linkHeaders.put("Cookie", cookie);
        }
        return new CloudLink(fileUrl, linkHeaders);
    }

    /** 拉取一次最新客户端版本；失败保留兜底值，不影响取链 */
    private String clientUserAgent(CloudContext ctx) {
        if (!versionFetched) {
            synchronized (this) {
                if (!versionFetched) {
                    versionFetched = true;
                    try {
                        String body = ctx.http().getText(APP_VERSION_URL,
                                CloudHttp.headers("User-Agent", WEB_UA,
                                        "Referer", DOWNLOAD_REFERER));
                        String version = CloudHttp.str(CloudHttp.json(body),
                                "data", "win", "version_code");
                        if (isValidVersion(version)) {
                            clientUa = "Mozilla/5.0 115Browser/" + version;
                        }
                    } catch (Exception ignored) {
                        // 版本接口不可用时按兜底 UA 继续
                    }
                }
            }
        }
        return clientUa;
    }

    private static boolean isValidVersion(String version) {
        if (version == null || version.isBlank() || version.length() > 16
                || !Character.isDigit(version.charAt(0))) {
            return false;
        }
        for (int i = 0; i < version.length(); i++) {
            char c = version.charAt(i);
            if (!Character.isDigit(c) && c != '.') {
                return false;
            }
        }
        return true;
    }

    private static Map<String, String> apiHeaders(String referer) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("User-Agent", WEB_UA);
        headers.put("Accept", "application/json, text/plain, */*");
        headers.put("X-Requested-With", X_REQUESTED_WITH);
        headers.put("Referer", referer);
        return headers;
    }

    private static String shareReferer(CloudShare share) {
        String host = share.host();
        if (!isShareHost(host)) {
            host = DEFAULT_SHARE_HOST;
        }
        String referer = "https://" + host + "/s/" + share.shareKey();
        if (!share.passcode().isBlank()) {
            referer += "?password=" + encode(share.passcode()) + "&";
        }
        return referer;
    }

    private static boolean isShareHost(String host) {
        String value = host == null ? "" : host.toLowerCase(Locale.ROOT);
        return value.equals("115.com") || value.equals("115cdn.com") || value.equals("115rc.com")
                || value.equals("anxia.com")
                || value.endsWith(".115.com") || value.endsWith(".115cdn.com")
                || value.endsWith(".115rc.com") || value.endsWith(".anxia.com");
    }

    /** 成功判定：state=true；失败按 errno 转成中文提示 */
    private static void checkState(Object json, String fallback) throws CloudException {
        if (CloudHttp.bool(json, "state")) {
            return;
        }
        int errno = (int) CloudHttp.lng(json, "errno");
        if (errno < 0) {
            errno = (int) CloudHttp.lng(json, "errNo");
        }
        if (errno < 0) {
            errno = 0;
        }
        String raw = CloudHttp.str(json, "error");
        if (raw.isBlank()) {
            raw = CloudHttp.str(json, "msg");
        }
        String message = switch (errno) {
            case 990001 -> "登录已过期，请重新登录 115 网盘";
            case 4100012 -> "该分享需要访问码，请填写访问码后重试";
            case 4100008 -> "访问码错误，请检查后重试";
            case 4100026 -> "分享链接不存在或已被删除";
            case 4100016 -> "不允许分享空文件夹";
            case 990009 -> "上一次操作还没完成，请稍后重试";
            case 50028 -> "115 拒绝向网页端下发该文件的直链（文件过大）";
            case 50029 -> "115 判定当前客户端版本过低，拒绝下发直链（大文件需登录后用 115 客户端下载）";
            default -> raw.isBlank() ? fallback : raw;
        };
        String suffix = errno > 0 && !message.contains(String.valueOf(errno))
                ? "（errno=" + errno + "）" : "";
        throw new CloudException(message + suffix);
    }

    /** 取响应头里 900 秒有效的下载专用 Cookie（优先 path 绑定的那条） */
    private static String downloadCookie(List<String> setCookies) {
        String fallback = null;
        for (String raw : setCookies) {
            String pair = raw;
            int semicolon = pair.indexOf(';');
            if (semicolon >= 0) {
                pair = pair.substring(0, semicolon);
            }
            pair = pair.trim();
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String name = pair.substring(0, eq).trim().toLowerCase(Locale.ROOT);
            if ("expires".equals(name) || "path".equals(name) || "domain".equals(name)
                    || "max-age".equals(name) || "secure".equals(name)
                    || "httponly".equals(name) || "samesite".equals(name)) {
                continue;
            }
            if (fallback == null) {
                fallback = pair;
            }
            Matcher matcher = COOKIE_PATH.matcher(raw);
            if (matcher.find()) {
                String path = matcher.group(1).trim();
                if (!path.isBlank() && !"/".equals(path)) {
                    return pair;
                }
            }
        }
        return fallback;
    }

    private static String mergeCookie(String... parts) {
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            if (part == null || part.isBlank()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append("; ");
            }
            builder.append(part.trim());
        }
        return builder.toString();
    }

    private static String extraString(CloudFile file, String key) {
        Object value = file.extra().get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}
