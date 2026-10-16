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
        // 必须先识别 HTTP(S)：http://host/a.torrent 是一个需要先下载的网络资源，
        // 若按后缀误判为 TORRENT，会把整段 URL 当本地路径打开直接失败
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            if (lower.contains(".m3u8")) {
                return Protocol.M3U8_HLS;
            }
            // 以 .torrent 结尾的 HTTP 链接：按普通 HTTP 文件下载种子本身，
            // 用户拿到种子后可再用本地文件方式发起 BT 任务
            return Protocol.HTTP_HTTPS;
        }
        if (lower.startsWith("sftp://")) {
            return Protocol.SFTP;
        }
        if (lower.startsWith("ftp://")) {
            return Protocol.FTP;
        }
        // 本地（或裸路径）的 .torrent 文件才走 BitTorrent 协议
        if (lower.endsWith(".torrent")) {
            return Protocol.TORRENT;
        }

        return Protocol.UNKNOWN;
    }
}
