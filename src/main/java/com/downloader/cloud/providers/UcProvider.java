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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * UC 网盘分享解析。
 * <p>UC 的官方下载流程不需要转存：文件列表走 transfer_share/detail（带 stoken），
 * 取链时把分享 fid / fid_token / pwd_id / stoken 直接交给 file/download。
 */
public final class UcProvider implements CloudProvider {

    private static final Pattern SHARE_PATTERN = Pattern.compile(
            "https?://(?:[A-Za-z0-9-]+\\.)?drive\\.uc\\.cn/s/([A-Za-z0-9]+)",
            Pattern.CASE_INSENSITIVE);

    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    private static final String API_BASE = "https://pc-api.uc.cn";
    private static final String SHARE_TOKEN_URL =
            API_BASE + "/1/clouddrive/share/sharepage/token?pr=UCBrowser&fr=pc";
    private static final String TRANSFER_DETAIL_URL =
            API_BASE + "/1/clouddrive/transfer_share/detail?entry=ft&fr=pc&pr=UCBrowser";
    private static final String DOWNLOAD_URL =
            API_BASE + "/1/clouddrive/file/download?entry=ft&fr=pc&pr=UCBrowser";

    private static final String WEB_ORIGIN = "https://drive.uc.cn";
    private static final String DOWNLOAD_REFERER = "https://drive.uc.cn/";
    private static final String TRANSFER_ORIGIN = "https://fast.uc.cn";
    private static final String DEFAULT_PDIR_FID = "0";
    private static final int PAGE_SIZE = 50;
    private static final int MAX_PAGES = 200;

    /** 游客取链时服务端下发的 __pugs（3 小时有效，且与本次直链同响应绑定） */
    private volatile String guestPugs = "";

    @Override
    public CloudDrive drive() {
        return CloudDrive.UC;
    }

    @Override
    public boolean matches(String text) {
        return text != null && SHARE_PATTERN.matcher(text).find();
    }

    @Override
    public CloudShare parse(String text) throws CloudException {
        if (text == null || text.isBlank()) {
            throw new CloudException("无法识别 UC 网盘分享链接");
        }
        String url = CloudLinks.extractUrl(text);
        Matcher matcher = SHARE_PATTERN.matcher(url);
        boolean found = matcher.find();
        if (!found) {
            matcher = SHARE_PATTERN.matcher(text);
            found = matcher.find();
        }
        if (!found) {
            throw new CloudException("无法识别 UC 网盘分享链接");
        }
        String shareKey = matcher.group(1);
        if (shareKey == null || shareKey.isBlank()) {
            throw new CloudException("UC 网盘分享链接缺少分享 ID");
        }
        String rawUrl = matcher.group();
        return new CloudShare(drive(), shareKey, passcodeOf(rawUrl, text),
                rawUrl, CloudLinks.hostOf(rawUrl));
    }

    @Override
    public List<CloudFile> list(CloudContext ctx, CloudShare share, CloudFile dir) throws Exception {
        // UC 分享的凭证与目录接口允许匿名访问，登录与否只影响取直链
        ctx.seedCookiesForHost(share.host());

        String stoken = fetchStoken(ctx, share);
        String pdirFid = (dir == null || dir.id().isBlank()) ? DEFAULT_PDIR_FID : dir.id();
        String parentId = dir == null ? DEFAULT_PDIR_FID : pdirFid;
        String basePath = dir == null ? "/" : dir.displayPath();

        Map<String, CloudFile> merged = new LinkedHashMap<>();
        for (int page = 1; page <= MAX_PAGES; page++) {
            int before = merged.size();
            for (CloudFile item : fetchSharePage(ctx, share, stoken, pdirFid, page)) {
                item.parentId(parentId).path(basePath);
                String key = item.id().isEmpty()
                        ? parentId + "/" + item.name()
                        : item.id();
                merged.putIfAbsent(key, item);
            }
            if (merged.size() == before) {
                break;
            }
        }
        return new ArrayList<>(merged.values());
    }

    @Override
    public CloudLink resolve(CloudContext ctx, CloudShare share, CloudFile file) throws Exception {
        if (file.folder()) {
            throw new CloudException("目录不能直接下载，请选择具体文件");
        }
        ctx.seedCookiesForHost(share.host());

        // 复用列表阶段拿到的 stoken：share_fid_token 与 stoken 绑定，重新申请会破坏配对
        String stoken = currentStoken(ctx, share);
        String fidToken = stringExtra(file, "fidToken");
        if (fidToken.isEmpty()) {
            fidToken = recoverFidToken(ctx, share, file);
            // 补令牌时重新列目录会刷新 stoken，需同步取回以保证两者配对
            stoken = currentStoken(ctx, share);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("fids", List.of(file.id()));
        body.put("pwd_id", share.shareKey());
        body.put("stoken", stoken);
        body.put("fids_token", List.of(fidToken));
        boolean guest = ctx.cookieText() == null || ctx.cookieText().isBlank();
        return requestDownload(ctx, MiniJson.write(body), guest);
    }

    // ─────────────────────────── 分享接口 ───────────────────────────

    private String fetchStoken(CloudContext ctx, CloudShare share) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("pwd_id", share.shareKey());
        body.put("passcode", share.passcode());
        body.put("share_for_transfer", true);
        Object data = call(ctx, "POST", SHARE_TOKEN_URL, MiniJson.write(body),
                CloudHttp.headers("User-Agent", USER_AGENT), "获取分享凭证");
        String stoken = CloudHttp.str(data, "stoken");
        if (stoken.isBlank()) {
            throw new CloudException("未获取到分享凭证（stoken 为空），请确认链接和提取码");
        }
        share.extra().put("stoken", stoken);
        String title = CloudHttp.str(data, "title");
        if (!title.isBlank()) {
            share.extra().put("title", title);
        }
        return stoken;
    }

    private String currentStoken(CloudContext ctx, CloudShare share) throws Exception {
        String stoken = share.extra().get("stoken");
        if (stoken != null && !stoken.isBlank()) {
            return stoken;
        }
        return fetchStoken(ctx, share);
    }

    private List<CloudFile> fetchSharePage(CloudContext ctx, CloudShare share,
                                           String stoken, String pdirFid, int page)
            throws Exception {
        StringBuilder url = new StringBuilder(TRANSFER_DETAIL_URL);
        url.append("&pwd_id=").append(share.shareKey())
                .append("&pdir_fid=").append(encode(pdirFid))
                .append("&fetch_file_list=1&passcode=&_page=").append(page)
                .append("&_size=").append(PAGE_SIZE)
                .append("&_fetch_total=1&_fetch_task=1&_fetch_share=1&_sort=")
                .append("&stoken=").append(encode(stoken));
        Object data = call(ctx, "GET", url.toString(), null,
                CloudHttp.headers("User-Agent", USER_AGENT,
                        "Origin", TRANSFER_ORIGIN, "Referer", TRANSFER_ORIGIN + "/"),
                "获取分享文件列表");
        List<Object> items = CloudHttp.list(data, "list");
        if (items.isEmpty()) {
            items = CloudHttp.list(data, "detail_info", "list");
        }
        List<CloudFile> files = new ArrayList<>(items.size());
        for (Object item : items) {
            String fid = CloudHttp.str(item, "fid");
            String name = CloudHttp.str(item, "file_name");
            if (name.isBlank()) {
                name = CloudHttp.str(item, "fname");
            }
            boolean folder = CloudHttp.bool(item, "dir");
            long size = CloudHttp.lng(item, "size");
            CloudFile file = new CloudFile(fid, name, size, folder);
            file.put("fidToken", CloudHttp.str(item, "share_fid_token"));
            String itemPdir = CloudHttp.str(item, "pdir_fid");
            if (!itemPdir.isBlank()) {
                file.put("pdirFid", itemPdir);
            }
            files.add(file);
        }
        return files;
    }

    /** 重新列父目录，为持久化任务中缺失 fidToken 的文件补齐令牌 */
    private String recoverFidToken(CloudContext ctx, CloudShare share, CloudFile file)
            throws Exception {
        try {
            CloudFile parent = new CloudFile(
                    file.parentId().isBlank() ? DEFAULT_PDIR_FID : file.parentId(),
                    "", 0, true).path(file.path());
            for (CloudFile sibling : list(ctx, share, parent)) {
                if (sibling.id().equals(file.id())) {
                    String token = stringExtra(sibling, "fidToken");
                    if (!token.isEmpty()) {
                        file.put("fidToken", token);
                        return token;
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        } catch (Exception ignored) {
            // 列出目录失败时交给上层给出可读提示
        }
        throw new CloudException("文件下载令牌缺失，请重新解析分享后再下载");
    }

    // ─────────────────────────── 下载直链 ───────────────────────────

    private CloudLink requestDownload(CloudContext ctx, String body, boolean guest) throws Exception {
        Map<String, String> headers = CloudHttp.headers("User-Agent", USER_AGENT,
                "Content-Type", "application/json");
        if (guest) {
            headers.put("Origin", WEB_ORIGIN);
            headers.put("Referer", DOWNLOAD_REFERER);
            if (!guestPugs.isBlank()) {
                headers.put("Cookie", guestPugs);
            }
        }
        HttpResponse<String> response = ctx.http().send("POST", DOWNLOAD_URL, headers, body, true);
        int httpStatus = response.statusCode();
        String text = response.body();
        if (text == null || text.isEmpty()) {
            throw new CloudException("获取下载链接失败：响应为空");
        }
        List<String> setCookies = response.headers().allValues("Set-Cookie");
        String responsePugs = pugsFromSetCookies(setCookies);
        if (guest && !responsePugs.isBlank()) {
            guestPugs = responsePugs;
        }
        String cookie = guest ? guestPugs : mergeTrackedCookies(ctx.cookieText(), setCookies);
        Object root;
        try {
            root = CloudHttp.json(text);
        } catch (CloudException e) {
            throw new CloudException("下载接口响应解析失败（HTTP " + httpStatus + "）", e);
        }
        long code = CloudHttp.lng(root, "code");
        if (CloudHttp.lng(root, "status") != 200) {
            throw new CloudException(guest
                    ? guestDownloadError((int) code, CloudHttp.str(root, "message"), httpStatus)
                    : downloadError((int) code, CloudHttp.str(root, "message"), httpStatus));
        }
        List<Object> data = CloudHttp.list(root, "data");
        if (data.isEmpty()) {
            throw new CloudException("未返回下载链接");
        }
        String url = CloudHttp.str(data.get(0), "download_url");
        if (url.isBlank()) {
            throw new CloudException("未返回下载链接");
        }
        return new CloudLink(url, downloadHeaders(cookie));
    }

    private static Map<String, String> downloadHeaders(String cookie) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("User-Agent", USER_AGENT);
        headers.put("Referer", DOWNLOAD_REFERER);
        headers.put("Origin", WEB_ORIGIN);
        if (cookie != null && !cookie.isBlank()) {
            headers.put("Cookie", cookie);
        }
        return headers;
    }

    private static String downloadError(int code, String message, int httpStatus) {
        return switch (code) {
            case 31001 -> "UC 登录态已失效，请重新填写 UC 网盘 Cookie";
            case 23018 -> "该文件超出游客可获取的大小上限，请登录 UC 网盘后再下载";
            case 14001 -> "分享已失效或提取码有误，请重新解析";
            case 41020 -> "文件令牌已过期，请重新解析分享";
            default -> {
                if (message != null && !message.isBlank()) {
                    yield message;
                }
                yield "获取下载链接失败（HTTP " + httpStatus + "）";
            }
        };
    }

    // ─────────────────────────── 通用 ───────────────────────────

    private Object call(CloudContext ctx, String method, String url, String body,
                        Map<String, String> headers, String action) throws Exception {
        HttpResponse<String> response = ctx.http().send(method, url, headers, body, true);
        String text = response.body();
        if (text == null || text.isEmpty()) {
            throw new CloudException(action + "失败：响应为空");
        }
        Object root;
        try {
            root = CloudHttp.json(text);
        } catch (CloudException e) {
            throw new CloudException("响应解析失败（HTTP " + response.statusCode() + "）", e);
        }
        if (CloudHttp.lng(root, "status") != 200) {
            String message = CloudHttp.str(root, "message");
            throw new CloudException(message.isBlank()
                    ? action + "失败（HTTP " + response.statusCode() + "）"
                    : message);
        }
        Object data = CloudHttp.at(root, "data");
        if (data == null) {
            throw new CloudException("响应缺少 data");
        }
        return data;
    }

    private static String guestDownloadError(int code, String message, int httpStatus) {
        return switch (code) {
            case 31001 -> "该分享需要登录 UC 网盘后才能下载，请填写 UC 网盘 Cookie";
            case 23018 -> "该文件超出 UC 游客可获取的大小上限，请填写 UC 网盘 Cookie 后再下载";
            case 14001 -> "分享已失效或提取码有误，请重新解析";
            case 41020 -> "文件令牌已过期，请重新解析分享";
            default -> {
                if (message != null && !message.isBlank()) {
                    yield message;
                }
                yield "获取下载链接失败（HTTP " + httpStatus + "）";
            }
        };
    }

    /** 从响应 Set-Cookie 中取出游客态 __pugs，返回值可直接作为 Cookie 头 */
    private static String pugsFromSetCookies(List<String> setCookies) {
        if (setCookies == null) {
            return "";
        }
        for (String setCookie : setCookies) {
            if (setCookie == null) {
                continue;
            }
            int semicolon = setCookie.indexOf(';');
            String pair = (semicolon >= 0 ? setCookie.substring(0, semicolon) : setCookie).trim();
            if (pair.startsWith("__pugs=") && pair.length() > "__pugs=".length()) {
                return pair;
            }
        }
        return "";
    }

    private static String passcodeOf(String url, String text) {
        Map<String, String> query = CloudLinks.queryOf(url);
        for (String key : new String[]{"pwd", "password", "passcode", "code"}) {
            String value = query.get(key);
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return CloudLinks.extractPasscode(text);
    }

    private static String stringExtra(CloudFile file, String key) {
        Object value = file.extra().get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static String mergeTrackedCookies(String original, List<String> setCookies) {
        String cookie = original == null ? "" : original;
        if (setCookies == null || setCookies.isEmpty()) {
            return cookie;
        }
        for (String setCookie : setCookies) {
            if (setCookie == null) {
                continue;
            }
            int semicolon = setCookie.indexOf(';');
            String pair = (semicolon >= 0 ? setCookie.substring(0, semicolon) : setCookie).trim();
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String name = pair.substring(0, eq).trim();
            if (!"__puus".equals(name) && !"__pus".equals(name)) {
                continue;
            }
            cookie = replaceCookie(cookie, name, pair.substring(eq + 1).trim());
        }
        return cookie;
    }

    private static String replaceCookie(String cookie, String name, String value) {
        List<String> parts = new ArrayList<>();
        if (cookie != null && !cookie.isBlank()) {
            for (String part : cookie.split(";")) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty() && !trimmed.startsWith(name + "=")) {
                    parts.add(trimmed);
                }
            }
        }
        parts.add(name + "=" + value);
        return String.join("; ", parts);
    }
}
