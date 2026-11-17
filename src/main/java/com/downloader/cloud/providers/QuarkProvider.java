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
 * 夸克网盘分享解析。
 * <p>分享文件优先走「免转存取链」；服务端要求先转存时回退到个人网盘临时目录流程，
 * 并把转存后的 fid 与临时目录 fid 记在 {@link CloudFile#extra()} 中，后续重新取链直接复用。
 */
public final class QuarkProvider implements CloudProvider {

    private static final Pattern SHARE_PATTERN = Pattern.compile(
            "https?://(?:[A-Za-z0-9-]+\\.)?pan\\.quark\\.cn/s/([A-Za-z0-9]+)",
            Pattern.CASE_INSENSITIVE);

    private static final String API_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) quark-cloud-drive/2.5.20 Chrome/100.0.4896.160 "
                    + "Electron/18.3.5.12-a038f7b798 Safari/537.36 Channel/pckk_other_ch";
    private static final String API_BASE = "https://drive-pc.quark.cn";
    private static final String SHARE_TOKEN_URL =
            API_BASE + "/1/clouddrive/share/sharepage/token?pr=ucpro&fr=pc";
    private static final String SHARE_DETAIL_URL =
            API_BASE + "/1/clouddrive/share/sharepage/detail?pr=ucpro&fr=pc";
    private static final String DOWNLOAD_URL =
            API_BASE + "/1/clouddrive/file/download?pr=ucpro&fr=pc&sys=win32&ve=3.23.2";
    private static final String FILE_URL =
            API_BASE + "/1/clouddrive/file?pr=ucpro&fr=pc";
    private static final String SAVE_URL =
            API_BASE + "/1/clouddrive/share/sharepage/save?pr=ucpro&fr=pc";
    private static final String TASK_URL =
            API_BASE + "/1/clouddrive/task?pr=ucpro&fr=pc";
    private static final String DELETE_URL =
            API_BASE + "/1/clouddrive/file/delete?pr=ucpro&fr=pc&uc_param_str=";

    private static final String WEB_ORIGIN = "https://pan.quark.cn";
    private static final String DOWNLOAD_REFERER = "https://pan.quark.cn/";
    private static final String DEFAULT_PDIR_FID = "0";
    private static final String TEMP_DIR_NAME = "OmniFetch临时转存";
    private static final String TEMP_SUBDIR_PREFIX = "tr_";
    private static final int PAGE_SIZE = 50;
    private static final int MAX_PAGES = 200;

    /** 游客取链时服务端下发的 __pugs（3 小时有效，与本次直链同响应绑定） */
    private volatile String guestPugs = "";

    @Override
    public CloudDrive drive() {
        return CloudDrive.QUARK;
    }

    @Override
    public boolean matches(String text) {
        return text != null && SHARE_PATTERN.matcher(text).find();
    }

    @Override
    public CloudShare parse(String text) throws CloudException {
        if (text == null || text.isBlank()) {
            throw new CloudException("无法识别夸克网盘分享链接");
        }
        String url = CloudLinks.extractUrl(text);
        Matcher matcher = SHARE_PATTERN.matcher(url);
        boolean found = matcher.find();
        if (!found) {
            matcher = SHARE_PATTERN.matcher(text);
            found = matcher.find();
        }
        if (!found) {
            throw new CloudException("无法识别夸克网盘分享链接");
        }
        String shareKey = matcher.group(1);
        if (shareKey == null || shareKey.isBlank()) {
            throw new CloudException("夸克网盘分享链接缺少分享 ID");
        }
        String rawUrl = matcher.group();
        return new CloudShare(drive(), shareKey, passcodeOf(rawUrl, text),
                rawUrl, CloudLinks.hostOf(rawUrl));
    }

    @Override
    public List<CloudFile> list(CloudContext ctx, CloudShare share, CloudFile dir) throws Exception {
        // 夸克分享的凭证与目录接口允许匿名访问，登录只影响能否取直链
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

        String savedFid = stringExtra(file, "savedFid");
        if (!savedFid.isEmpty()) {
            try {
                return personalDownload(ctx, savedFid);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            } catch (Exception e) {
                // 之前转存的文件可能已被清理：清掉旧临时目录后重新取链
                cleanupTempDir(ctx, stringExtra(file, "tempDirFid"));
                file.extra().remove("savedFid");
                file.extra().remove("tempDirFid");
            }
        }

        // 复用列表阶段拿到的 stoken：share_fid_token 与 stoken 绑定，重新申请会破坏配对
        String stoken = currentStoken(ctx, share);
        String fidToken = stringExtra(file, "fidToken");
        if (fidToken.isEmpty()) {
            fidToken = recoverFidToken(ctx, share, file);
            // 补令牌时重新列目录会刷新 stoken，需同步取回以保证两者配对
            stoken = currentStoken(ctx, share);
        }

        // 未登录：走游客直链（夸克实测约 50MB 以内可用），失败时给出登录提示
        boolean guest = ctx.cookieText() == null || ctx.cookieText().isBlank();
        if (guest) {
            return guestShareDownload(ctx, share, file.id(), fidToken, stoken);
        }

        try {
            return shareDownload(ctx, share, file.id(), fidToken, stoken);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        } catch (Exception ignored) {
            // 个别分享类型服务端要求「先转存再取链」，继续走临时转存流程
        }

        String tempDir = ensureTempDir(ctx);
        String subDirName = TEMP_SUBDIR_PREFIX + System.nanoTime()
                + "_" + (int) (Math.random() * 1_000_000);
        String subDir = createFolder(ctx, subDirName, tempDir);
        try {
            String newFid = saveAndWait(ctx, share, file, subDir, stoken, fidToken);
            CloudLink link = personalDownload(ctx, newFid);
            file.put("savedFid", newFid);
            file.put("tempDirFid", subDir);
            return link;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        } catch (Exception e) {
            cleanupTempDir(ctx, subDir);
            throw e;
        }
    }

    // ─────────────────────────── 分享接口 ───────────────────────────

    private String fetchStoken(CloudContext ctx, CloudShare share) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("pwd_id", share.shareKey());
        body.put("passcode", share.passcode());
        body.put("support_visit_limit_private_share", true);
        Object data = call(ctx, "POST", SHARE_TOKEN_URL, MiniJson.write(body),
                CloudHttp.headers("User-Agent", API_USER_AGENT), "获取分享凭证");
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
        StringBuilder url = new StringBuilder(SHARE_DETAIL_URL);
        url.append("&pwd_id=").append(share.shareKey())
                .append("&stoken=").append(encode(stoken))
                .append("&pdir_fid=").append(encode(pdirFid))
                .append("&ver=2&force=0&_page=").append(page)
                .append("&_size=").append(PAGE_SIZE)
                .append("&_fetch_banner=0&_fetch_share=0")
                .append("&fetch_relate_conversation=0&_fetch_total=1")
                .append("&_sort=file_type:asc,file_name:asc");
        Object data = call(ctx, "GET", url.toString(), null,
                CloudHttp.headers("User-Agent", API_USER_AGENT,
                        "Origin", WEB_ORIGIN, "Referer", DOWNLOAD_REFERER),
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

    private CloudLink shareDownload(CloudContext ctx, CloudShare share,
                                    String fid, String fidToken, String stoken)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("fids", List.of(fid));
        body.put("fids_token", List.of(fidToken));
        body.put("pwd_id", share.shareKey());
        body.put("stoken", stoken);
        body.put("speedup_session", "");
        body.put("token", "");
        return requestDownload(ctx, MiniJson.write(body), false);
    }

    /** 游客态取链：同一接口、同一 body，只是不带账号 Cookie，改为回带响应下发的 __pugs */
    private CloudLink guestShareDownload(CloudContext ctx, CloudShare share,
                                        String fid, String fidToken, String stoken)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("fids", List.of(fid));
        body.put("fids_token", List.of(fidToken));
        body.put("pwd_id", share.shareKey());
        body.put("stoken", stoken);
        body.put("speedup_session", "");
        body.put("token", "");
        return requestDownload(ctx, MiniJson.write(body), true);
    }

    private CloudLink personalDownload(CloudContext ctx, String fid) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("fids", List.of(fid));
        return requestDownload(ctx, MiniJson.write(body), false);
    }

    private CloudLink requestDownload(CloudContext ctx, String body, boolean guest) throws Exception {
        Map<String, String> headers = CloudHttp.headers("User-Agent", API_USER_AGENT,
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
        // 游客 __pugs 与本次直链同响应绑定，必须用这一次下发的值
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
        if (CloudHttp.lng(root, "status") != 200 && code != 0) {
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
        headers.put("User-Agent", API_USER_AGENT);
        headers.put("Referer", DOWNLOAD_REFERER);
        if (cookie != null && !cookie.isBlank()) {
            headers.put("Cookie", cookie);
        }
        return headers;
    }

    private static String downloadError(int code, String message, int httpStatus) {
        return switch (code) {
            case 31001 -> "夸克登录态已失效，请重新填写夸克 Cookie";
            case 23018 -> "服务端按游客限制拒绝了该文件，请确认已登录夸克网盘";
            default -> {
                if (message != null && !message.isBlank()) {
                    yield message;
                }
                yield "获取下载链接失败（HTTP " + httpStatus + "）";
            }
        };
    }

    // ─────────────────────────── 个人网盘 / 转存 ───────────────────────────

    private String ensureTempDir(CloudContext ctx) throws Exception {
        String url = FILE_URL + "&pdir_fid=" + DEFAULT_PDIR_FID + "&page=1&size=100";
        Object data = call(ctx, "GET", url, null,
                CloudHttp.headers("User-Agent", API_USER_AGENT), "获取网盘目录");
        for (Object item : CloudHttp.list(data, "list")) {
            if (CloudHttp.bool(item, "dir")
                    && TEMP_DIR_NAME.equals(CloudHttp.str(item, "file_name"))) {
                String fid = CloudHttp.str(item, "fid");
                if (!fid.isBlank()) {
                    return fid;
                }
            }
        }
        return createFolder(ctx, TEMP_DIR_NAME, DEFAULT_PDIR_FID);
    }

    private String createFolder(CloudContext ctx, String name, String parentFid)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("pdir_fid", parentFid);
        body.put("file_name", name);
        body.put("dir_path", "");
        body.put("dir_init_lock", false);
        Object data = call(ctx, "POST", FILE_URL, MiniJson.write(body),
                CloudHttp.headers("User-Agent", API_USER_AGENT), "创建网盘目录");
        String fid = CloudHttp.str(data, "fid");
        if (fid.isBlank()) {
            throw new CloudException("创建临时转存目录失败：未返回目录 ID");
        }
        return fid;
    }

    private String saveAndWait(CloudContext ctx, CloudShare share, CloudFile file,
                               String toDirFid, String stoken, String fidToken)
            throws Exception {
        String fromPdir = stringExtra(file, "pdirFid");
        if (fromPdir.isEmpty()) {
            fromPdir = file.parentId().isBlank() ? DEFAULT_PDIR_FID : file.parentId();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("pwd_id", share.shareKey());
        body.put("stoken", stoken);
        body.put("pdir_fid", fromPdir);
        body.put("to_pdir_fid", toDirFid);
        body.put("fid_list", List.of(file.id()));
        body.put("fid_token_list", List.of(fidToken));
        body.put("scene", "link");
        Object data = call(ctx, "POST", SAVE_URL, MiniJson.write(body),
                CloudHttp.headers("User-Agent", API_USER_AGENT), "转存分享文件");
        String taskId = CloudHttp.str(data, "task_id");
        if (taskId.isBlank()) {
            throw new CloudException("转存失败：未返回任务 ID");
        }
        return waitTask(ctx, taskId);
    }

    private String waitTask(CloudContext ctx, String taskId) throws Exception {
        String url = TASK_URL + "&task_id=" + encode(taskId) + "&retry_index=0";
        for (int i = 0; i < 10; i++) {
            Object data = call(ctx, "GET", url, null,
                    CloudHttp.headers("User-Agent", API_USER_AGENT), "查询转存任务");
            long finishedAt = CloudHttp.lng(data, "finished_at");
            long status = CloudHttp.lng(data, "status");
            long taskStatus = CloudHttp.lng(data, "task_status");
            if (finishedAt > 0 || status == 2 || taskStatus == 2) {
                String savedFid = CloudHttp.str(data, "save_as", "save_as_top_fids", 0);
                if (!savedFid.isBlank()) {
                    return savedFid;
                }
            }
            Thread.sleep(1000L);
        }
        throw new CloudException("转存超时，请稍后重试");
    }

    private void cleanupTempDir(CloudContext ctx, String dirFid) {
        if (dirFid == null || dirFid.isBlank()) {
            return;
        }
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("action_type", 2);
            body.put("filelist", List.of(dirFid));
            body.put("exclude_fids", List.of());
            call(ctx, "POST", DELETE_URL, MiniJson.write(body),
                    CloudHttp.headers("User-Agent", API_USER_AGENT), "删除临时转存目录");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception ignored) {
            // 清理失败不阻断下载
        }
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
            case 23018 -> "该文件超出夸克游客可获取的大小上限（约 50MB），请填写夸克 Cookie 后再下载";
            case 31001 -> "该分享需要登录夸克网盘后才能下载，请填写夸克 Cookie";
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
