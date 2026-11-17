package com.downloader.cloud;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 已识别的网盘分享链接。
 */
public final class CloudShare {

    private final CloudDrive drive;
    /** 分享标识（各平台含义不同：pwd_id / surl / shareKey / share_code / 蓝奏文件 ID 等） */
    private final String shareKey;
    /** 提取码（可为空） */
    private final String passcode;
    /** 原始分享链接 */
    private final String rawUrl;
    /** 分享链接所在主机（多域名平台用于确定 API 基址） */
    private final String host;
    /** 平台私有附加字段（如 115 的 receiveCode、百度分享信息等） */
    private final Map<String, String> extra = new LinkedHashMap<>();

    public CloudShare(CloudDrive drive, String shareKey, String passcode,
                      String rawUrl, String host) {
        this.drive = drive;
        this.shareKey = shareKey;
        this.passcode = passcode == null ? "" : passcode.trim();
        this.rawUrl = rawUrl == null ? "" : rawUrl;
        this.host = host == null ? "" : host;
    }

    public CloudDrive drive() {
        return drive;
    }

    public String shareKey() {
        return shareKey;
    }

    public String passcode() {
        return passcode;
    }

    public String rawUrl() {
        return rawUrl;
    }

    public String host() {
        return host;
    }

    public Map<String, String> extra() {
        return extra;
    }

    @Override
    public String toString() {
        return drive.displayName + " 分享 " + shareKey;
    }
}
