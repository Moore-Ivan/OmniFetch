package com.downloader.cloud;

import com.downloader.config.ConfigManager;

/**
 * 一次网盘解析会话的上下文：带 Cookie 的 HTTP 客户端、浏览器 UA、用户凭据。
 */
public final class CloudContext implements AutoCloseable {

    public static final String DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

    private final CloudDrive drive;
    private final CloudHttp http;
    private final String userAgent;
    private final String cookieText;

    private CloudContext(CloudDrive drive, String cookieOverride) {
        ConfigManager cfg = ConfigManager.getInstance();
        this.drive = drive;
        this.userAgent = cfg.getString("cloud.userAgent", DEFAULT_USER_AGENT);
        String cookie = cookieOverride == null
                ? cfg.getString(drive.cookieKey, "").trim()
                : cookieOverride.trim();
        this.cookieText = cookie;
        this.http = new CloudHttp(
                cfg.getInt("download.connectTimeoutSec", 15),
                cfg.getInt("download.responseTimeoutSec", 30));
        if (!cookieText.isEmpty()) {
            http.seedCookies(drive.cookieDomain, cookieText);
        }
    }

    public static CloudContext create(CloudDrive drive) {
        return new CloudContext(drive, null);
    }

    /**
     * 用界面上临时输入的凭据创建会话（尚未保存到配置时也能立即解析）。
     * cookieOverride 为空则回落到配置值。
     */
    public static CloudContext create(CloudDrive drive, String cookieOverride) {
        return new CloudContext(drive, cookieOverride);
    }

    public CloudDrive drive() {
        return drive;
    }

    public CloudHttp http() {
        return http;
    }

    public String userAgent() {
        return userAgent;
    }

    public String cookieText() {
        return cookieText;
    }

    /** 平台多域名时，按分享链接实际主机二次注入 Cookie */
    public void seedCookiesForHost(String host) {
        if (host == null || host.isBlank()) {
            return;
        }
        http.seedCookies(host, cookieText);
    }

    @Override
    public void close() {
        http.close();
    }
}
