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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 百度网盘分享解析器：verify（提取码）-> xpan/share 列目录 -> 转存临时目录 -> locatedownload 取高速直链 -> 清理转存。
 *
 * <p>列目录支持匿名访问；取下载直链需要登录态 BDUSS（转存到个人网盘后取链）。</p>
 */
public final class BaiduProvider implements CloudProvider {

    private static final String APP_ID = "250528";
    private static final String TEMP_DIR_NAME = "/OmniFetch临时转存";
    private static final String UA_WEB =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";
    private static final String UA_NETDISK =
            "netdisk;12.24.6;piano;android-android;16;JSbridge4.4.0;jointBridge;1.1.0";
    private static final int PAGE_SIZE = 100;
    private static final int MAX_PAGES = 100;

    private static final Pattern SHARE_PATH = Pattern.compile(
            "(?:pan|yun)\\.baidu\\.com/s/([A-Za-z0-9_-]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern SHARE_INIT = Pattern.compile(
            "pan\\.baidu\\.com/share/init(?:\\?|.*?[?&])surl=([A-Za-z0-9_-]+)",
            Pattern.CASE_INSENSITIVE);

    @Override
    public CloudDrive drive() {
        return CloudDrive.BAIDU;
    }

    @Override
    public boolean matches(String text) {
        if (text == null) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        if (!lower.contains("pan.baidu.com/s/") && !lower.contains("yun.baidu.com/s/")
                && !lower.contains("pan.baidu.com/share/init")) {
            return false;
        }
        String url = normalizedUrl(text);
        return SHARE_PATH.matcher(url).find() || SHARE_INIT.matcher(url).find();
    }

    @Override
    public CloudShare parse(String text) throws CloudException {
        String url = normalizedUrl(text);
        String host = CloudLinks.hostOf(url);
        if (host.isEmpty()) {
            host = "pan.baidu.com";
        }
        String key = null;
        Matcher path = SHARE_PATH.matcher(url);
        if (path.find()) {
            key = path.group(1);
        }
        if (key == null) {
            Matcher init = SHARE_INIT.matcher(url);
            if (init.find()) {
                key = init.group(1);
            }
        }
        if (key == null || key.isBlank()) {
            throw new CloudException("无法识别百度网盘分享链接");
        }
        // 分享页短链以 1 开头，接口里的 surl 需要去掉这个前缀。
        if (key.startsWith("1") && key.length() > 1) {
            key = key.substring(1);
        }
        String passcode = CloudLinks.extractPasscode(text);
        return new CloudShare(CloudDrive.BAIDU, key, passcode, url, host);
    }

    @Override
    public List<CloudFile> list(CloudContext ctx, CloudShare share, CloudFile dir) throws Exception {
        // 百度分享页与列表接口支持匿名浏览，登录只影响能否取下载直链。
        String sekey = sekeyFor(ctx, share, false);
        String dirPath = dir == null || dir.id().isBlank() ? "/" : dir.id();
        String parentId = dir == null ? "" : dir.id();
        String basePath = dir == null ? "/" : dir.displayPath();
        boolean root = "/".equals(dirPath) || dirPath.isBlank();

        List<CloudFile> result = new ArrayList<>();
        int page = 1;
        while (page <= MAX_PAGES) {
            Map<String, Object> json = requestList(ctx, share, sekey, dirPath, root, page);
            long errno = CloudHttp.lng(json, "errno");
            if (errno != 0) {
                throw listError(errno, json, sekey);
            }
            rememberShareInfo(share, json);
            List<Object> items = CloudHttp.list(json, "list");
            for (Object raw : items) {
                Map<String, Object> item = asMap(raw);
                if (item.isEmpty()) {
                    continue;
                }
                boolean folder = isFolder(item.get("isdir"));
                String fsId = CloudHttp.str(item, "fs_id");
                String path = CloudHttp.str(item, "path");
                String id = folder
                        ? (path.isBlank() ? fsId : path)
                        : (fsId.isBlank() ? path : fsId);
                if (id.isBlank()) {
                    continue;
                }
                String name = CloudHttp.str(item, "server_filename");
                if (name.isBlank()) {
                    name = CloudHttp.str(item, "filename");
                }
                if (folder && path.isBlank() && !name.isBlank()) {
                    id = "/" + name;
                }
                long size = CloudHttp.lng(item, "size");
                CloudFile file = new CloudFile(id, name, size, folder)
                        .parentId(parentId)
                        .path(basePath);
                file.put("fs_id", fsId);
                file.put("path", path);
                file.put("isdir", folder);
                result.add(file);
            }
            if (items.size() < PAGE_SIZE) {
                break;
            }
            page++;
        }
        return result;
    }

    @Override
    public CloudLink resolve(CloudContext ctx, CloudShare share, CloudFile file) throws Exception {
        requireCookie(ctx);
        if (file.folder()) {
            throw new CloudException("目录不能直接下载，请选择文件");
        }
        String fsId = String.valueOf(file.extra().getOrDefault("fs_id", file.id())).trim();
        if (fsId.isBlank()) {
            throw new CloudException("文件标识缺失，无法解析下载直链");
        }

        String sekey = sekeyFor(ctx, share, true);
        String[] info = shareInfo(ctx, share, sekey);
        String cookie = ctx.cookieText();
        String tempDir = ensureTempDir(ctx, cookie);
        TransferResult transferred = transfer(ctx, share, info[0], info[1], sekey, fsId, tempDir, cookie);
        String dlink = locateDownload(ctx, transferred.path, cookie);
        // appall 直链自带签名，取链成功后即可删除临时转存，避免网盘残留。
        deleteQuietly(ctx, transferred.path, cookie);
        if (transferred.path.startsWith(TEMP_DIR_NAME + "/")) {
            deleteQuietly(ctx, TEMP_DIR_NAME, cookie);
        }

        Map<String, String> headers = CloudHttp.headers(
                "User-Agent", UA_NETDISK,
                "Referer", "https://pan.baidu.com/",
                "Accept", "*/*");
        if (cookie != null && !cookie.isBlank()) {
            headers.put("Cookie", cookie);
        }
        return new CloudLink(dlink, headers);
    }

    // ───────────────────────── 分享信息 ─────────────────────────

    /**
     * 获取 sekey（randsk）：有提取码且尚未缓存时先调用 share/verify。
     *
     * @param required 下载阶段为 true，此时无提取码的公共分享也允许（sekey 为空）
     */
    private String sekeyFor(CloudContext ctx, CloudShare share, boolean required) throws Exception {
        String cached = share.extra().getOrDefault("sekey", "");
        if (!cached.isBlank()) {
            return cached;
        }
        String pwd = share.passcode();
        if (pwd == null || pwd.isBlank()) {
            if (required) {
                return "";
            }
            return "";
        }
        String sekey = verifyShare(ctx, share);
        if (!sekey.isBlank()) {
            share.extra().put("sekey", sekey);
        }
        return sekey;
    }

    private String verifyShare(CloudContext ctx, CloudShare share) throws Exception {
        String url = "https://pan.baidu.com/share/verify?surl=" + enc(share.shareKey());
        Map<String, String> form = new LinkedHashMap<>();
        form.put("pwd", share.passcode());
        form.put("vcode", "");
        form.put("vcode_str", "");
        String body = ctx.http().postForm(url, form, requestHeaders(share, null));
        Map<String, Object> json = CloudHttp.map(CloudHttp.json(body));
        long errno = CloudHttp.lng(json, "errno");
        if (errno != 0) {
            throw verifyError(errno, json);
        }
        String randsk = CloudHttp.str(json, "randsk");
        if (randsk.isBlank()) {
            throw new CloudException("百度网盘未返回分享密钥，请检查提取码");
        }
        return randsk;
    }

    private Map<String, Object> requestList(CloudContext ctx, CloudShare share,
                                            String sekey, String dirPath,
                                            boolean root, int page) throws Exception {
        StringBuilder url = new StringBuilder("https://pan.baidu.com/rest/2.0/xpan/share?method=list")
                .append("&shorturl=").append(enc(share.shareKey()))
                .append("&page=").append(page)
                .append("&num=").append(PAGE_SIZE)
                .append("&root=").append(root ? "1" : "0")
                .append("&dir=").append(enc(dirPath));
        if (!sekey.isBlank()) {
            // randsk 本身已是 URL 编码形式，不能再次编码。
            url.append("&sekey=").append(sekey);
        }
        String body = ctx.http().getText(url.toString(),
                requestHeaders(share, subDirCookie(ctx, sekey, root)));
        return CloudHttp.map(CloudHttp.json(body));
    }

    /**
     * 子目录（root=0）必须携带 BDCLND cookie（值为 verify 返回的 randsk），否则 errno=2。
     */
    private String subDirCookie(CloudContext ctx, String sekey, boolean root) {
        String cookie = ctx.cookieText();
        if (root || sekey.isBlank()) {
            return cookie;
        }
        if (cookie != null && cookie.contains("BDCLND=")) {
            return cookie;
        }
        return cookie == null || cookie.isBlank() ? "BDCLND=" + sekey : cookie + "; BDCLND=" + sekey;
    }

    private void rememberShareInfo(CloudShare share, Map<String, Object> json) {
        String shareId = CloudHttp.str(json, "share_id");
        String uk = CloudHttp.str(json, "uk");
        if (!shareId.isBlank()) {
            share.extra().put("share_id", shareId);
        }
        if (!uk.isBlank()) {
            share.extra().put("uk", uk);
        }
    }

    /** 取转存所需的 share_id/uk；缓存缺失时先列一次根目录。 */
    private String[] shareInfo(CloudContext ctx, CloudShare share, String sekey) throws Exception {
        String shareId = share.extra().getOrDefault("share_id", "");
        String uk = share.extra().getOrDefault("uk", "");
        if (!shareId.isBlank() && !uk.isBlank()) {
            return new String[]{shareId, uk};
        }
        Map<String, Object> json = requestList(ctx, share, sekey, "/", true, 1);
        long errno = CloudHttp.lng(json, "errno");
        if (errno != 0) {
            throw listError(errno, json, sekey);
        }
        rememberShareInfo(share, json);
        shareId = share.extra().getOrDefault("share_id", "");
        uk = share.extra().getOrDefault("uk", "");
        if (shareId.isBlank() || uk.isBlank()) {
            throw new CloudException("获取百度分享信息失败，请检查分享链接是否有效");
        }
        return new String[]{shareId, uk};
    }

    // ───────────────────────── 个人网盘 ─────────────────────────

    private String ensureTempDir(CloudContext ctx, String cookie) throws Exception {
        List<String> root = listDir(ctx, "/", cookie);
        if (root.contains(TEMP_DIR_NAME)) {
            return TEMP_DIR_NAME;
        }
        return createDir(ctx, TEMP_DIR_NAME, cookie) ? TEMP_DIR_NAME : "/";
    }

    private String bdstoken(CloudContext ctx, String cookie) throws Exception {
        String fields = "%5B%22bdstoken%22%5D";
        String url = "https://pan.baidu.com/api/gettemplatevariable"
                + "?clienttype=0&app_id=" + APP_ID + "&web=1&fields=" + fields;
        Map<String, String> headers = CloudHttp.headers(
                "User-Agent", UA_WEB,
                "Referer", "https://pan.baidu.com/",
                "Accept", "application/json, text/plain, */*");
        if (cookie != null && !cookie.isBlank()) {
            headers.put("Cookie", cookie);
        }
        String body = ctx.http().getText(url, headers);
        Map<String, Object> json = CloudHttp.map(CloudHttp.json(body));
        String token = CloudHttp.str(json, "result", "bdstoken");
        if (token.isBlank()) {
            throw new CloudException("获取百度 bdstoken 失败，请更新百度网盘 Cookie");
        }
        return token;
    }

    private List<String> listDir(CloudContext ctx, String dir, String cookie) throws Exception {
        String url = "https://pan.baidu.com/api/list?clienttype=0&app_id=" + APP_ID
                + "&web=1&channel=chunlei&showempty=0&order=time&desc=1"
                + "&dir=" + enc(dir) + "&num=100&page=1";
        Map<String, String> headers = CloudHttp.headers(
                "User-Agent", UA_WEB,
                "Referer", "https://pan.baidu.com/disk/main",
                "X-Requested-With", "XMLHttpRequest");
        if (cookie != null && !cookie.isBlank()) {
            headers.put("Cookie", cookie);
        }
        String body = ctx.http().getText(url, headers);
        Map<String, Object> json = CloudHttp.map(CloudHttp.json(body));
        List<String> paths = new ArrayList<>();
        if (CloudHttp.lng(json, "errno") != 0) {
            return paths;
        }
        for (Object raw : CloudHttp.list(json, "list")) {
            Map<String, Object> item = asMap(raw);
            String path = CloudHttp.str(item, "path");
            if (!path.isBlank()) {
                paths.add(path);
            }
        }
        return paths;
    }

    private boolean createDir(CloudContext ctx, String path, String cookie) throws Exception {
        String token = bdstoken(ctx, cookie);
        String url = "https://pan.baidu.com/api/create?a=commit&channel=chunlei&web=1"
                + "&app_id=" + APP_ID + "&clienttype=0&bdstoken=" + enc(token);
        Map<String, String> form = new LinkedHashMap<>();
        form.put("path", path);
        form.put("isdir", "1");
        form.put("size", "");
        form.put("block_list", "[]");
        form.put("method", "post");
        form.put("dataType", "json");
        String body = ctx.http().postForm(url, form,
                CloudHttp.headers("User-Agent", UA_WEB,
                        "Referer", "https://pan.baidu.com/disk/main",
                        "Cookie", cookie == null ? "" : cookie));
        Map<String, Object> json = CloudHttp.map(CloudHttp.json(body));
        return CloudHttp.lng(json, "errno") == 0;
    }

    private TransferResult transfer(CloudContext ctx, CloudShare share, String shareId, String uk,
                                    String sekey, String fsId, String toDir, String cookie)
            throws Exception {
        String token = bdstoken(ctx, cookie);
        StringBuilder url = new StringBuilder("https://pan.baidu.com/share/transfer?shareid=")
                .append(enc(shareId))
                .append("&from=").append(enc(uk))
                .append("&channel=chunlei&ondup=newcopy&web=1&app_id=").append(APP_ID)
                .append("&clienttype=0&bdstoken=").append(enc(token));
        if (!sekey.isBlank()) {
            url.append("&sekey=").append(sekey);
        }
        String authCookie = cookie == null ? "" : cookie;
        if (!sekey.isBlank() && !authCookie.contains("BDCLND=")) {
            authCookie = authCookie.isBlank() ? "BDCLND=" + sekey : authCookie + "; BDCLND=" + sekey;
        }
        Map<String, String> form = new LinkedHashMap<>();
        form.put("fsidlist", "[\"" + fsId + "\"]");
        form.put("path", toDir);
        Map<String, String> headers = CloudHttp.headers(
                "User-Agent", UA_WEB,
                "Origin", "https://pan.baidu.com",
                "Referer", "https://pan.baidu.com/s/",
                "Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
        if (!authCookie.isBlank()) {
            headers.put("Cookie", authCookie);
        }
        String body = ctx.http().postForm(url.toString(), form, headers);
        Map<String, Object> json = CloudHttp.map(CloudHttp.json(body));
        long errno = CloudHttp.lng(json, "errno");
        if (errno != 0) {
            throw transferError(errno, json);
        }
        List<Object> list = CloudHttp.list(json, "extra", "list");
        if (list.isEmpty()) {
            throw new CloudException("百度转存失败：未返回新文件");
        }
        Map<String, Object> item = asMap(list.get(0));
        String newFsId = CloudHttp.str(item, "to_fs_id");
        String newPath = CloudHttp.str(item, "to");
        if (newFsId.isBlank()) {
            throw new CloudException("百度转存失败：未返回新文件标识");
        }
        if (newPath.isBlank()) {
            newPath = toDir.endsWith("/") ? toDir : toDir + "/";
        }
        return new TransferResult(newFsId, newPath);
    }

    /** locatedownload：返回 appallNN.baidupcs.com 明文直链（自带签名，可 Range 下载）。 */
    private String locateDownload(CloudContext ctx, String path, String cookie) throws Exception {
        long time = System.currentTimeMillis() / 1000;
        String url = "https://d.pcs.baidu.com/rest/2.0/pcs/file?method=locatedownload"
                + "&app_id=" + APP_ID
                + "&clienttype=17&ver=4.0&ant=1&check_blue=1&es=1&esl=1&apn_id=1_-1"
                + "&freeisp=0&queryfree=0&use=1&dtype=1&eck=1&ehps=1&err_ver=1.0"
                + "&network_type=WIFI&channel=0&path=" + enc(path) + "&time=" + time
                + "&rand=5ed606e9da222cde0474cdf70eda884b"
                + "&devuid=0F1E9FC2E084472DA5A61C4CF4C759AF"
                + "&cuid=0F1E9FC2E084472DA5A61C4CF4C759AF"
                + "&deviceid=348642637967375013"
                + "&psign=860a071f77c860e8cea06e4e54c518f3"
                + "&version=2.2.111.34&version_app=12.24.6&vip=0";
        Map<String, String> headers = CloudHttp.headers(
                "User-Agent", UA_NETDISK,
                "Content-Type", "application/x-www-form-urlencoded");
        if (cookie != null && !cookie.isBlank()) {
            headers.put("Cookie", cookie);
        }
        String body = ctx.http().send("POST", url, headers, "0", true).body();
        Map<String, Object> json = CloudHttp.map(CloudHttp.json(body));
        long errno = CloudHttp.lng(json, "errno");
        if (errno != 0) {
            throw downloadError(errno, json);
        }

        List<Object> urls = CloudHttp.list(json, "urls");
        String plain = "";
        String https = "";
        String first = "";
        for (Object raw : urls) {
            Map<String, Object> item = asMap(raw);
            String candidate = CloudHttp.str(item, "url");
            if (candidate.isBlank()) {
                continue;
            }
            if (first.isBlank()) {
                first = candidate;
            }
            if (https.isBlank() && candidate.startsWith("https")) {
                https = candidate;
            }
            if (plain.isBlank() && CloudHttp.bool(item, "encrypt") == false
                    && candidate.startsWith("https")) {
                plain = candidate;
            }
        }
        if (!plain.isBlank()) {
            return plain;
        }
        if (!https.isBlank()) {
            return https;
        }
        if (!first.isBlank()) {
            return first;
        }
        throw new CloudException("百度网盘未返回下载链接");
    }

    /** 删除转存文件（失败不阻断）。 */
    private void deleteQuietly(CloudContext ctx, String path, String cookie) {
        try {
            String token = bdstoken(ctx, cookie);
            String url = "https://pan.baidu.com/api/filemanager?async=2&onnest=fail&opera=delete"
                    + "&bdstoken=" + enc(token) + "&newVerify=1&clienttype=0&app_id=" + APP_ID + "&web=1";
            Map<String, String> form = new LinkedHashMap<>();
            form.put("filelist", "[\"" + path + "\"]");
            Map<String, String> headers = CloudHttp.headers(
                    "User-Agent", UA_WEB,
                    "Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
            if (cookie != null && !cookie.isBlank()) {
                headers.put("Cookie", cookie);
            }
            ctx.http().postForm(url, form, headers);
        } catch (Exception ignored) {
            // 清理失败不影响下载
        }
    }

    // ───────────────────────── 辅助 ─────────────────────────

    private static void requireCookie(CloudContext ctx) throws CloudException {
        String cookie = ctx.cookieText();
        if (cookie == null || cookie.isBlank() || !cookie.contains("BDUSS=")) {
            throw new CloudException("百度网盘下载需要登录，请填写包含 BDUSS 的 Cookie");
        }
    }

    private static Map<String, String> requestHeaders(CloudShare share, String cookie) {
        Map<String, String> headers = CloudHttp.headers(
                "User-Agent", UA_WEB,
                "Referer", "https://pan.baidu.com/s/1" + share.shareKey(),
                "Origin", "https://pan.baidu.com",
                "Accept", "application/json, text/plain, */*",
                "Accept-Language", "zh-CN,zh;q=0.9",
                "X-Requested-With", "XMLHttpRequest");
        if (cookie != null && !cookie.isBlank()) {
            headers.put("Cookie", cookie);
        }
        return headers;
    }

    private static CloudException verifyError(long errno, Map<String, Object> json) {
        String detail = CloudHttp.str(json, "err_msg");
        if (detail.isBlank()) {
            detail = CloudHttp.str(json, "show_msg");
        }
        if (errno == -12 || errno == -9 || errno == -62) {
            return new CloudException("提取码错误，请检查提取码");
        }
        if (errno == -6) {
            return new CloudException("百度网盘登录状态失效，请更新 Cookie");
        }
        return new CloudException(detail.isBlank()
                ? "百度网盘提取码校验失败（errno=" + errno + "）"
                : "百度网盘提取码校验失败：" + detail + "（errno=" + errno + "）");
    }

    private static CloudException listError(long errno, Map<String, Object> json, String sekey) {
        String detail = CloudHttp.str(json, "err_msg");
        if (detail.isBlank()) {
            detail = CloudHttp.str(json, "show_msg");
        }
        if (errno == 105 || errno == 404) {
            return new CloudException("百度网盘分享链接已失效或不存在");
        }
        if (errno == -6) {
            return new CloudException("该分享需要提取码，或需要登录百度网盘（errno=-6）");
        }
        if (errno == -9 || errno == -12) {
            return new CloudException("提取码错误，请检查提取码");
        }
        if (sekey.isBlank() && errno == 2) {
            return new CloudException("该分享需要提取码（errno=2）");
        }
        String base = "获取百度网盘文件列表失败（errno=" + errno + "）";
        return new CloudException(detail.isBlank() ? base : base + "：" + detail);
    }

    private static CloudException transferError(long errno, Map<String, Object> json) {
        String detail = CloudHttp.str(json, "err_msg");
        if (detail.isBlank()) {
            detail = CloudHttp.str(json, "show_msg");
        }
        if (errno == -6) {
            return new CloudException("百度网盘登录状态失效，请更新 Cookie");
        }
        if (errno == -33 || errno == 12) {
            return new CloudException("百度网盘空间不足，无法转存临时文件");
        }
        String base = "百度网盘转存失败（errno=" + errno + "）";
        return new CloudException(detail.isBlank() ? base : base + "：" + detail);
    }

    private static CloudException downloadError(long errno, Map<String, Object> json) {
        String detail = CloudHttp.str(json, "err_msg");
        if (detail.isBlank()) {
            detail = CloudHttp.str(json, "show_msg");
        }
        if (errno == -6) {
            return new CloudException("百度网盘登录状态失效，请更新 Cookie");
        }
        if (errno == 31066 || errno == 2) {
            return new CloudException("文件不存在或已被删除，请重新解析");
        }
        String base = "获取百度网盘下载直链失败（errno=" + errno + "）";
        return new CloudException(detail.isBlank() ? base : base + "：" + detail);
    }

    private static boolean isFolder(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof Number n) {
            return n.longValue() == 1L;
        }
        return value != null && ("1".equals(value.toString()) || "true".equalsIgnoreCase(value.toString()));
    }

    private static Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                result.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            return result;
        }
        return new LinkedHashMap<>();
    }

    private static String normalizedUrl(String text) {
        String url = CloudLinks.extractUrl(text);
        if (url.isEmpty()) {
            url = text.trim();
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://" + url;
        }
        return url;
    }

    private static String enc(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static final class TransferResult {
        final String fsId;
        final String path;

        TransferResult(String fsId, String path) {
            this.fsId = fsId;
            this.path = path;
        }
    }
}
