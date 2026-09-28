package com.downloader.detector;

import com.downloader.model.Protocol;

/**
 * 根据输入 URL 自动检测下载协议
 */
public final class ProtocolDetector {

    private ProtocolDetector() {}

    public static Protocol detect(String input) {
        if (input == null || input.isBlank()) {
            return Protocol.UNKNOWN;
        }

        String lower = input.toLowerCase().trim();

        if (lower.startsWith("magnet:")) {
            return Protocol.MAGNET;
        }
        if (lower.endsWith(".torrent")) {
            return Protocol.TORRENT;
        }
        if (lower.startsWith("sftp://")) {
            return Protocol.SFTP;
        }
        if (lower.startsWith("ftp://")) {
            return Protocol.FTP;
        }
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            if (lower.contains(".m3u8")) {
                return Protocol.M3U8_HLS;
            }
            return Protocol.HTTP_HTTPS;
        }

        return Protocol.UNKNOWN;
    }
}
