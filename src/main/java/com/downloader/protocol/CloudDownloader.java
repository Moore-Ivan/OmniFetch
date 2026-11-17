package com.downloader.protocol;

import com.downloader.cloud.CloudContext;
import com.downloader.cloud.CloudLink;
import com.downloader.cloud.CloudProvider;
import com.downloader.cloud.CloudRegistry;
import com.downloader.cloud.CloudTaskCodec;
import com.downloader.model.ProgressCallback;
import com.downloader.util.RateLimiter;

/**
 * 网盘任务下载器。
 * <p>任务 URL 是 cloud:// 编码的任务描述（分享信息 + 文件标识），不含直链。
 * 每次开始下载/续传时重新调用平台解析器换取新鲜直链，再交给
 * {@link HttpDownloader}（Range 分片 + 断点续传）完成实际传输。
 */
public final class CloudDownloader implements DownloadProtocol {

    private final String cloudUrl;
    private final String savePath;
    private final ProgressCallback callback;
    private final long downloadedSize;
    private final String saveFilePath;
    private final RateLimiter limiter;

    private volatile boolean cancelled = false;
    private volatile HttpDownloader delegate;

    public CloudDownloader(String cloudUrl, String savePath,
                           ProgressCallback callback, long downloadedSize,
                           String saveFilePath, RateLimiter limiter) {
        this.cloudUrl = cloudUrl;
        this.savePath = savePath;
        this.callback = callback;
        this.downloadedSize = downloadedSize;
        this.saveFilePath = saveFilePath;
        this.limiter = limiter;
    }

    @Override
    public void download() throws Exception {
        CloudTaskCodec.Task task = CloudTaskCodec.decode(cloudUrl);
        CloudProvider provider = CloudRegistry.byDrive(task.share.drive());
        if (provider == null) {
            throw new Exception("暂不支持该网盘: " + task.share.drive().displayName);
        }

        try (CloudContext ctx = CloudContext.create(task.share.drive())) {
            ctx.seedCookiesForHost(task.share.host());
            // 解析可能失败（凭据过期/分享取消/风控），错误信息直接呈现给用户
            CloudLink link = provider.resolve(ctx, task.share, task.file);
            if (link == null || link.url() == null || link.url().isBlank()) {
                throw new Exception("未能获取下载直链，请检查凭据或分享是否有效");
            }
            if (cancelled) {
                return;
            }

            HttpDownloader http = new HttpDownloader(
                    link.url(), savePath, callback, downloadedSize,
                    saveFilePath, limiter, link.headers(), task.file.name());
            delegate = http;
            try {
                http.download();
            } finally {
                delegate = null;
            }
        }
    }

    @Override
    public void cancel() {
        cancelled = true;
        HttpDownloader http = delegate;
        if (http != null) {
            http.cancel();
        }
    }

    @Override
    public void close() {
        HttpDownloader http = delegate;
        if (http != null) {
            http.close();
        }
    }
}
