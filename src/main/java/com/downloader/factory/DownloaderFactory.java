package com.downloader.factory;

import com.downloader.model.Protocol;
import com.downloader.model.ProgressCallback;
import com.downloader.plugin.PluginManager;
import com.downloader.protocol.*;
import com.downloader.util.FileUtils;

/**
 * 下载器工厂：根据协议类型创建对应的 DownloadProtocol 实例
 */
public final class DownloaderFactory {

    private DownloaderFactory() {}

    public static DownloadProtocol create(Protocol protocol,
                                           String url,
                                           String savePath,
                                           String user,
                                           String pass,
                                           ProgressCallback callback,
                                           long downloadedSize,
                                           String saveFilePath)
            throws Exception {
        
        // 首先尝试使用插件处理
        PluginManager pluginManager = PluginManager.getInstance();
        DownloadProtocol downloader = pluginManager.createDownloader(
                url, savePath, user, pass, callback, downloadedSize, saveFilePath);
        
        if (downloader != null) {
            return downloader;
        }

        // 如果没有插件支持，则使用内置协议处理
        switch (protocol) {

            case HTTP_HTTPS:
                return new HttpDownloader(url, savePath, callback, downloadedSize, saveFilePath);

            case FTP: {
                String host = FileUtils.extractHost(url);
                int port = FileUtils.extractPort(url, 21);
                String u = FileUtils.extractUser(url, "anonymous");
                String p = FileUtils.extractPass(url, "");
                String path = FileUtils.extractPath(url);
                return new FtpDownloader(host, port, u, p, path,
                        savePath, callback);
            }

            case SFTP: {
                String host = FileUtils.extractHost(url);
                int port = FileUtils.extractPort(url, 22);
                String u = (user != null && !user.isBlank())
                        ? user : FileUtils.extractUser(url, "root");
                String p = (pass != null && !pass.isBlank())
                        ? pass : FileUtils.extractPass(url, "");
                String path = FileUtils.extractPath(url);
                return new SftpDownloader(host, port, u, p, path,
                        savePath, callback);
            }

            case M3U8_HLS:
                return new M3u8Downloader(url, savePath, callback);

            case MAGNET:
            case TORRENT:
                return new BitTorrentDownloader(url, savePath, callback);

            default:
                throw new UnsupportedOperationException(
                        "不支持的协议: " + protocol.displayName);
        }
    }

    public static DownloadProtocol create(Protocol protocol,
                                           String url,
                                           String savePath,
                                           String user,
                                           String pass,
                                           ProgressCallback callback,
                                           long downloadedSize)
            throws Exception {
        return create(protocol, url, savePath, user, pass, callback, downloadedSize, null);
    }

    public static DownloadProtocol create(Protocol protocol,
                                           String url,
                                           String savePath,
                                           String user,
                                           String pass,
                                           ProgressCallback callback)
            throws Exception {
        return create(protocol, url, savePath, user, pass, callback, 0);
    }
}
