package com.downloader.factory;

import com.downloader.model.Protocol;
import com.downloader.model.ProgressCallback;
import com.downloader.plugin.PluginManager;
import com.downloader.protocol.*;
import com.downloader.util.FileUtils;
import com.downloader.util.RateLimiter;

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
                                           String saveFilePath,
                                           RateLimiter limiter)
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
                return new HttpDownloader(url, savePath, callback, downloadedSize, saveFilePath, limiter);

            case FTP: {
                String host = FileUtils.extractHost(url);
                int port = FileUtils.extractPort(url, 21);
                // 与 SFTP 一致：优先使用界面传入的凭据，为空再回退 URL 内嵌信息/匿名
                String u = (user != null && !user.isBlank())
                        ? user : FileUtils.extractUser(url, "anonymous");
                String p = (pass != null && !pass.isBlank())
                        ? pass : FileUtils.extractPass(url, "");
                String path = FileUtils.extractPath(url);
                return new FtpDownloader(host, port, u, p, path,
                        savePath, callback, saveFilePath, limiter);
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
                        savePath, callback, saveFilePath, limiter);
            }

            case M3U8_HLS:
                return new M3u8Downloader(url, savePath, callback, limiter);

            case MAGNET:
            case TORRENT:
                // saveFilePath：续传/重试时复用上次实际落盘路径，避免重下生成新文件名
                return new BitTorrentDownloader(url, savePath, callback, saveFilePath, limiter);

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
                                           long downloadedSize,
                                           String saveFilePath)
            throws Exception {
        return create(protocol, url, savePath, user, pass, callback, downloadedSize, saveFilePath, null);
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
