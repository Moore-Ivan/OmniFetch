package com.downloader.cloud;

import com.downloader.util.MiniJson;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 网盘下载任务的可持久化描述。
 * <p>直链带签名且很快过期，任务里不能直接保存直链；这里把「分享信息 + 文件标识」
 * 编码进任务 URL（cloud:// 前缀），每次开始下载/续传时重新解析出新鲜直链。
 * 编码结果同时会写入 history.json，重启后仍可继续。
 */
public final class CloudTaskCodec {

    public static final String PREFIX = "cloud://";

    private CloudTaskCodec() {
    }

    public static final class Task {
        public final CloudShare share;
        public final CloudFile file;

        public Task(CloudShare share, CloudFile file) {
            this.share = share;
            this.file = file;
        }
    }

    public static boolean isCloudTask(String url) {
        return url != null && url.startsWith(PREFIX);
    }

    public static String encode(CloudShare share, CloudFile file) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("v", 1L);
        root.put("d", share.drive().name());

        Map<String, Object> s = new LinkedHashMap<>();
        s.put("k", share.shareKey());
        s.put("p", share.passcode());
        s.put("u", share.rawUrl());
        s.put("h", share.host());
        if (!share.extra().isEmpty()) {
            s.put("x", new LinkedHashMap<>(share.extra()));
        }
        root.put("s", s);

        Map<String, Object> f = new LinkedHashMap<>();
        f.put("i", file.id());
        f.put("n", file.name());
        f.put("z", file.size());
        f.put("d", file.folder());
        f.put("p", file.path());
        if (!file.extra().isEmpty()) {
            f.put("x", new LinkedHashMap<>(file.extra()));
        }
        root.put("f", f);

        String json = MiniJson.write(root);
        String base64 = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(json.getBytes(StandardCharsets.UTF_8));
        return PREFIX + base64;
    }

    public static Task decode(String url) throws CloudException {
        if (!isCloudTask(url)) {
            throw new CloudException("不是有效的网盘任务链接");
        }
        String base64 = url.substring(PREFIX.length()).trim();
        try {
            byte[] raw = Base64.getUrlDecoder().decode(base64);
            Object parsed = MiniJson.parse(new String(raw, StandardCharsets.UTF_8));
            Map<String, Object> root = asMap(parsed);
            CloudDrive drive;
            try {
                drive = CloudDrive.valueOf(String.valueOf(root.get("d")));
            } catch (Exception e) {
                throw new CloudException("网盘任务中的平台标识无效");
            }
            Map<String, Object> s = asMap(root.get("s"));
            Map<String, Object> f = asMap(root.get("f"));
            CloudShare share = new CloudShare(drive,
                    String.valueOf(s.getOrDefault("k", "")),
                    String.valueOf(s.getOrDefault("p", "")),
                    String.valueOf(s.getOrDefault("u", "")),
                    String.valueOf(s.getOrDefault("h", "")));
            Map<String, Object> sx = asMap(s.get("x"));
            for (Map.Entry<String, Object> e : sx.entrySet()) {
                share.extra().put(e.getKey(), String.valueOf(e.getValue()));
            }

            CloudFile file = new CloudFile(
                    String.valueOf(f.getOrDefault("i", "")),
                    String.valueOf(f.getOrDefault("n", "")),
                    toLong(f.get("z")),
                    toBool(f.get("d")));
            file.path(String.valueOf(f.getOrDefault("p", "/")));
            Map<String, Object> fx = asMap(f.get("x"));
            file.extra().putAll(fx);
            return new Task(share, file);
        } catch (CloudException e) {
            throw e;
        } catch (Exception e) {
            throw new CloudException("网盘任务描述解析失败: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        return new LinkedHashMap<>();
    }

    private static long toLong(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (Exception e) {
            return -1;
        }
    }

    private static boolean toBool(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        return value != null && "true".equalsIgnoreCase(String.valueOf(value));
    }
}
