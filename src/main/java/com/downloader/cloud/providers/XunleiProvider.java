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

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 迅雷网盘分享解析。
 * <p>分享列目录允许匿名访问；转存 / 取直链需要登录凭据。
 * 凭据从配置的 Cookie 文本中读取 access_token（也兼容整段 localStorage JSON）。
 * 请求需带 X-Device-Id 与 X-Captcha-Token，验证码 token 按设备缓存并在失效时重签。
 */
public final class XunleiProvider implements CloudProvider {

    private static final String PAN_BASE = "https://api-pan.xunlei.com";
    private static final String SHARE_URL = PAN_BASE + "/drive/v1/share";
    private static final String SHARE_DETAIL_URL = PAN_BASE + "/drive/v1/share/detail";
    private static final String FILES_URL = PAN_BASE + "/drive/v1/files";
    private static final String RESTORE_URL = PAN_BASE + "/drive/v1/share/restore";
    private static final String CAPTCHA_INIT_URL = "https://xluser-ssl.xunlei.com/v1/shield/captcha/init";
    private static final String REFRESH_URL = "https://xluser-ssl.xunlei.com/v1/auth/token";

    private static final String APP_CLIENT_ID = "Xp6vsxz_7IYVw2BB";
    private static final String APP_CLIENT_VERSION = "8.31.0.9726";
    private static final String APP_PACKAGE_NAME = "com.xunlei.downloadprovider";
    private static final String WEB_CLIENT_ID = "Xqp0kJBXWhwaTpB6";
    private static final String WEB_CLIENT_VERSION = "1.93.10";
    private static final String WEB_PACKAGE_NAME = "pan.xunlei.com";

    private static final String WEB_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36";
    private static final String DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";
    private static final String APP_UA =
            "ANDROID-com.xunlei.downloadprovider/8.31.0.9726 netWorkType/5G appid/40 "
                    + "deviceName/Xiaomi_M2004j7ac deviceModel/M2004J7AC OSVersion/12 protocolVersion/301 "
                    + "platformVersion/10 sdkVersion/512000 Oauth2Client/0.9 "
                    + "(Linux 4_14_186-perf-gddfs8vbb238b) (JAVA 0)";
    private static final String DESKTOP_SEC_CH_UA =
            "\"Google Chrome\";v=\"131\", \"Chromium\";v=\"131\", \"Not_A Brand\";v=\"24\"";

    private static final String[] APP_SALTS = {
            "9uJNVj/wLmdwKrJaVj/omlQ",
            "Oz64Lp0GigmChHMf/6TNfxx7O9PyopcczMsnf",
            "Eb+L7Ce+Ej48u",
            "jKY0",
            "ASr0zCl6v8W4aidjPK5KHd1Lq3t+vBFf41dqv5+fnOd",
            "wQlozdg6r1qxh0eRmt3QgNXOvSZO6q/GXK",
            "gmirk+ciAvIgA/cxUUCema47jr/YToixTT+Q6O",
            "5IiCoM9B1/788ntB",
            "P07JH0h6qoM6TSUAK2aL9T5s2QBVeY9JWvalf",
            "+oK0AN"
    };

    private static final String[] WEB_SALTS = {
            "DVM8BRQfJWDsIE0JbH5GCUM0z0jU1yGymBTj4Eyloazv1F+w0G7ig",
            "T",
            "a1fUxfjOO1/dW++DpGupRgw9P93L",
            "aktKbp+/lQc3MvlMHIupt65LjAgJOL6ttMFvTKK+7+WKubSf9rT",
            "SB/ne9FQUnQmWz4Wuv9KqKfGOBOCUubpHpECxOHfD4hNoMclYA2Yq",
            "sOZFBQ8IMFy3OFjAIf/eA65iqFvS9TIKzLyrhiT6",
            "ZhJdLgL7MGh2GanboB0yk",
            "Osf",
            "v"
    };

    private static final String TEMP_DIR_NAME = "OmniFetch临时转存";
    private static final String EXTRA_PASS_TOKEN = "xlPassCodeToken";
    private static final int CAPTCHA_TTL_MS = 270_000;
    private static final int MAX_PAGES = 100;

    private static final Pattern SHARE_PATTERN =
            Pattern.compile("pan\\.xunlei\\.com/s/([A-Za-z0-9_-]+)", Pattern.CASE_INSENSITIVE);

    private static final class Captcha {
        private final String token;
        private final String clientId;
        private final long expiresAt;

        private Captcha(String token, String clientId, long expiresAt) {
            this.token = token;
            this.clientId = clientId;
            this.expiresAt = expiresAt;
        }
    }

    private final Map<String, Captcha> captchas = new ConcurrentHashMap<>();
    private final Map<String, String> refreshedAccessTokens = new ConcurrentHashMap<>();
    private final Map<String, String> refreshedRefreshTokens = new ConcurrentHashMap<>();
    private final String guestDeviceId = randomHex(32);

    @Override
    public CloudDrive drive() {
        return CloudDrive.XUNLEI;
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
            throw new CloudException("无法识别迅雷网盘分享链接（应形如 https://pan.xunlei.com/s/xxxx）");
        }
        String shareId = m.group(1);
        String passcode = CloudLinks.extractPasscode(text);
        return new CloudShare(CloudDrive.XUNLEI, shareId, passcode, url, CloudLinks.hostOf(url));
    }

    @Override
    public List<CloudFile> list(CloudContext ctx, CloudShare share, CloudFile dir) throws Exception {
        String token = accessToken(ctx, share);
        String device = deviceId(ctx);
        String parentId = dir == null ? "" : dir.id();
        String basePath = dir == null ? "/" : dir.displayPath();

        List<CloudFile> result = new ArrayList<>();
        if (dir == null) {
            String pageToken = "";
            int pages = 0;
            do {
                Map<String, Object> data = fetchSharePage(ctx, share, token, device, pageToken, 100);
                collectFiles(data, parentId, basePath, result);
                pageToken = CloudHttp.str(data, "next_page_token");
                pages++;
            } while (!pageToken.isBlank() && pages < MAX_PAGES);
            return result;
        }

        String passToken = share.extra().getOrDefault(EXTRA_PASS_TOKEN, "");
        if (passToken.isBlank()) {
            fetchSharePage(ctx, share, token, device, "", 1);
            passToken = share.extra().getOrDefault(EXTRA_PASS_TOKEN, "");
        }
        if (passToken.isBlank()) {
            throw new CloudException("迅雷分享访问令牌缺失，请重新解析分享链接");
        }
        String pageToken = "";
        int pages = 0;
        do {
            String url = SHARE_DETAIL_URL
                    + "?share_id=" + enc(share.shareKey())
                    + "&parent_id=" + enc(parentId)
                    + "&pass_code_token=" + enc(passToken)
                    + "&limit=100&page_token=" + enc(pageToken)
                    + "&thumbnail_size=SIZE_SMALL";
            Map<String, Object> data = panRequest(ctx, share, "GET", url, null, token, device,
                    "GET:/drive/v1/share/detail");
            collectFiles(data, parentId, basePath, result);
            pageToken = CloudHttp.str(data, "next_page_token");
            pages++;
        } while (!pageToken.isBlank() && pages < MAX_PAGES);
        return result;
    }

    @Override
    public CloudLink resolve(CloudContext ctx, CloudShare share, CloudFile file) throws Exception {
        String token = accessToken(ctx, share);
        if (token.isBlank()) {
            throw new CloudException("请在网盘解析窗口填写迅雷 Cookie（需包含 access_token）");
        }
        String device = deviceId(ctx);
        String tempDir = ensureTempDir(ctx, share, token, device);
        String savedId = restore(ctx, share, file, tempDir, token, device);
        CloudLink link = fileDetail(ctx, share, savedId, token, device);
        try {
            batchDelete(ctx, share, savedId, token, device);
        } catch (Exception ignored) {
            // 临时转存文件删除失败不阻断下载：直链已自带签名
        }
        return link;
    }

    private Map<String, Object> fetchSharePage(CloudContext ctx, CloudShare share, String token,
                                               String device, String pageToken, int limit) throws Exception {
        String url = SHARE_URL
                + "?share_id=" + enc(share.shareKey())
                + "&pass_code=" + enc(share.passcode())
                + "&limit=" + limit
                + "&page_token=" + enc(pageToken)
                + "&thumbnail_size=SIZE_SMALL";
        Map<String, Object> data = panRequest(ctx, share, "GET", url, null, token, device,
                "GET:/drive/v1/share");
        checkShareStatus(data);
        String passToken = CloudHttp.str(data, "pass_code_token");
        if (!passToken.isBlank()) {
            share.extra().put(EXTRA_PASS_TOKEN, passToken);
        }
        return data;
    }

    private String ensureTempDir(CloudContext ctx, CloudShare share, String token,
                                 String device) throws Exception {
        String filters = URLEncoder.encode("{\"trashed\":{\"eq\":false}}", StandardCharsets.UTF_8);
        String url = FILES_URL + "?parent_id=&page_token=&limit=50&with_audit=true&filters=" + filters;
        Map<String, Object> data = panRequest(ctx, share, "GET", url, null, token, device,
                "GET:/drive/v1/files");
        for (Object item : CloudHttp.list(data, "files")) {
            if (!(item instanceof Map<?, ?>)) {
                continue;
            }
            if ("drive#folder".equals(CloudHttp.str(item, "kind"))
                    && TEMP_DIR_NAME.equals(CloudHttp.str(item, "name"))) {
                String id = CloudHttp.str(item, "id");
                if (!id.isBlank()) {
                    return id;
                }
            }
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("kind", "drive#folder");
        body.put("name", TEMP_DIR_NAME);
        body.put("parent_id", "");
        body.put("space", "");
        Map<String, Object> created = panRequest(ctx, share, "POST", FILES_URL,
                MiniJson.write(body), token, device, "POST:/drive/v1/files");
        String id = CloudHttp.str(created, "id");
        if (id.isBlank()) {
            throw new CloudException("迅雷创建临时转存目录失败");
        }
        return id;
    }

    private String restore(CloudContext ctx, CloudShare share, CloudFile file, String parentId,
                           String token, String device) throws Exception {
        String passToken = share.extra().getOrDefault(EXTRA_PASS_TOKEN, "");
        if (passToken.isBlank()) {
            fetchSharePage(ctx, share, token, device, "", 1);
            passToken = share.extra().getOrDefault(EXTRA_PASS_TOKEN, "");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("share_id", share.shareKey());
        body.put("pass_code_token", passToken);
        body.put("parent_id", parentId);
        body.put("ancestor_ids", new ArrayList<>());
        body.put("file_ids", List.of(file.id()));
        body.put("specify_parent_id", true);
        Map<String, Object> data = panRequest(ctx, share, "POST", RESTORE_URL,
                MiniJson.write(body), token, device, "POST:/drive/v1/share/restore");

        String trace = CloudHttp.str(data, "params", "trace_file_ids");
        if (!trace.isBlank()) {
            try {
                Object parsed = MiniJson.parse(trace);
                Object newId = CloudHttp.at(parsed, file.id());
                if (newId != null && !String.valueOf(newId).isBlank()) {
                    return String.valueOf(newId);
                }
            } catch (Exception ignored) {
                // trace_file_ids 非 JSON 时回退到 file_id 字段
            }
        }
        String id = CloudHttp.str(data, "file_id");
        if (id.isBlank()) {
            throw new CloudException("迅雷转存失败，未能获取转存后的文件 ID");
        }
        return id;
    }

    private CloudLink fileDetail(CloudContext ctx, CloudShare share, String fileId,
                                 String token, String device) throws Exception {
        String url = FILES_URL + "/" + enc(fileId)
                + "?_magic=2021&usage=PLAY&thumbnail_size=SIZE_LARGE"
                + "&with=hdr10&with=subtitle_files&with=task&with=public_share_tag";
        Map<String, Object> data = panRequest(ctx, share, "GET", url, null, token, device,
                "GET:/drive/v1/files/" + fileId);
        String linkUrl = CloudHttp.str(data, "links", "application/octet-stream", "url");
        if (linkUrl.isBlank()) {
            linkUrl = CloudHttp.str(data, "web_content_link");
        }
        if (linkUrl.isBlank()) {
            throw new CloudException("迅雷未返回有效下载地址，请确认该账号的下载权限");
        }
        Map<String, String> headers = CloudHttp.headers(
                "User-Agent", WEB_UA,
                "Referer", "https://pan.xunlei.com/",
                "Accept", "*/*");
        return new CloudLink(linkUrl, headers);
    }

    private void batchDelete(CloudContext ctx, CloudShare share, String fileId,
                             String token, String device) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ids", List.of(fileId));
        body.put("space", "");
        panRequest(ctx, share, "POST", FILES_URL + ":batchDelete", MiniJson.write(body),
                token, device, "POST:/drive/v1/files:batchDelete");
    }

    private Map<String, Object> panRequest(CloudContext ctx, CloudShare share, String method,
                                           String url, String body, String token, String device,
                                           String action) throws Exception {
        String access = token;
        // 迅雷分享接口即使匿名访问也要求 X-Captcha-Token，因此有无登录都要先取验证码
        Captcha captcha = ensureCaptcha(ctx, share, device, action, access);
        for (int attempt = 0; attempt < 2; attempt++) {
            Map<String, String> headers = panHeaders(access, device, captcha);
            HttpResponse<String> resp = ctx.http().send(method, url, headers, body, true);
            String text = resp.body();
            int status = resp.statusCode();
            if (text == null || text.isBlank()) {
                throw new CloudException("迅雷接口返回为空（HTTP " + status + "）");
            }
            Object json;
            try {
                json = CloudHttp.json(text);
            } catch (CloudException e) {
                throw new CloudException("迅雷接口返回异常（HTTP " + status + "）", e);
            }
            String error = CloudHttp.str(json, "error");
            if (status >= 400 || !error.isBlank()) {
                boolean unauthenticated = status == 401 || "unauthenticated".equals(error);
                if (attempt == 0 && unauthenticated) {
                    if (access.isBlank()) {
                        throw new CloudException("请在网盘解析窗口填写迅雷 Cookie");
                    }
                    String fresh = refreshAccessToken(ctx, share, access, device);
                    if (!fresh.isBlank()) {
                        access = fresh;
                        captchas.remove(device);
                        captcha = ensureCaptcha(ctx, share, device, action, access);
                        continue;
                    }
                    throw new CloudException("迅雷登录已过期，请在网盘解析窗口重新填写迅雷 Cookie");
                }
                if (attempt == 0 && "captcha_invalid".equals(error)) {
                    captchas.remove(device);
                    captcha = ensureCaptcha(ctx, share, device, action, access);
                    continue;
                }
                String message = firstNonBlank(CloudHttp.str(json, "error_description"),
                        CloudHttp.str(json, "message"), error);
                throw new CloudException(message.isBlank()
                        ? "迅雷请求失败（HTTP " + status + "）" : message);
            }
            Object data = CloudHttp.at(json, "data");
            if (data instanceof Map<?, ?>) {
                return CloudHttp.map(json, "data");
            }
            return CloudHttp.map(json);
        }
        throw new CloudException("迅雷验证码刷新后仍失败");
    }

    private static Map<String, String> panHeaders(String token, String device, Captcha captcha) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("User-Agent", WEB_UA);
        headers.put("X-Device-Id", device);
        headers.put("X-Client-Version", APP_CLIENT_VERSION);
        headers.put("Content-Type", "application/json");
        headers.put("Origin", "https://pan.xunlei.com");
        headers.put("Referer", "https://pan.xunlei.com/");
        if (!token.isBlank()) {
            headers.put("Authorization", "Bearer " + token);
        }
        if (captcha != null && !captcha.token.isBlank()) {
            headers.put("X-Captcha-Token", captcha.token);
            // 验证码 token 与签发它的 client_id 绑定，缺这个头会直接返回 captcha_invalid
            if (!captcha.clientId.isBlank()) {
                headers.put("X-Client-Id", captcha.clientId);
            }
        }
        return headers;
    }

    private Captcha ensureCaptcha(CloudContext ctx, CloudShare share, String device,
                                  String action, String accessToken) {
        long now = System.currentTimeMillis();
        Captcha cached = captchas.get(device);
        if (cached != null && !cached.token.isBlank() && cached.expiresAt > now) {
            return cached;
        }
        String userId = jwtSub(accessToken);
        // 匿名访问没有网页会话，App 通道签发的 token 才稳定；登录态优先走网页签名
        Captcha fresh;
        if (accessToken.isBlank()) {
            String appToken = initAppCaptcha(ctx, device, action, userId);
            fresh = appToken.isBlank()
                    ? webCaptcha(ctx, device, action, userId)
                    : new Captcha(appToken, APP_CLIENT_ID, now + CAPTCHA_TTL_MS);
        } else {
            fresh = webCaptcha(ctx, device, action, userId);
            if (fresh == null) {
                String appToken = initAppCaptcha(ctx, device, action, userId);
                fresh = appToken.isBlank() ? null : new Captcha(appToken, APP_CLIENT_ID, now + CAPTCHA_TTL_MS);
            }
        }
        if (fresh != null) {
            captchas.put(device, fresh);
            return fresh;
        }
        return new Captcha("", "", now);
    }

    private Captcha webCaptcha(CloudContext ctx, String device, String action, String userId) {
        String token = initWebCaptcha(ctx, device, action, userId);
        if (token.isBlank()) {
            return null;
        }
        return new Captcha(token, WEB_CLIENT_ID, System.currentTimeMillis() + CAPTCHA_TTL_MS);
    }

    private String initWebCaptcha(CloudContext ctx, String device, String action, String userId) {
        try {
            long ts = System.currentTimeMillis();
            String sign = captchaSign(WEB_CLIENT_ID, WEB_CLIENT_VERSION, WEB_PACKAGE_NAME,
                    device, String.valueOf(ts), WEB_SALTS);
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("username", "");
            meta.put("phone_number", "");
            meta.put("email", "");
            meta.put("package_name", WEB_PACKAGE_NAME);
            meta.put("client_version", WEB_CLIENT_VERSION);
            meta.put("captcha_sign", sign);
            meta.put("timestamp", String.valueOf(ts));
            meta.put("user_id", userId);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("client_id", WEB_CLIENT_ID);
            body.put("action", action);
            body.put("device_id", device);
            body.put("meta", meta);
            Map<String, String> headers = CloudHttp.headers(
                    "Accept", "*/*",
                    "Content-Type", "text/plain;charset=UTF-8",
                    "Origin", "https://pan.xunlei.com",
                    "Referer", "https://pan.xunlei.com/",
                    "User-Agent", DESKTOP_UA);
            String text = ctx.http().postJson(CAPTCHA_INIT_URL, MiniJson.write(body), headers);
            if (text.contains("client_id not match")) {
                return "";
            }
            Object json = CloudHttp.json(text);
            String token = CloudHttp.str(json, "captcha_token");
            return token.isBlank() ? "" : token;
        } catch (Exception e) {
            return "";
        }
    }

    private String initAppCaptcha(CloudContext ctx, String device, String action, String userId) {
        try {
            long ts = System.currentTimeMillis();
            String sign = captchaSign(APP_CLIENT_ID, APP_CLIENT_VERSION, APP_PACKAGE_NAME,
                    device, String.valueOf(ts), APP_SALTS);
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("client_version", APP_CLIENT_VERSION);
            meta.put("package_name", APP_PACKAGE_NAME);
            meta.put("timestamp", String.valueOf(ts));
            meta.put("captcha_sign", sign);
            meta.put("user_id", userId);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("client_id", APP_CLIENT_ID);
            body.put("action", action);
            body.put("device_id", device);
            body.put("redirect_uri", "xlaccsdk01://xunlei.com/callback?state=harbor");
            body.put("meta", meta);
            body.put("captcha_token", "");
            Map<String, String> headers = CloudHttp.headers(
                    "User-Agent", APP_UA,
                    "Accept", "application/json;charset=UTF-8",
                    "Content-Type", "application/json",
                    "X-Client-Id", APP_CLIENT_ID,
                    "X-Device-Id", device,
                    "X-Client-Version", APP_CLIENT_VERSION);
            String text = ctx.http().postJson(CAPTCHA_INIT_URL, MiniJson.write(body), headers);
            Object json = CloudHttp.json(text);
            String token = CloudHttp.str(json, "captcha_token");
            return token.isBlank() ? "" : token;
        } catch (Exception e) {
            return "";
        }
    }

    private static String captchaSign(String clientId, String version, String packageName,
                                      String deviceId, String timestamp, String[] salts) {
        String hash = clientId + version + packageName + deviceId + timestamp;
        for (String salt : salts) {
            hash = md5Hex(hash + salt);
        }
        return "1." + hash;
    }

    private String refreshAccessToken(CloudContext ctx, CloudShare share,
                                      String currentAccess, String device) {
        String cookie = ctx.cookieText();
        String refresh = refreshedRefreshTokens.getOrDefault(cookie, "");
        if (refresh.isBlank()) {
            refresh = credentialValue(cookie, "refresh_token", "refreshToken");
        }
        if (refresh.isBlank()) {
            return "";
        }
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("grant_type", "refresh_token");
            body.put("client_id", WEB_CLIENT_ID);
            body.put("refresh_token", refresh);
            Map<String, String> headers = CloudHttp.headers(
                    "Content-Type", "application/json",
                    "X-Device-Id", device,
                    "X-Client-Id", WEB_CLIENT_ID,
                    "Origin", "https://pan.xunlei.com",
                    "Referer", "https://pan.xunlei.com/",
                    "User-Agent", DESKTOP_UA,
                    "Sec-Ch-Ua", DESKTOP_SEC_CH_UA,
                    "Sec-Ch-Ua-Mobile", "?0",
                    "Sec-Ch-Ua-Platform", "\"Windows\"");
            String text = ctx.http().postJson(REFRESH_URL, MiniJson.write(body), headers);
            Object json = CloudHttp.json(text);
            String access = firstNonBlank(CloudHttp.str(json, "access_token"),
                    CloudHttp.str(json, "accessToken"));
            if (access.isBlank()) {
                return "";
            }
            refreshedAccessTokens.put(cookie, access);
            String newRefresh = firstNonBlank(CloudHttp.str(json, "refresh_token"),
                    CloudHttp.str(json, "refreshToken"));
            if (!newRefresh.isBlank()) {
                refreshedRefreshTokens.put(cookie, newRefresh);
            }
            return access;
        } catch (Exception e) {
            return "";
        }
    }

    private String accessToken(CloudContext ctx, CloudShare share) {
        String cookie = ctx.cookieText();
        String refreshed = refreshedAccessTokens.getOrDefault(cookie, "");
        if (!refreshed.isBlank()) {
            return refreshed;
        }
        String token = credentialValue(cookie, "access_token", "accessToken");
        if (token.isBlank() && looksLikeToken(cookie.trim())) {
            token = stripBearer(cookie.trim());
        }
        return token;
    }

    private static String credentialValue(String raw, String... keys) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String text = raw.trim();
        if (text.startsWith("{")) {
            try {
                Object node = MiniJson.parse(text);
                for (int depth = 0; depth < 3; depth++) {
                    if (!(node instanceof Map<?, ?> map)) {
                        break;
                    }
                    String found = findStringValue(map, keys);
                    if (!found.isBlank()) {
                        return stripBearer(found);
                    }
                    Object nested = null;
                    for (String key : new String[]{"credentials", "token", "data"}) {
                        Object value = CloudHttp.at(node, key);
                        if (value instanceof Map<?, ?>) {
                            nested = value;
                            break;
                        }
                    }
                    if (nested == null) {
                        break;
                    }
                    node = nested;
                }
            } catch (Exception ignored) {
                // 不是合法 JSON 时继续按 Cookie 文本解析
            }
        }
        for (String part : text.split(";")) {
            int eq = part.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = part.substring(0, eq).trim();
            String value = part.substring(eq + 1).trim();
            for (String wanted : keys) {
                if (wanted.equalsIgnoreCase(key)) {
                    String token = stripBearer(value);
                    if (!token.isBlank()) {
                        return token;
                    }
                }
            }
        }
        return "";
    }

    private static String findStringValue(Map<?, ?> map, String... keys) {
        for (String key : keys) {
            Object value = map.get(key);
            if (value instanceof String s && !s.isBlank() && !hasControlChars(s)) {
                return s.trim();
            }
            if (value instanceof Number n) {
                return String.valueOf(n);
            }
        }
        return "";
    }

    private String deviceId(CloudContext ctx) {
        String raw = ctx.cookieText();
        String fromJson = credentialValue(raw, "device_id", "deviceId");
        if (!fromJson.isBlank()) {
            return normalizeDeviceId(fromJson);
        }
        for (String part : raw.split(";")) {
            int eq = part.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = part.substring(0, eq).trim();
            if (!"deviceid".equalsIgnoreCase(key) && !"device_id".equalsIgnoreCase(key)) {
                continue;
            }
            String value = part.substring(eq + 1).trim();
            try {
                value = URLDecoder.decode(value, StandardCharsets.UTF_8);
            } catch (Exception ignored) {
                // 非 URL 编码的设备号按原值使用
            }
            if (!value.isBlank()) {
                return normalizeDeviceId(value);
            }
        }
        return guestDeviceId;
    }

    private static String normalizeDeviceId(String value) {
        String text = value.trim();
        int dot = text.indexOf('.');
        if (dot > -1 && text.length() > 32) {
            String rest = text.substring(dot + 1);
            if (rest.length() >= 32) {
                return rest.substring(0, 32);
            }
        }
        return text;
    }

    private static String jwtSub(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length < 2) {
                return "";
            }
            String payload = new String(Base64.getUrlDecoder().decode(parts[1]),
                    StandardCharsets.UTF_8);
            Object json = MiniJson.parse(payload);
            return CloudHttp.str(json, "sub");
        } catch (Exception e) {
            return "";
        }
    }

    private static void collectFiles(Map<String, Object> data, String parentId,
                                     String basePath, List<CloudFile> result) {
        for (Object item : CloudHttp.list(data, "files")) {
            if (!(item instanceof Map<?, ?>)) {
                continue;
            }
            String id = CloudHttp.str(item, "id");
            if (id.isBlank()) {
                continue;
            }
            String name = CloudHttp.str(item, "name");
            long size = CloudHttp.lng(item, "size");
            boolean folder = "drive#folder".equals(CloudHttp.str(item, "kind"));
            result.add(new CloudFile(id, name, size, folder)
                    .parentId(parentId)
                    .path(basePath));
        }
    }

    private static void checkShareStatus(Map<String, Object> data) throws CloudException {
        String status = CloudHttp.str(data, "share_status");
        if ("PASS_CODE_EMPTY".equals(status)) {
            throw new CloudException("请输入提取码");
        }
        if ("PASS_CODE_ERROR".equals(status)) {
            throw new CloudException("提取码错误");
        }
        if ("PASS_CODE_NEED".equals(status)) {
            throw new CloudException("该分享需要提取码");
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private static String enc(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static String randomHex(int length) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append("0123456789abcdef".charAt(random.nextInt(16)));
        }
        return sb.toString();
    }

    private static String md5Hex(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0x0f, 16))
                        .append(Character.forDigit(b & 0x0f, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("MD5 不可用", e);
        }
    }

    private static String stripBearer(String value) {
        String text = value.trim();
        if (text.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return text.substring(7).trim();
        }
        return text;
    }

    private static boolean hasControlChars(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x20 || c == 0x7f) {
                return true;
            }
        }
        return false;
    }

    private static boolean looksLikeToken(String value) {
        String text = stripBearer(value);
        if (text.length() < 16 || text.length() > 16384) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!Character.isLetterOrDigit(c) && "._~+/=-".indexOf(c) < 0) {
                return false;
            }
        }
        return !"null".equals(text) && !"undefined".equals(text);
    }
}
