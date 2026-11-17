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

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 蓝奏云（LANZOU）分享解析。
 *
 * <p>匿名分享链路：分享页 HTML → 同源 iframe（如有）→ ajaxm/ajaxfile 参数 →
 * 下载节点 HEAD/302 → 验证页处理 → 最终直链。蓝奏可能返回 {@code acw_sc__v2}
 * 人机校验页，本实现按公开的固定字符串变换在本地计算 Cookie 后重放。</p>
 */
public final class LanzouProvider implements CloudProvider {

    private static final String WEB_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";
    private static final int MAX_FOLDER_PAGES = 500;
    private static final String DOWN_IP_COOKIE = "down_ip=1";

    /** 分享域名族：lanzou* / lan[zs]o[ux] / lanzn / ulanzou / woozooo，以及 t-is.cn。 */
    private static final Pattern HOST_PATTERN = Pattern.compile(
            "^(?:[a-z0-9-]+\\.)*(?:lanzou[a-z0-9-]*|lan[zs]o[ux]|lanzn|ulanzou|woozooo)"
                    + "\\.(?:com|net|org|cn)$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TIS_HOST_PATTERN = Pattern.compile(
            "^(?:[a-z0-9-]+\\.)*t-is\\.cn$", Pattern.CASE_INSENSITIVE);

    private static final Pattern PWD_QUERY = Pattern.compile(
            "[?&](?:pwd|pass|passcode|password)=([A-Za-z0-9]+)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern IFRAME_PATTERN = Pattern.compile(
            "<iframe[^>]+src=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern FILE_ID_AJAX = Pattern.compile(
            "(?:ajaxm|ajaxfile)\\.php\\?file=(\\d+)");
    private static final Pattern FILE_ID_INPUT = Pattern.compile(
            "name=[\"']file[\"'][^>]*value=[\"'](\\d+)[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern FILE_ID_ANY = Pattern.compile("file=(\\d+)");
    private static final Pattern ABS_AJAX = Pattern.compile(
            "https?://[^'\"\\s]+/(?:ajaxm|ajaxfile)\\.php\\?file=\\d+");
    private static final Pattern TITLE_PATTERN = Pattern.compile(
            "<title>([^<]*)</title>", Pattern.CASE_INSENSITIVE);
    private static final Pattern CLASS_N_PATTERN = Pattern.compile(
            "class=[\"']n[\"']>([^<]+)<");
    private static final Pattern VAR_FILENAME_PATTERN = Pattern.compile(
            "var\\s+filename\\s*=\\s*['\"]([^'\"]+)['\"]");
    private static final Pattern DISPLAY_SIZE_PATTERN = Pattern.compile(
            "(?:文件大小|大小)\\s*[:：]\\s*([\\d.]+)\\s*([KMGT]?)(?:i?B)?",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SIZE_PATTERN = Pattern.compile(
            "([\\d.]+)\\s*([KMGT]?)", Pattern.CASE_INSENSITIVE);
    private static final Pattern ACW_ARG1 = Pattern.compile(
            "\\barg1\\s*=\\s*['\"]([0-9A-Fa-f]{40})['\"]");
    private static final Pattern META_CHARSET = Pattern.compile(
            "charset\\s*=\\s*[\"']?([A-Za-z0-9_-]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern VERIFY_DATA = Pattern.compile(
            "data\\s*:\\s*\\{([^{}]*(?:\\{[^{}]*\\}[^{}]*)*)\\}", Pattern.DOTALL);
    private static final Pattern VERIFY_FILE_JS = Pattern.compile(
            "['\"]?file['\"]?\\s*:\\s*['\"]([^'\"]+)['\"]");
    private static final Pattern VERIFY_SIGN_JS = Pattern.compile(
            "['\"]?sign['\"]?\\s*:\\s*['\"]([^'\"]+)['\"]");
    private static final Pattern VERIFY_FILE_INPUT = Pattern.compile(
            "<input[^>]*name=[\"']file[\"'][^>]*value=[\"']([^\"']+)[\"']",
            Pattern.CASE_INSENSITIVE);

    /** acw_sc__v2 位置表（1-based）：把 arg1 的第 pos[z] 个字符放到第 z 位。 */
    private static final int[] ACW_POS = {
            15, 35, 29, 24, 33, 16, 1, 38, 10, 9, 19, 31, 40, 27, 22, 23, 25, 13, 6, 11,
            39, 18, 20, 8, 14, 21, 32, 26, 2, 30, 7, 4, 17, 5, 3, 28, 34, 37, 12, 36
    };
    private static final String ACW_MASK = "3000176000856006061501533003690027800375";

    /** 域名失效时的备用节点（仅在 DNS/连接失败时按同一路径重试）。 */
    private static final List<String> FALLBACK_HOSTS = List.of(
            "pan.lanzou.com", "pan.lanzoui.com", "pan.lanzoux.com", "pan.lanzouj.com",
            "www.lanzou.com", "www.lanzoui.com", "www.lanzoux.com", "www.lanzouj.com");

    @Override
    public CloudDrive drive() {
        return CloudDrive.LANZOU;
    }

    @Override
    public boolean matches(String text) {
        if (!CloudLinks.containsUrl(text)) {
            return false;
        }
        String url = CloudLinks.extractUrl(text);
        String host = CloudLinks.hostOf(url);
        if (!isLanzouHost(host)) {
            return false;
        }
        String first = firstPathSegment(CloudLinks.pathOf(url));
        return !first.isEmpty() && !first.contains(".");
    }

    @Override
    public CloudShare parse(String text) throws CloudException {
        if (!matches(text)) {
            throw new CloudException("无法识别蓝奏云分享链接");
        }
        String url = CloudLinks.extractUrl(text);
        String normalized = normalizeShareUrl(url);
        String host = CloudLinks.hostOf(normalized);
        String shareId = firstPathSegment(CloudLinks.pathOf(normalized));
        if (shareId.isEmpty() || shareId.contains(".")) {
            throw new CloudException("无法识别蓝奏云分享编号");
        }
        return new CloudShare(drive(), shareId, passcodeFrom(text, normalized),
                normalized, host);
    }

    @Override
    public List<CloudFile> list(CloudContext ctx, CloudShare share, CloudFile dir)
            throws Exception {
        ctx.seedCookiesForHost(share.host());
        SharePage page = loadPage(ctx, share);
        if (page.needsPwd && share.passcode().isEmpty()) {
            throw new CloudException("此蓝奏分享需要提取码，请填写后重新解析");
        }
        // 蓝奏文件夹分享只提供平铺文件列表，没有可下钻的子目录。
        if (dir != null) {
            return Collections.emptyList();
        }
        if (!page.folder) {
            String name = singleFileName(page);
            if (isGenericName(name, page.shareId)) {
                // 带密码的单文件分享页标题只是“文件”，真实文件名要等提交提取码后才随响应返回。
                String cached = share.extra().getOrDefault("lanzouFileName", "");
                if (cached.isBlank() && !share.passcode().isEmpty()
                        && page.fileId.matches("\\d+")) {
                    cached = discoverFileName(ctx, share, page);
                    if (!cached.isBlank()) {
                        share.extra().put("lanzouFileName", cached);
                    }
                }
                name = cached.isBlank() ? name : cached;
            }
            CloudFile file = new CloudFile(page.shareId, name, page.displaySize, false);
            file.parentId("").path("/");
            return Collections.singletonList(file);
        }
        return listFolder(ctx, page, share.passcode());
    }

    @Override
    public CloudLink resolve(CloudContext ctx, CloudShare share, CloudFile file)
            throws Exception {
        ctx.seedCookiesForHost(share.host());
        SharePage page = loadPage(ctx, share);
        if (page.needsPwd && share.passcode().isEmpty()) {
            throw new CloudException("此蓝奏分享需要提取码，请填写后重新解析");
        }
        if (file.folder()) {
            throw new CloudException("文件夹无法直接下载，请选择其中的文件");
        }

        String html = page.html;
        String origin = page.origin;
        String referer = page.shareUrl;
        // 文件夹分享里的文件用条目 id 打开独立下载页，下载参数在页面的 /fn 同源 iframe 内。
        if (page.folder) {
            PageFetch item = fetchLanzouPage(ctx, origin + "/" + file.id(), page.shareUrl);
            html = item.html;
            origin = originOf(item.url);
            referer = item.url;
            Matcher iframe = IFRAME_PATTERN.matcher(html);
            if (iframe.find()) {
                String src = absolutize(origin, iframe.group(1));
                String srcHost = hostOf(src);
                if (!srcHost.isEmpty() && srcHost.equalsIgnoreCase(hostOf(referer))) {
                    PageFetch inner = fetchLanzouPage(ctx, src, referer);
                    html = inner.html;
                    origin = originOf(inner.url);
                    referer = inner.url;
                }
            }
        } else if (!page.iframeUrl.isEmpty()) {
            String iframeHost = hostOf(page.iframeUrl);
            if (iframeHost.isEmpty()) {
                throw new CloudException("蓝奏下载页面地址异常，请检查分享链接");
            }
            if (!iframeHost.equalsIgnoreCase(hostOf(page.shareUrl))) {
                throw new CloudException("蓝奏下载页面地址异常，请检查分享链接");
            }
            html = fetchLanzouPage(ctx, page.iframeUrl, page.shareUrl).html;
        }

        String fileId = firstNonBlank(extractFileId(html), page.fileId, numericId(file.id()));
        if (!fileId.matches("\\d+")) {
            throw new CloudException("蓝奏解析失败，请确认分享链接和提取码后重试");
        }

        Map<String, Object> json = requestDownProcess(ctx, share, html, origin, fileId, referer);

        String dom = CloudHttp.str(json, "dom");
        String rel = CloudHttp.str(json, "url");
        if (dom.isEmpty() || rel.isEmpty()
                || rel.startsWith("/") || rel.contains("://")) {
            throw new CloudException("蓝奏解析失败，请确认分享链接和提取码后重试");
        }
        String domOrigin = schemeHost(dom);
        if (domOrigin.isEmpty()) {
            throw new CloudException("蓝奏解析失败，请确认分享链接和提取码后重试");
        }
        String jump = domOrigin + "/file/" + rel;
        String finalUrl = probeAndVerify(ctx, jump, origin);

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("User-Agent", WEB_UA);
        headers.put("Referer", originOf(finalUrl) + "/");
        headers.put("Cookie", DOWN_IP_COOKIE);
        return new CloudLink(finalUrl, headers);
    }

    /**
     * 提交蓝奏下载申请（downprocess）。新版 websign 与旧版密码协议在这里统一处理。
     */
    private Map<String, Object> requestDownProcess(CloudContext ctx, CloudShare share,
                                                   String html, String origin,
                                                   String fileId, String referer) throws Exception {
        boolean withPwd = !share.passcode().isEmpty();
        // 新版 websign 协议（页面带 ajaxdata/wp_sign）优先，它同时用于无密码分享和文件夹条目下载。
        boolean webSign = !orEmpty(jsVar(html, "ajaxdata")).isEmpty()
                || !orEmpty(jsVar(html, "wp_sign")).isEmpty();
        String ajaxUrl = resolveAjaxUrl(html, origin, fileId, withPwd && !webSign);
        String sign = firstNonBlank(
                jsVar(html, "wp_sign"),
                jsVar(html, "isngis"),
                jsVar(html, "sign"),
                inputValue(html, "sign"));
        if (sign.isEmpty()) {
            throw new CloudException("蓝奏分享页缺少下载参数，请重新解析");
        }

        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("action", "downprocess");
        fields.put("sign", sign);
        if (webSign) {
            // 新版协议：websignkey/signs 用 ajaxdata，websign 为空，kd 用 kdns。
            String key = firstNonBlank(
                    jsVar(html, "ajaxdata"),
                    jsVar(html, "websignkey"),
                    inputValue(html, "websignkey"));
            fields.put("signs", firstNonBlank(jsVar(html, "signs"), key));
            fields.put("websignkey", key);
            fields.put("websign", firstNonBlank(jsVar(html, "websign"), ""));
            fields.put("kd", firstNonBlank(jsVarRaw(html, "kdns"), "1"));
            fields.put("ves", "1");
        } else if (withPwd) {
            fields.put("p", share.passcode());
            fields.put("kd", "1");
        } else {
            String key = firstNonBlank(
                    jsVar(html, "ajaxdata"),
                    jsVar(html, "websignkey"),
                    inputValue(html, "websignkey"));
            fields.put("signs", firstNonBlank(jsVar(html, "signs"), key));
            fields.put("websignkey", key);
            fields.put("websign", firstNonBlank(jsVar(html, "websign"), ""));
            fields.put("kd", firstNonBlank(jsVarRaw(html, "kdns"), "1"));
            fields.put("ves", "1");
        }

        String body = postFormNoRedirect(ctx, ajaxUrl, fields, referer, origin);
        Map<String, Object> json = parseJsonMap(body, "蓝奏解析服务响应异常，请稍后重试");
        validateAjax(json);
        return json;
    }

    /** 带密码的单文件分享：提交提取码后从响应 inf 字段取得真实文件名。失败则返回空串。 */
    private String discoverFileName(CloudContext ctx, CloudShare share, SharePage page) {
        try {
            Map<String, Object> json = requestDownProcess(ctx, share, page.html,
                    page.origin, page.fileId, page.shareUrl);
            return CloudHttp.str(json, "inf").trim();
        } catch (Exception ignored) {
            return "";
        }
    }

    private static boolean isGenericName(String name, String shareId) {
        return name.isBlank() || "文件".equals(name) || name.equals(shareId);
    }

    // ───────────────────────── 分享页与分页 ─────────────────────────

    private SharePage loadPage(CloudContext ctx, CloudShare share) throws Exception {
        String normalized = normalizeShareUrl(share.rawUrl());
        if (normalized.isEmpty()) {
            normalized = "https://" + share.host() + "/" + share.shareKey();
        }
        PageFetch fetched = fetchWithFallback(ctx, normalized);
        String html = fetched.html;
        String shareUrl = fetched.url;
        String origin = originOf(shareUrl);

        String title = extractTitle(html);
        boolean needsPwd = html.contains("请输入密码")
                || html.contains("id=\"pwbox\"")
                || html.contains("input_password")
                || html.contains("密码不正确");
        boolean folder = html.contains("filemoreajax")
                || html.contains("class=\"filemore\"")
                || html.contains("file_more");

        String iframeUrl = "";
        Matcher iframe = IFRAME_PATTERN.matcher(html);
        if (iframe.find()) {
            iframeUrl = absolutize(origin, iframe.group(1));
        }

        String fileId = extractFileId(html);
        String lx = firstNonBlank(folderField(html, "lx"), jsVar(html, "lx"));
        String fid = firstNonBlank(folderField(html, "fid"), jsVar(html, "fid"));
        String t = firstNonBlank(folderFieldRef(html, "t"), jsVar(html, "t"));
        String k = firstNonBlank(folderFieldRef(html, "k"), jsVar(html, "k"));
        String shareId = lastPathSegment(CloudLinks.pathOf(shareUrl));
        if (fid.isEmpty()) {
            fid = shareId;
        }
        if (shareId.isEmpty()) {
            shareId = share.shareKey();
        }

        long displaySize = 0;
        Matcher size = DISPLAY_SIZE_PATTERN.matcher(html);
        if (size.find()) {
            displaySize = parseDisplaySize(size.group(1) + " " + size.group(2));
        }

        return new SharePage(shareUrl, origin, title, folder, needsPwd, iframeUrl,
                fileId, lx, fid, t, k,
                folderField(html, "uid"), folderField(html, "puid"),
                firstNonBlank(folderField(html, "rep"), "0"),
                firstNonBlank(folderField(html, "up"), "1"),
                firstNonBlank(folderField(html, "ls"), "1"),
                html, shareId, displaySize);
    }

    private List<CloudFile> listFolder(CloudContext ctx, SharePage page, String passcode)
            throws Exception {
        if (page.lx.isEmpty() || page.fid.isEmpty()
                || page.t.isEmpty() || page.k.isEmpty()) {
            throw new CloudException("蓝奏分享分页参数缺失，请重新解析");
        }

        List<CloudFile> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int pageNo = 1;
        int retry = 0;
        while (pageNo <= MAX_FOLDER_PAGES) {
            Map<String, String> form = new LinkedHashMap<>();
            form.put("lx", page.lx);
            form.put("fid", page.fid);
            form.put("uid", page.uid);
            form.put("puid", page.puid);
            form.put("rep", page.rep);
            form.put("up", page.up);
            form.put("ls", page.ls);
            form.put("t", page.t);
            form.put("k", page.k);
            form.put("pwd", passcode == null ? "" : passcode);
            form.put("pg", String.valueOf(pageNo));

            String body = postFormNoRedirect(
                    ctx, page.origin + "/filemoreajax.php", form, page.shareUrl, page.origin);
            Map<String, Object> json = parseJsonMap(body, "蓝奏解析服务响应异常，请稍后重试");
            long zt = CloudHttp.lng(json, "zt");
            if (zt == 2) {
                break;
            }
            if (zt == 3) {
                throw new CloudException("蓝奏提取码错误，请检查后重新解析");
            }
            if (zt == 4) {
                retry++;
                if (retry >= 5) {
                    throw new CloudException("蓝奏请求过于频繁，请稍后再试");
                }
                Thread.sleep(1500L * retry);
                continue;
            }
            if (zt != 1) {
                throw new CloudException("蓝奏解析失败，请确认分享链接和提取码后重试");
            }
            retry = 0;

            List<Object> items = CloudHttp.list(json, "text");
            if (items.isEmpty()) {
                break;
            }
            int added = 0;
            for (Object item : items) {
                String id = CloudHttp.str(item, "id");
                String key = id;
                if (key.isEmpty() || !seen.add(key)) {
                    continue;
                }
                String name = firstNonBlank(
                        CloudHttp.str(item, "name_all"),
                        CloudHttp.str(item, "name"));
                long size = parseDisplaySize(CloudHttp.str(item, "size"));
                CloudFile file = new CloudFile(key, name.isEmpty() ? key : name, size, false);
                file.parentId("").path("/");
                result.add(file);
                added++;
            }
            if (added == 0) {
                throw new CloudException("蓝奏分页重复，请重新打开目录");
            }
            pageNo++;
        }
        return result;
    }

    // ───────────────────────── 下载节点 ─────────────────────────

    private String probeAndVerify(CloudContext ctx, String jump, String shareOrigin)
            throws Exception {
        String url = jump;
        Set<String> visited = new HashSet<>();
        Set<String> challengedHosts = new HashSet<>();
        Set<String> verifiedHosts = new HashSet<>();
        Map<String, Map<String, String>> cookieJar = new LinkedHashMap<>();
        putCookie(cookieJar, hostOf(jump), "down_ip", "1");

        for (int hop = 0; hop < 8; hop++) {
            String host = hostOf(url);
            if (host.isEmpty()) {
                throw new CloudException("蓝奏下载地址暂时不可用，请稍后重试");
            }
            // Cookie 参与判重：解出 acw_sc__v2 后允许带新 Cookie 再试一次同一地址。
            if (!visited.add(url + "|" + cookieHeader(cookieJar, host))) {
                throw new CloudException("蓝奏下载地址循环跳转，请重新解析");
            }
            putCookie(cookieJar, host, "down_ip", "1");

            HttpResponse<String> response = headRequest(
                    ctx, url, shareOrigin, cookieHeader(cookieJar, host));
            int code = response.statusCode();
            if (code >= 300 && code < 400) {
                String location = response.headers().firstValue("Location").orElse("");
                if (location.isBlank()) {
                    throw new CloudException("蓝奏下载地址暂时不可用，请稍后重试");
                }
                url = CloudHttp.absoluteUrl(url, location);
                continue;
            }
            if (code == 200 || code == 206) {
                String contentType = response.headers()
                        .firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);
                if (contentType.contains("text/html")) {
                    String body = getText(
                            ctx, url, shareOrigin, cookieHeader(cookieJar, host));
                    String challenge = challengeCookie(body);
                    if (challenge != null) {
                        if (!challengedHosts.add(host)) {
                            throw new CloudException(
                                    "蓝奏需要进一步验证，请在分享页完成验证后重试");
                        }
                        putCookie(cookieJar, host, "acw_sc__v2", challenge);
                        continue;
                    }
                    if (body.contains("验证并下载")) {
                        if (verifiedHosts.add(host)) {
                            String verified = submitVerify(ctx, url, body);
                            if (!verified.isEmpty()) {
                                url = verified;
                                continue;
                            }
                        }
                        throw new CloudException(
                                "蓝奏需要进一步验证，请在分享页完成验证后重试");
                    }
                    throw new CloudException(
                            "蓝奏返回的是验证页面，未创建下载任务，请在分享页完成验证后重试");
                }
                long size = sizeOf(response, code);
                if (size <= 0) {
                    rangeProbe(ctx, url, shareOrigin, cookieHeader(cookieJar, host));
                }
                return url;
            }
            if (code == 403 || code == 404 || code == 410) {
                throw new CloudException("蓝奏下载地址已失效，请稍后重新解析");
            }
            if (code == 429) {
                throw new CloudException("蓝奏下载请求过于频繁，请稍后再试");
            }
            long size = rangeProbe(ctx, url, shareOrigin, cookieHeader(cookieJar, host));
            if (size > 0) {
                return url;
            }
            throw new CloudException("蓝奏下载地址暂时不可用，请稍后重试");
        }
        throw new CloudException("蓝奏下载地址暂时不可用，请稍后重试");
    }

    private String submitVerify(CloudContext ctx, String nodeUrl, String html)
            throws Exception {
        String data = findVerifyData(html);
        String file = data == null ? null : regexGroup(data, VERIFY_FILE_JS);
        if (file == null || file.isEmpty()) {
            file = regexGroup(html, VERIFY_FILE_INPUT);
        }
        if (file == null || file.isEmpty()) {
            return "";
        }
        String sign = data == null ? null : regexGroup(data, VERIFY_SIGN_JS);
        if (sign == null || sign.isEmpty()) {
            sign = jsVar(html, "sign");
        }
        if (sign == null || sign.isEmpty()) {
            sign = inputValue(html, "sign");
        }
        if (sign == null || sign.isEmpty()) {
            return "";
        }

        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        }

        String origin = originOf(nodeUrl);
        String ajaxUrl;
        try {
            ajaxUrl = URI.create(nodeUrl).resolve("ajax.php").toString();
        } catch (Exception e) {
            ajaxUrl = origin + "/ajax.php";
        }
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("file", file);
        fields.put("el", "2");
        fields.put("sign", sign);

        String body = postFormNoRedirect(ctx, ajaxUrl, fields, nodeUrl, origin);
        for (int attempt = 0; attempt < 2; attempt++) {
            String challenge = challengeCookie(body);
            if (challenge == null) {
                break;
            }
            body = postFormNoRedirect(ctx, ajaxUrl, fields, nodeUrl, origin,
                    "acw_sc__v2=" + challenge);
        }
        Map<String, Object> json = parseJsonMap(
                body, "蓝奏下载验证服务暂时不可用，请稍后重试");
        long zt = CloudHttp.lng(json, "zt");
        String url = CloudHttp.str(json, "url");
        if (zt != 1 || url.isEmpty()) {
            throw new CloudException(url.isEmpty()
                    ? "蓝奏需要进一步验证，请在分享页完成验证后重试" : url);
        }
        if ("?SignError".equals(url)) {
            throw new CloudException("蓝奏下载验证已过期，请重新解析");
        }
        return url;
    }

    private static String findVerifyData(String html) {
        Matcher matcher = VERIFY_DATA.matcher(html);
        while (matcher.find()) {
            String data = matcher.group(1);
            if (data.contains("'file'") || data.contains("\"file\"")) {
                return data;
            }
        }
        return null;
    }

    private static HttpResponse<String> headRequest(CloudContext ctx, String url,
                                                    String shareOrigin, String cookie)
            throws Exception {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("User-Agent", WEB_UA);
        headers.put("Referer", shareOrigin + "/");
        if (!cookie.isEmpty()) {
            headers.put("Cookie", cookie);
        }
        return ctx.http().send("HEAD", url, headers, null, false);
    }

    private static String getText(CloudContext ctx, String url, String shareOrigin,
                                  String cookie) throws Exception {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("User-Agent", WEB_UA);
        headers.put("Referer", shareOrigin + "/");
        if (!cookie.isEmpty()) {
            headers.put("Cookie", cookie);
        }
        HttpResponse<byte[]> response = ctx.http().sendBytes(
                "GET", url, headers, null, true);
        byte[] body = response.body();
        String encoding = response.headers()
                .firstValue("Content-Encoding").orElse("").toLowerCase(Locale.ROOT);
        if (encoding.contains("gzip")) {
            try (java.util.zip.GZIPInputStream in = new java.util.zip.GZIPInputStream(
                    new java.io.ByteArrayInputStream(body))) {
                body = in.readAllBytes();
            }
        }
        return decodeHtml(body, contentTypeOf(response));
    }

    private static long rangeProbe(CloudContext ctx, String url, String shareOrigin,
                                   String cookie) throws Exception {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("User-Agent", WEB_UA);
        headers.put("Referer", shareOrigin + "/");
        if (!cookie.isEmpty()) {
            headers.put("Cookie", cookie);
        }
        headers.put("Range", "bytes=0-8191");
        HttpResponse<byte[]> response = ctx.http().sendBytes(
                "GET", url, headers, null, false);
        return sizeOf(response, response.statusCode());
    }

    private static long sizeOf(HttpResponse<?> response, int code) {
        long size = lastNumber(response.headers().firstValue("Content-Range").orElse(""));
        if (size > 0) {
            return size;
        }
        if (code == 200) {
            return parseLong(response.headers().firstValue("Content-Length").orElse(""));
        }
        return 0;
    }

    private static long lastNumber(String value) {
        if (value == null || value.isEmpty()) {
            return 0;
        }
        int slash = value.lastIndexOf('/');
        String tail = slash >= 0 ? value.substring(slash + 1) : value;
        return parseLong(tail);
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value == null ? "" : value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ───────────────────────── 页面抓取 ─────────────────────────

    private PageFetch fetchWithFallback(CloudContext ctx, String normalized)
            throws Exception {
        IOException last = null;
        for (String candidate : candidateUrls(normalized)) {
            try {
                return fetchLanzouPage(ctx, candidate, null);
            } catch (IOException e) {
                if (!(e instanceof UnknownHostException)
                        && !(e instanceof ConnectException)) {
                    throw e;
                }
                last = e;
            }
        }
        throw new CloudException("蓝奏分享域名暂时无法访问，请检查网络后重试", last);
    }

    private PageFetch fetchLanzouPage(CloudContext ctx, String startUrl, String referer)
            throws Exception {
        String current = startUrl;
        Set<String> challengedHosts = new HashSet<>();
        for (int hop = 0; hop < 8; hop++) {
            String host = hostOf(current);
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("User-Agent", WEB_UA);
            headers.put("Referer", referer != null && hop == 0 ? referer : current);
            HttpResponse<byte[]> response = ctx.http().sendBytes(
                    "GET", current, headers, null, false);
            int code = response.statusCode();

            if (code >= 300 && code < 400) {
                String location = response.headers().firstValue("Location").orElse("");
                if (location.isBlank()) {
                    throw new CloudException("蓝奏页面跳转次数过多，请重新解析");
                }
                String next = CloudHttp.absoluteUrl(current, location);
                String nextHost = hostOf(next);
                if (nextHost.isEmpty() || !isLanzouHost(nextHost)) {
                    throw new CloudException("蓝奏分享链接无效");
                }
                current = next;
                continue;
            }
            if (code == 429) {
                throw new CloudException("蓝奏请求过于频繁，请稍后再试");
            }

            String html = decodeHtml(response.body(), contentTypeOf(response));
            if (html.length() > 2_000_000) {
                throw new CloudException("蓝奏分享页面过大或异常");
            }
            if (html.isBlank()) {
                throw new CloudException("蓝奏分享页面为空，请检查链接后重试");
            }
            String challenge = challengeCookie(html);
            if (challenge != null) {
                if (!challengedHosts.add(host)) {
                    throw new CloudException("蓝奏需要进一步验证，请在分享页完成验证后重试");
                }
                ctx.http().seedCookies(host, "acw_sc__v2=" + challenge);
                continue;
            }
            if (html.contains("文件已取消") || html.contains("不存在")) {
                throw new CloudException("蓝奏文件已取消分享或不存在");
            }
            if (html.contains("请求过于频繁") || html.contains("稍后再试")) {
                throw new CloudException("蓝奏请求过于频繁，请稍后再试");
            }
            if (code >= 400 && html.isBlank()) {
                throw new CloudException("蓝奏页面请求失败（HTTP " + code + "）");
            }
            return new PageFetch(current, html);
        }
        throw new CloudException("蓝奏页面跳转次数过多，请重新解析");
    }

    private static List<String> candidateUrls(String url) {
        List<String> candidates = new ArrayList<>();
        candidates.add(url);
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            String path = uri.getRawPath() == null ? "" : uri.getRawPath();
            String query = uri.getRawQuery();
            String tail = path + (query == null ? "" : "?" + query);
            for (String fallback : FALLBACK_HOSTS) {
                if (!fallback.equalsIgnoreCase(host)) {
                    candidates.add("https://" + fallback + tail);
                }
            }
        } catch (Exception ignored) {
            // 原链接不合法时只尝试原地址
        }
        return candidates;
    }

    // ───────────────────────── 表单请求 ─────────────────────────

    private String postFormNoRedirect(CloudContext ctx, String url,
                                      Map<String, String> fields,
                                      String referer, String origin) throws Exception {
        return postFormNoRedirect(ctx, url, fields, referer, origin, "");
    }

    private String postFormNoRedirect(CloudContext ctx, String url,
                                      Map<String, String> fields,
                                      String referer, String origin,
                                      String cookie) throws Exception {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("User-Agent", WEB_UA);
        headers.put("Referer", referer);
        headers.put("Origin", origin);
        headers.put("X-Requested-With", "XMLHttpRequest");
        headers.put("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
        if (cookie != null && !cookie.isEmpty()) {
            headers.put("Cookie", cookie);
        }
        HttpResponse<byte[]> response = ctx.http().sendBytes(
                "POST", url, headers, encodeForm(fields), false);
        if (response.statusCode() == 429) {
            throw new CloudException("蓝奏请求过于频繁，请稍后再试");
        }
        String body = decodeBody(response);
        if (body.length() > 4_000_000) {
            throw new CloudException("蓝奏解析服务返回内容过大，请稍后重试");
        }
        if (response.statusCode() >= 400 && body.isBlank()) {
            throw new CloudException("蓝奏解析服务响应异常（HTTP "
                    + response.statusCode() + "），请稍后重试");
        }
        return body;
    }

    private static String encodeForm(Map<String, String> fields) {
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<String, String> entry : fields.entrySet()) {
            if (builder.length() > 0) {
                builder.append('&');
            }
            builder.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
                    .append('=')
                    .append(URLEncoder.encode(
                            entry.getValue() == null ? "" : entry.getValue(),
                            StandardCharsets.UTF_8));
        }
        return builder.toString();
    }

    // ───────────────────────── 页面字段 ─────────────────────────

    private static String resolveAjaxUrl(String html, String origin, String fileId,
                                          boolean withPwd) {
        String domain1 = jsVar(html, "domain1");
        if (domain1 != null && domain1.contains("/ajax")) {
            return domain1;
        }
        String domain2 = jsVar(html, "domain2");
        if (domain2 != null && domain2.contains("/ajax")) {
            return domain2;
        }
        Matcher matcher = ABS_AJAX.matcher(html);
        if (matcher.find()) {
            return matcher.group();
        }
        return origin + (withPwd
                ? "/ajaxfile.php?file=" : "/ajaxm.php?file=") + fileId;
    }

    private static String extractFileId(String html) {
        Matcher matcher = FILE_ID_AJAX.matcher(html);
        if (matcher.find()) {
            return matcher.group(1);
        }
        matcher = FILE_ID_INPUT.matcher(html);
        if (matcher.find()) {
            return matcher.group(1);
        }
        matcher = FILE_ID_ANY.matcher(html);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return "";
    }

    /** 读取 foldermoreajax 数据对象里的字面量字段（如 {@code 'lx':2}）。 */
    private static String folderField(String html, String name) {
        Matcher matcher = Pattern.compile(
                "['\"]" + Pattern.quote(name) + "['\"]\\s*:\\s*['\"]?([A-Za-z0-9_.-]+)['\"]?")
                .matcher(html);
        return matcher.find() ? matcher.group(1) : "";
    }

    /** 读取 foldermoreajax 数据对象字段，值为变量名时解析成变量内容。 */
    private static String folderFieldRef(String html, String name) {
        String value = folderField(html, name);
        if (value.isEmpty()) {
            return "";
        }
        String resolved = jsVar(html, value);
        if (resolved == null) {
            resolved = jsVarRaw(html, value);
        }
        return resolved == null ? value : resolved;
    }

    /**
     * 单文件分享的真实文件名：页面 &lt;title&gt; 有时只是“文件下载”，
     * 优先取页面里的 filename 变量或名称节点，取不到再退回标题。
     */
    private static String singleFileName(SharePage page) {
        Matcher matcher = VAR_FILENAME_PATTERN.matcher(page.html);
        if (matcher.find()) {
            String name = matcher.group(1).trim();
            if (!name.isEmpty()) {
                return name;
            }
        }
        matcher = CLASS_N_PATTERN.matcher(page.html);
        if (matcher.find()) {
            String name = matcher.group(1).trim();
            if (!name.isEmpty()) {
                return name;
            }
        }
        return page.title.isEmpty() ? page.shareId : page.title;
    }

    private static String extractTitle(String html) {
        Matcher matcher = TITLE_PATTERN.matcher(html);
        if (matcher.find()) {
            String title = matcher.group(1)
                    .replace(" - 蓝奏云", "")
                    .replace("蓝奏云", "")
                    .trim();
            if (!title.isEmpty() && !title.contains("密码")) {
                return title;
            }
        }
        matcher = CLASS_N_PATTERN.matcher(html);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        matcher = VAR_FILENAME_PATTERN.matcher(html);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return "";
    }

    private static String jsVar(String html, String name) {
        Pattern pattern = Pattern.compile(
                "(?:(?:var|let|const)\\s+)?" + Pattern.quote(name)
                        + "\\s*=\\s*['\"]([^'\"]*)['\"]");
        Matcher matcher = pattern.matcher(html);
        String value = null;
        // 页面可能先声明空值再赋真实值（如 var isngis = ''; var isngis = 'xxx';），取最后一个非空值。
        while (matcher.find()) {
            String found = matcher.group(1);
            if (found != null && !found.isEmpty()) {
                value = found;
            } else if (value == null) {
                value = "";
            }
        }
        return value;
    }

    /** 读取未加引号的 JS 变量值（如 {@code var kdns = 0}）。 */
    private static String jsVarRaw(String html, String name) {
        Pattern pattern = Pattern.compile(
                "(?:var|let|const)\\s+" + Pattern.quote(name)
                        + "\\s*=\\s*([^;,\\n\\r]+)");
        Matcher matcher = pattern.matcher(html);
        if (!matcher.find()) {
            return null;
        }
        return matcher.group(1).trim().replaceAll("^['\"]|['\"]$", "");
    }

    private static String inputValue(String html, String name) {
        Pattern pattern = Pattern.compile(
                "<input[^>]*name=[\"']" + Pattern.quote(name)
                        + "[\"'][^>]*value=[\"']([^\"']*)[\"']",
                Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(html);
        if (matcher.find()) {
            return matcher.group(1);
        }
        pattern = Pattern.compile(
                "<input[^>]*value=[\"']([^\"']*)[\"'][^>]*name=[\"']"
                        + Pattern.quote(name) + "[\"']",
                Pattern.CASE_INSENSITIVE);
        matcher = pattern.matcher(html);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static void validateAjax(Map<String, Object> json) throws CloudException {
        if (CloudHttp.lng(json, "zt") == 1) {
            return;
        }
        String info = CloudHttp.str(json, "inf");
        if (info.contains("密码") || info.contains("提取码")) {
            throw new CloudException("蓝奏提取码错误，请检查后重新解析");
        }
        if (info.contains("不存在") || info.contains("取消")
                || info.contains("过期") || info.contains("删除")) {
            throw new CloudException("蓝奏文件已取消分享或不存在");
        }
        if (info.contains("频繁") || info.contains("次数") || info.contains("稍后")) {
            throw new CloudException("蓝奏请求过于频繁，请稍后再试");
        }
        throw new CloudException("蓝奏解析失败，请确认分享链接和提取码后重试");
    }

    // ───────────────────────── 编码 / 解码 ─────────────────────────

    private static Map<String, Object> parseJsonMap(String body, String fallback)
            throws CloudException {
        String trimmed = body == null ? "" : body.trim();
        if (trimmed.startsWith("<")) {
            throw new CloudException("蓝奏返回了验证页面，请稍后重试或在分享页完成验证");
        }
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

    private static String decodeBody(HttpResponse<byte[]> response) {
        byte[] bytes = response.body() == null ? new byte[0] : response.body();
        String encoding = response.headers()
                .firstValue("Content-Encoding").orElse("").toLowerCase(Locale.ROOT);
        if (encoding.contains("gzip") && bytes.length > 0) {
            try (java.util.zip.GZIPInputStream in = new java.util.zip.GZIPInputStream(
                    new java.io.ByteArrayInputStream(bytes))) {
                bytes = in.readAllBytes();
            } catch (IOException ignored) {
                // 不是合法 gzip 时按原字节处理
            }
        }
        Charset charset = charsetOf(contentTypeOf(response));
        if (charset != null) {
            return new String(bytes, charset);
        }
        String utf8 = new String(bytes, StandardCharsets.UTF_8);
        return utf8.indexOf('\uFFFD') < 0
                ? utf8 : new String(bytes, Charset.forName("GBK"));
    }

    private static String decodeHtml(byte[] bytes, String contentType) {
        byte[] data = bytes == null ? new byte[0] : bytes;
        Charset charset = charsetOf(contentType);
        if (charset == null) {
            String head = new String(data, 0, Math.min(data.length, 4096),
                    StandardCharsets.ISO_8859_1);
            Matcher matcher = META_CHARSET.matcher(head);
            if (matcher.find()) {
                charset = charsetByName(matcher.group(1));
            }
        }
        if (charset != null) {
            return new String(data, charset);
        }
        String utf8 = new String(data, StandardCharsets.UTF_8);
        if (utf8.indexOf('\uFFFD') < 0) {
            return utf8;
        }
        return new String(data, Charset.forName("GBK"));
    }

    private static Charset charsetOf(String contentType) {
        Matcher matcher = META_CHARSET.matcher(
                contentType == null ? "" : contentType);
        return matcher.find() ? charsetByName(matcher.group(1)) : null;
    }

    private static Charset charsetByName(String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.startsWith("gb") || "gb2312".equals(lower)
                || "gbk".equals(lower) || "gb18030".equals(lower)) {
            return Charset.forName("GBK");
        }
        try {
            return Charset.forName(name);
        } catch (Exception e) {
            return null;
        }
    }

    private static String contentTypeOf(HttpResponse<?> response) {
        return response.headers().firstValue("Content-Type").orElse("");
    }

    // ───────────────────────── acw_sc__v2 ─────────────────────────

    /** 页面若为 acw_sc__v2 校验页则返回应设置的 Cookie 值；无 arg1 返回 null。 */
    private static String challengeCookie(String html) {
        Matcher matcher = ACW_ARG1.matcher(html);
        if (!matcher.find()) {
            return null;
        }
        return acwScV2(matcher.group(1));
    }

    /** arg1 按位置表重排 → 与掩码逐字节异或 → 20 位小写十六进制。 */
    private static String acwScV2(String arg1) {
        char[] rearranged = new char[ACW_POS.length];
        for (int x = 0; x < arg1.length(); x++) {
            for (int z = 0; z < ACW_POS.length; z++) {
                if (ACW_POS[z] == x + 1) {
                    rearranged[z] = arg1.charAt(x);
                }
            }
        }
        StringBuilder hex = new StringBuilder();
        for (int i = 0; i + 2 <= rearranged.length && i + 2 <= ACW_MASK.length(); i += 2) {
            int left = Integer.parseInt(
                    new String(rearranged, i, 2), 16);
            int right = Integer.parseInt(ACW_MASK.substring(i, i + 2), 16);
            hex.append(String.format("%02x", left ^ right));
        }
        return hex.toString();
    }

    // ───────────────────────── 通用工具 ─────────────────────────

    private static String passcodeFrom(String text, String url) {
        Matcher matcher = PWD_QUERY.matcher(url);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return CloudLinks.extractPasscode(text);
    }

    private static boolean isLanzouHost(String host) {
        if (host == null || host.isBlank()) {
            return false;
        }
        return HOST_PATTERN.matcher(host).matches()
                || TIS_HOST_PATTERN.matcher(host).matches();
    }

    private static String normalizeShareUrl(String url) {
        String value = url == null ? "" : url.trim();
        if (value.regionMatches(true, 0, "http://", 0, 7)) {
            value = "https://" + value.substring(7);
        }
        int hash = value.indexOf('#');
        if (hash >= 0) {
            value = value.substring(0, hash);
        }
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private static String firstPathSegment(String path) {
        if (path == null) {
            return "";
        }
        int start = 0;
        while (start < path.length() && path.charAt(start) == '/') {
            start++;
        }
        int end = path.indexOf('/', start);
        if (end < 0) {
            end = path.length();
        }
        return path.substring(start, end);
    }

    private static String lastPathSegment(String path) {
        String value = path == null ? "" : path;
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        int slash = value.lastIndexOf('/');
        return slash >= 0 ? value.substring(slash + 1) : value;
    }

    private static String numericId(String id) {
        String value = id == null ? "" : id.trim();
        if (value.startsWith("f:") || value.startsWith("d:")) {
            value = value.substring(2);
        }
        return value;
    }

    private static String hostOf(String url) {
        return CloudLinks.hostOf(url);
    }

    private static String schemeHost(String url) {
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            if (host == null || host.isEmpty()) {
                return "";
            }
            String scheme = uri.getScheme();
            return (scheme == null || scheme.isEmpty() ? "https" : scheme) + "://" + host;
        } catch (Exception e) {
            return "";
        }
    }

    private static String originOf(String url) {
        String origin = schemeHost(url);
        return origin.isEmpty() ? "https://pan.lanzoui.com" : origin;
    }

    private static String absolutize(String base, String target) {
        if (target == null || target.isEmpty()) {
            return "";
        }
        if (target.startsWith("http://") || target.startsWith("https://")) {
            return target;
        }
        String origin = originOf(base);
        return target.startsWith("/") ? origin + target : origin + "/" + target;
    }

    private static void putCookie(Map<String, Map<String, String>> jar,
                                  String host, String name, String value) {
        if (host == null || host.isEmpty()) {
            return;
        }
        jar.computeIfAbsent(host, key -> new LinkedHashMap<>()).put(name, value);
    }

    private static String cookieHeader(Map<String, Map<String, String>> jar, String host) {
        Map<String, String> cookies = jar.get(host);
        if (cookies == null || cookies.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<String, String> entry : cookies.entrySet()) {
            if (builder.length() > 0) {
                builder.append("; ");
            }
            builder.append(entry.getKey()).append('=').append(entry.getValue());
        }
        return builder.toString();
    }

    private static long parseDisplaySize(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        Matcher matcher = SIZE_PATTERN.matcher(text.trim());
        if (!matcher.find()) {
            return 0;
        }
        double value;
        try {
            value = Double.parseDouble(matcher.group(1));
        } catch (NumberFormatException e) {
            return 0;
        }
        String unit = matcher.group(2).toUpperCase(Locale.ROOT);
        long factor = switch (unit) {
            case "K" -> 1024L;
            case "M" -> 1024L * 1024;
            case "G" -> 1024L * 1024 * 1024;
            case "T" -> 1024L * 1024 * 1024 * 1024;
            default -> 1L;
        };
        return (long) (value * factor);
    }

    private static String regexGroup(String source, Pattern pattern) {
        Matcher matcher = pattern.matcher(source);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return "";
    }

    private static final class PageFetch {
        private final String url;
        private final String html;

        private PageFetch(String url, String html) {
            this.url = url;
            this.html = html;
        }
    }

    private static final class SharePage {
        private final String shareUrl;
        private final String origin;
        private final String title;
        private final boolean folder;
        private final boolean needsPwd;
        private final String iframeUrl;
        private final String fileId;
        private final String lx;
        private final String fid;
        private final String t;
        private final String k;
        private final String uid;
        private final String puid;
        private final String rep;
        private final String up;
        private final String ls;
        private final String html;
        private final String shareId;
        private final long displaySize;

        private SharePage(String shareUrl, String origin, String title,
                          boolean folder, boolean needsPwd, String iframeUrl,
                          String fileId, String lx, String fid, String t, String k,
                          String uid, String puid, String rep, String up, String ls,
                          String html, String shareId, long displaySize) {
            this.shareUrl = shareUrl;
            this.origin = origin;
            this.title = title;
            this.folder = folder;
            this.needsPwd = needsPwd;
            this.iframeUrl = iframeUrl;
            this.fileId = fileId;
            this.lx = lx;
            this.fid = fid;
            this.t = t;
            this.k = k;
            this.uid = uid;
            this.puid = puid;
            this.rep = rep;
            this.up = up;
            this.ls = ls;
            this.html = html;
            this.shareId = shareId;
            this.displaySize = displaySize;
        }
    }
}
