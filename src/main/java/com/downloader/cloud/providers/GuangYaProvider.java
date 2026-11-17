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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 光鸭云盘分享解析（公开分享免登录）。
 * <p>流程与官方网页一致：用分享 ID + 提取码换分享访问令牌，再分页列目录；
 * 取直链时把分享访问令牌一并提交。业务接口必须带 did / dt / traceparent 请求头。
 */
public final class GuangYaProvider implements CloudProvider {

    private static final String API_BASE = "https://api.guangyapan.com";
    private static final String WEB_SITE = "https://www.guangyapan.com";
    private static final String DOWNLOAD_REFERER = WEB_SITE + "/";
    private static final String WEB_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

    private static final String SHARE_ACCESS_TOKEN = API_BASE + "/userres/v1/get_share_access_token";
    private static final String SHARE_FILES = API_BASE + "/userres/v1/get_share_page_files_list";
    private static final String SHARE_DOWNLOAD = API_BASE + "/userres/v1/get_share_download_url";
    private static final String RESTORE_SHARE = API_BASE + "/userres/v1/restore_share";
    private static final String FILE_LIST = API_BASE + "/userres/v1/file/get_file_list";
    private static final String CREATE_DIR = API_BASE + "/userres/v1/file/create_dir";
    private static final String RES_DOWNLOAD = API_BASE + "/userres/v1/get_res_download_url";
    private static final String DELETE_FILE = API_BASE + "/userres/v1/file/delete_file";

    private static final String TEMP_DIR_PREFIX = "OmniFetch临时转存_";

    private static final int PAGE_SIZE = 100;
    private static final int MAX_PAGES = 100;

    /** 分享访问令牌在 CloudShare.extra() 中的键名 */
    private static final String EXTRA_TOKEN = "gyAccessToken";

    private static final Pattern SHARE_PATTERN =
            Pattern.compile("guangyapan\\.com/s/([A-Za-z0-9_-]+)", Pattern.CASE_INSENSITIVE);

    /** 匿名业务请求所需的设备标识（进程内复用） */
    private final String deviceId = randomHex(32);

    @Override
    public CloudDrive drive() {
        return CloudDrive.GUANGYA;
    }

    @Override
    public boolean matches(String text) {
        return text != null && SHARE_PATTERN.matcher(text).find();
    }

    @Override
    public CloudShare parse(String text) throws CloudException {
        String url = CloudLinks.extractUrl(text);
        Matcher m = SHARE_PATTERN.matcher(url);
        if (!m.find()) {
            throw new CloudException("无法识别光鸭云盘分享链接（应形如 https://www.guangyapan.com/s/xxxx）");
        }
        String shareId = m.group(1);
        String passcode = CloudLinks.extractPasscode(text);
        return new CloudShare(CloudDrive.GUANGYA, shareId, passcode, url, CloudLinks.hostOf(url));
    }

    @Override
    public List<CloudFile> list(CloudContext ctx, CloudShare share, CloudFile dir) throws Exception {
        String accessToken = ensureShareToken(ctx, share);
        String parentId = dir == null ? "" : dir.id();
        String basePath = dir == null ? "/" : dir.displayPath();

        List<CloudFile> result = new ArrayList<>();
        String cursor = "";
        boolean hasMore = false;
        int pages = 0;
        do {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("parentId", parentId);
            body.put("pageSize", (long) PAGE_SIZE);
            body.put("orderBy", 0L);
            body.put("sortType", 0L);
            body.put("accessToken", accessToken);
            if (!cursor.isBlank()) {
                body.put("cursor", cursor);
            }
            Object json = postBusiness(ctx, SHARE_FILES, body, null);
            checkBusiness(json, "获取光鸭分享文件列表失败");
            Map<String, Object> data = dataOf(json);
            for (Object item : listArray(data)) {
                CloudFile file = parseFile(item);
                if (file == null) {
                    continue;
                }
                file.parentId(parentId).path(basePath);
                result.add(file);
            }
            boolean more = CloudHttp.bool(data, "hasMore");
            hasMore = more;
            cursor = CloudHttp.str(data, "cursor");
            pages++;
        } while (hasMore && !cursor.isBlank() && pages < MAX_PAGES);
        return result;
    }

    @Override
    public CloudLink resolve(CloudContext ctx, CloudShare share, CloudFile file) throws Exception {
        String accessToken = ensureShareToken(ctx, share);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("fileId", file.id());
        body.put("accessToken", accessToken);
        Object json = postBusiness(ctx, SHARE_DOWNLOAD, body, null);
        if ("207".equals(CloudHttp.str(json, "code"))) {
            // 分享者未开启免登录下载：已登录则转存到临时目录，再从个人盘取链
            return transferAndDownload(ctx, file, accessToken);
        }
        checkBusiness(json, "获取光鸭下载地址失败");
        Map<String, Object> data = dataOf(json);
        String url = downloadUrlOf(data);
        if (url.isBlank()) {
            throw new CloudException("光鸭未返回有效下载地址，请确认该分享的下载权限");
        }
        return new CloudLink(url, downloadHeaders());
    }

    /** 受限分享的兜底：建临时目录 → 转存 → 轮询目标文件 → 个人盘取链 → 清理临时目录 */
    private CloudLink transferAndDownload(CloudContext ctx, CloudFile file,
                                          String shareToken) throws Exception {
        String account = accountToken(ctx);
        if (account.isBlank()) {
            throw new CloudException("该光鸭分享未开启免登录下载，请填写光鸭登录凭据（access_token）后再下载");
        }
        String folderId = createDir(ctx, account,
                TEMP_DIR_PREFIX + UUID.randomUUID().toString().replace("-", ""));
        try {
            restoreShare(ctx, account, shareToken, file.id(), folderId);
            CloudFile target = pollCopiedFile(ctx, account, folderId, file);
            return personalDownload(ctx, account, target.id());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            deleteFilesQuietly(ctx, account, folderId);
            throw e;
        } catch (Exception e) {
            deleteFilesQuietly(ctx, account, folderId);
            throw e;
        }
    }

    private String createDir(CloudContext ctx, String token, String name) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("parentId", "");
        body.put("dirName", name);
        body.put("failIfNameExist", true);
        Object json = postBusiness(ctx, CREATE_DIR, body, token);
        checkBusiness(json, "光鸭创建临时目录失败");
        Map<String, Object> data = dataOf(json);
        String id = firstNonBlank(CloudHttp.str(data, "fileId"), CloudHttp.str(data, "id"));
        if (id.isBlank()) {
            throw new CloudException("光鸭创建临时目录失败：未返回目录 ID");
        }
        return id;
    }

    private void restoreShare(CloudContext ctx, String account, String shareToken,
                              String fileId, String folderId) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("accessToken", shareToken);
        body.put("fileIds", List.of(fileId));
        body.put("parentId", folderId);
        Object json = postBusiness(ctx, RESTORE_SHARE, body, account);
        checkBusiness(json, "光鸭转存分享文件失败");
    }

    /** 轮询临时目录直到目标文件出现（同名但大小不一致时视为异常，避免下错文件） */
    private CloudFile pollCopiedFile(CloudContext ctx, String account, String folderId,
                                     CloudFile source) throws Exception {
        for (int i = 0; i < 30; i++) {
            Thread.sleep(1000L);
            for (CloudFile candidate : listCloudFiles(ctx, account, folderId)) {
                if (candidate.folder() || !source.name().equals(candidate.name())) {
                    continue;
                }
                if (source.size() > 0 && candidate.size() > 0
                        && source.size() != candidate.size()) {
                    throw new CloudException("光鸭转存文件大小不一致，无法确定下载目标");
                }
                return candidate;
            }
        }
        throw new CloudException("光鸭转存已提交，目标文件暂不可见，请稍后重试");
    }

    private List<CloudFile> listCloudFiles(CloudContext ctx, String account, String parentId)
            throws Exception {
        List<CloudFile> result = new ArrayList<>();
        for (int page = 0; page < MAX_PAGES; page++) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("parentId", parentId);
            body.put("pageSize", (long) PAGE_SIZE);
            body.put("orderBy", 0L);
            body.put("sortType", 0L);
            body.put("page", (long) page);
            Object json = postBusiness(ctx, FILE_LIST, body, account);
            checkBusiness(json, "获取光鸭网盘文件列表失败");
            Map<String, Object> data = dataOf(json);
            List<Object> items = listArray(data);
            for (Object item : items) {
                CloudFile parsed = parseFile(item);
                if (parsed != null) {
                    result.add(parsed);
                }
            }
            if (items.size() < PAGE_SIZE) {
                break;
            }
        }
        return result;
    }

    private CloudLink personalDownload(CloudContext ctx, String account, String fileId)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("fileId", fileId);
        Object json = postBusiness(ctx, RES_DOWNLOAD, body, account);
        checkBusiness(json, "获取光鸭下载地址失败");
        String url = downloadUrlOf(dataOf(json));
        if (url.isBlank()) {
            throw new CloudException("光鸭未返回有效下载地址，请确认该分享的下载权限");
        }
        return new CloudLink(url, downloadHeaders());
    }

    private void deleteFilesQuietly(CloudContext ctx, String account, String folderId) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("fileIds", List.of(folderId));
            Object json = postBusiness(ctx, DELETE_FILE, body, account);
            checkBusiness(json, "删除光鸭临时目录失败");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception ignored) {
            // 清理失败不阻断下载
        }
    }

    private static String downloadUrlOf(Map<String, Object> data) {
        return firstNonBlank(
                CloudHttp.str(data, "signedURL"), CloudHttp.str(data, "signedUrl"),
                CloudHttp.str(data, "downloadUrl"), CloudHttp.str(data, "download_url"),
                CloudHttp.str(data, "url"), CloudHttp.str(data, "cdnUrl"));
    }

    private static Map<String, String> downloadHeaders() {
        return CloudHttp.headers(
                "User-Agent", WEB_UA,
                "Referer", DOWNLOAD_REFERER,
                "Accept", "*/*");
    }

    /** 从填写的凭据里取出光鸭账号 access token：支持 name=value、整段 JSON、或直接粘贴 token */
    private static String accountToken(CloudContext ctx) {
        String text = ctx.cookieText();
        if (text == null) {
            return "";
        }
        text = text.trim();
        if (text.isEmpty()) {
            return "";
        }
        if (text.startsWith("{")) {
            try {
                Map<String, Object> json = CloudHttp.map(CloudHttp.json(text));
                for (String key : new String[]{"access_token", "accessToken", "token"}) {
                    String value = CloudHttp.str(json, key);
                    if (!value.isBlank()) {
                        return stripBearer(value);
                    }
                }
            } catch (CloudException ignored) {
                return "";
            }
            return "";
        }
        if (text.indexOf('=') < 0) {
            return stripBearer(text);
        }
        for (String key : new String[]{"access_token", "accessToken", "gy_token", "token"}) {
            for (String part : text.split(";")) {
                String pair = part.trim();
                if (pair.startsWith(key + "=")) {
                    String value = pair.substring(key.length() + 1).trim();
                    if (!value.isEmpty()) {
                        return stripBearer(value);
                    }
                }
            }
        }
        return "";
    }

    private static String stripBearer(String value) {
        String text = value == null ? "" : value.trim();
        return text.regionMatches(true, 0, "Bearer ", 0, 7)
                ? text.substring(7).trim() : text;
    }

    /** 用分享 ID + 提取码换取访问令牌；结果缓存在 share.extra() 供分页与取链复用。 */
    private String ensureShareToken(CloudContext ctx, CloudShare share) throws Exception {
        String cached = share.extra().get(EXTRA_TOKEN);
        if (cached != null && !cached.isBlank()) {
            return cached;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("shareId", share.shareKey());
        body.put("code", share.passcode());
        Object json = postBusiness(ctx, SHARE_ACCESS_TOKEN, body, null);
        checkBusiness(json, "获取光鸭分享访问令牌失败");
        Map<String, Object> data = dataOf(json);
        String token = firstNonBlank(CloudHttp.str(data, "accessToken"),
                CloudHttp.str(data, "access_token"));
        if (token.isBlank()) {
            throw new CloudException("光鸭分享链接无效或提取码错误");
        }
        share.extra().put(EXTRA_TOKEN, token);
        return token;
    }

    private Object postBusiness(CloudContext ctx, String url, Map<String, Object> body,
                                String token) throws Exception {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", "application/json, text/plain, */*");
        headers.put("Content-Type", "application/json");
        headers.put("User-Agent", WEB_UA);
        headers.put("Origin", WEB_SITE);
        headers.put("Referer", DOWNLOAD_REFERER);
        headers.put("dt", "4");
        headers.put("traceparent", newTraceparent());
        headers.put("did", deviceId);
        if (token != null && !token.isBlank()) {
            headers.put("Authorization", "Bearer " + token);
        }
        String text = ctx.http().postJson(url, MiniJson.write(body), headers);
        return CloudHttp.json(text);
    }

    @SuppressWarnings("unchecked")
    private static List<Object> listArray(Map<String, Object> data) {
        for (String key : new String[]{"list", "items", "files", "records", "content"}) {
            Object value = data.get(key);
            if (value instanceof List<?> l) {
                return (List<Object>) l;
            }
        }
        return new ArrayList<>();
    }

    private static CloudFile parseFile(Object node) {
        if (!(node instanceof Map<?, ?>)) {
            return null;
        }
        String id = firstNonBlank(CloudHttp.str(node, "fileId"), CloudHttp.str(node, "file_id"),
                CloudHttp.str(node, "id"), CloudHttp.str(node, "resId"),
                CloudHttp.str(node, "resourceId"));
        if (id.isBlank()) {
            return null;
        }
        String name = firstNonBlank(CloudHttp.str(node, "fileName"), CloudHttp.str(node, "name"),
                CloudHttp.str(node, "filename"), CloudHttp.str(node, "dirName"),
                CloudHttp.str(node, "title"));
        long size = firstLong(firstNonBlank(CloudHttp.str(node, "fileSize"),
                CloudHttp.str(node, "size"), CloudHttp.str(node, "file_size")));
        String resType = CloudHttp.str(node, "resType");
        String dirType = CloudHttp.str(node, "dirType");
        String type = CloudHttp.str(node, "type").toLowerCase();
        String fileType = CloudHttp.str(node, "fileType").toLowerCase();
        boolean folder;
        if ("2".equals(resType)) {
            folder = true;
        } else if ("1".equals(resType)) {
            folder = false;
        } else {
            folder = CloudHttp.bool(node, "isDir") || CloudHttp.bool(node, "isdir")
                    || CloudHttp.bool(node, "dir") || CloudHttp.bool(node, "isFolder")
                    || CloudHttp.bool(node, "folder")
                    || !CloudHttp.str(node, "dirName").isBlank()
                    || "folder".equals(type) || "dir".equals(type)
                    || "directory".equals(type) || "0".equals(type)
                    || "folder".equals(fileType) || "dir".equals(fileType)
                    || "directory".equals(fileType) || "0".equals(fileType)
                    || (!name.contains(".") && "1".equals(dirType));
        }
        return new CloudFile(id, name, size, folder);
    }

    private static Map<String, Object> dataOf(Object json) {
        Object data = CloudHttp.at(json, "data");
        if (data instanceof Map<?, ?>) {
            return CloudHttp.map(json, "data");
        }
        return new LinkedHashMap<>();
    }

    private static void checkBusiness(Object json, String fallback) throws CloudException {
        if (isSuccess(json)) {
            return;
        }
        String code = CloudHttp.str(json, "code");
        String message = firstNonBlank(CloudHttp.str(json, "msg"), CloudHttp.str(json, "message"),
                CloudHttp.str(json, "error_description"));
        String mapped = switch (code) {
            case "209" -> "光鸭分享提取码错误或缺失";
            case "200", "201", "202" -> "光鸭分享已取消、过期或不可访问";
            case "205", "206", "207", "504" -> "光鸭分享下载受限，请在官网确认该分享的下载权限";
            case "157" -> "光鸭网盘空间不足";
            default -> null;
        };
        throw new CloudException(mapped != null ? mapped
                : (message.isBlank() ? fallback + "（code=" + code + "）" : message));
    }

    private static boolean isSuccess(Object json) {
        Object success = CloudHttp.at(json, "success");
        if (success != null && !CloudHttp.bool(json, "success")) {
            return false;
        }
        if (!CloudHttp.str(json, "error").isBlank()) {
            return false;
        }
        String code = CloudHttp.str(json, "code");
        return code.isBlank() || "0".equals(code);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private static long firstLong(String value) {
        try {
            return value == null || value.isBlank() ? -1 : Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String randomHex(int length) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append("0123456789abcdef".charAt(random.nextInt(16)));
        }
        return sb.toString();
    }

    private static String newTraceparent() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        return "00-" + hex(random, 32) + "-" + hex(random, 16) + "-01";
    }

    private static String hex(ThreadLocalRandom random, int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append("0123456789abcdef".charAt(random.nextInt(16)));
        }
        return sb.toString();
    }
}
