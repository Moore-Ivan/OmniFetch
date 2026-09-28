package com.downloader.model;

/**
 * 支持的下载协议
 */
public enum Protocol {

    HTTP_HTTPS("HTTP/HTTPS", "http/https"),
    FTP("FTP", "ftp"),
    SFTP("SFTP", "sftp"),
    MAGNET("磁力链接", "magnet"),
    TORRENT("BitTorrent", ".torrent"),
    M3U8_HLS("m3u8/HLS", "m3u8"),
    UNKNOWN("未知协议", "");

    public final String displayName;
    public final String scheme;

    Protocol(String displayName, String scheme) {
        this.displayName = displayName;
        this.scheme = scheme;
    }
}
