package com.downloader.protocol;

import com.downloader.config.ConfigManager;
import com.downloader.model.ProgressCallback;
import com.downloader.pool.SftpConnectionPool;
import com.downloader.util.AppLogger;
import com.downloader.util.FileUtils;
import com.downloader.util.RateLimiter;
import com.jcraft.jsch.*;
import java.io.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SFTP 下载器
 * 基于 JSch 库实现。
 * 落盘采用 .part 隔离：全程写 {@code <最终文件>.part}，下载完成并校验后
 * 原子改名为最终文件；暂停/失败保留 .part，下次以 JSch RESUME 模式断点续传
 * （SFTP 协议按偏移读取，续传无需服务器特殊命令支持）。
 */
public class SftpDownloader implements DownloadProtocol {

    private final String remotePath;
    private final String savePath;
    /** 上次运行回报的最终落盘路径（续传/重试时复用，避免二次避让改名）；可为 null */
    private final String saveFilePath;
    private final ProgressCallback callback;
    /** 任务级限速器（字节/秒，0=不限）；可为 null（插件通道） */
    private final RateLimiter limiter;
    private volatile boolean cancelled = false;

    private final SftpConnectionPool connectionPool;
    private SftpConnectionPool.SftpConnection connection;

    private static final int READ_TIMEOUT_MS = 30_000;

    /** 进度上报间隔走配置（download.progressIntervalMs） */
    private int getProgressIntervalMs() {
        return ConfigManager.getInstance().getInt("download.progressIntervalMs", 250);
    }

    public SftpDownloader(String host, int port, String user, String pass,
                          String remotePath, String savePath,
                          ProgressCallback callback) {
        this(host, port, user, pass, remotePath, savePath, callback, null, null);
    }

    public SftpDownloader(String host, int port, String user, String pass,
                          String remotePath, String savePath,
                          ProgressCallback callback, String saveFilePath,
                          RateLimiter limiter) {
        this.remotePath = remotePath;
        this.savePath = savePath;
        this.callback = callback;
        this.saveFilePath = saveFilePath;
        this.limiter = limiter;
        this.connectionPool = new SftpConnectionPool(host, port, user, pass);
    }

    @Override
    public void download() throws Exception {
        try {
            // 从连接池获取连接
            connection = connectionPool.getConnection();
            ChannelSftp channel = connection.getChannel();

            // 读超时防止服务器假死导致任务永久卡在"下载中"
            try {
                channel.getSession().setTimeout(READ_TIMEOUT_MS);
            } catch (JSchException e) {
                // 会话已连接时设置失败不影响下载，忽略
            }

            // 获取远程文件属性
            SftpATTRS attrs = channel.stat(remotePath);
            long totalSize = attrs.getSize();

            // 提取文件名
            String fileName = remotePath.contains("/")
                    ? remotePath.substring(remotePath.lastIndexOf('/') + 1)
                    : remotePath;
            if (fileName.isEmpty()) {
                fileName = "sftp_download";
            }

            callback.onConnected(fileName, totalSize);

            // 最终落盘路径：续传/重试复用上次回报路径，避免二次避让改名
            File finalFile = (saveFilePath != null && !saveFilePath.isEmpty())
                    ? new File(saveFilePath)
                    : FileUtils.buildSaveFile(savePath, fileName);
            // 重名避让后的真实落盘路径回报给任务（打开文件/续传都以它为准）
            callback.onSaveFile(finalFile);
            File partFile = new File(finalFile.getAbsolutePath() + ".part");

            // 断点续传：.part 已有数据时以 JSch RESUME 模式从其长度处继续
            long offset = 0;
            if (partFile.exists()) {
                long existing = partFile.length();
                if (totalSize > 0 && existing >= totalSize) {
                    // 上次已完成但未改名（异常残留）：直接收尾
                    FileUtils.atomicMove(partFile.toPath(), finalFile.toPath());
                    callback.onComplete();
                    return;
                }
                if (totalSize > 0) {
                    offset = existing;
                } else {
                    // 远端大小未知无法校验 .part 内容，弃用重下
                    if (!partFile.delete() && partFile.exists()) {
                        throw new IOException("无法重置下载临时文件: " + partFile.getAbsolutePath());
                    }
                }
            }

            // 进度监控：count 按增量回调，承担限速、节流进度上报与取消判定
            final long baseOffset = offset;
            final int progressInterval = getProgressIntervalMs();
            final AtomicLong transferred = new AtomicLong(baseOffset);
            final long[] lastReport = {System.currentTimeMillis()};
            final long[] lastBytes = {baseOffset};
            SftpProgressMonitor monitor = new SftpProgressMonitor() {
                @Override
                public void init(int op, String src, String dst, long max) {
                    callback.onStatusUpdate(baseOffset > 0
                            ? "SFTP 断点续传（已下载 " + FileUtils.formatSize(baseOffset) + "）"
                            : "SFTP");
                }

                @Override
                public boolean count(long increment) {
                    // 限速：按本次传输增量获取令牌（不限速时空操作）
                    RateLimiter.acquire(limiter, increment);
                    long dl = transferred.addAndGet(increment);
                    long now = System.currentTimeMillis();
                    if (now - lastReport[0] >= progressInterval) {
                        long speed = (long) ((dl - lastBytes[1]) * 1000.0 / (now - lastReport[0]));
                        callback.onProgress(dl, totalSize, speed);
                        lastReport[0] = now;
                        lastBytes[1] = dl;
                    }
                    // 返回 false 使 JSch 中止传输（暂停/取消；.part 保留供续传）
                    return !cancelled;
                }

                @Override
                public void end() {
                }
            };

            try {
                channel.get(remotePath, partFile.getAbsolutePath(), monitor,
                        offset > 0 ? ChannelSftp.RESUME : ChannelSftp.OVERWRITE);
            } catch (SftpException e) {
                if (cancelled) {
                    // 暂停/取消触发的中止：保留 .part，正常返回
                    return;
                }
                throw new IOException("SFTP 下载失败: " + e.getMessage(), e);
            }

            if (cancelled) {
                return;
            }

            // 严禁假完成：总大小已知但未写满必须走重试
            if (totalSize > 0 && partFile.length() < totalSize) {
                throw new IOException("SFTP 下载不完整: "
                        + partFile.length() + "/" + totalSize + " 字节");
            }
            // 完成：.part 原子改名为最终文件
            FileUtils.atomicMove(partFile.toPath(), finalFile.toPath());
            if (offset > 0) {
                AppLogger.info("SFTP 断点续传完成: " + remotePath
                        + "（从 " + FileUtils.formatSize(offset) + " 处继续）");
            }
            callback.onComplete();

        } finally {
            close();
        }
    }

    @Override
    public void cancel() {
        cancelled = true;
    }

    @Override
    public void close() {
        if (connection != null) {
            connectionPool.releaseConnection(connection);
            connection = null;
        }
        // 每个下载任务独享一个连接池，任务结束后必须整体关闭，
        // 否则池内清理线程永久持有池引用与空闲会话
        connectionPool.close();
    }
}
