package com.downloader.protocol;

import com.downloader.config.ConfigManager;
import com.downloader.model.ProgressCallback;
import com.downloader.pool.SftpConnectionPool;
import com.downloader.util.FileUtils;
import com.jcraft.jsch.*;
import java.io.*;

/**
 * SFTP 下载器
 * 基于 JSch 库实现
 */
public class SftpDownloader implements DownloadProtocol {

    private final String remotePath;
    private final String savePath;
    private final ProgressCallback callback;
    private volatile boolean cancelled = false;

    private final SftpConnectionPool connectionPool;
    private SftpConnectionPool.SftpConnection connection;

    private static final int BUFFER_SIZE = 16_384;
    private static final int READ_TIMEOUT_MS = 30_000;

    /** 进度上报间隔走配置（download.progressIntervalMs） */
    private int getProgressIntervalMs() {
        return ConfigManager.getInstance().getInt("download.progressIntervalMs", 250);
    }

    public SftpDownloader(String host, int port, String user, String pass,
                          String remotePath, String savePath,
                          ProgressCallback callback) {
        this.remotePath = remotePath;
        this.savePath = savePath;
        this.callback = callback;
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
            callback.onStatusUpdate("SFTP");

            File saveFile = FileUtils.buildSaveFile(savePath, fileName);
            
            // 预分配磁盘空间，避免文件系统碎片
            if (totalSize > 0) {
                try (RandomAccessFile raf = new RandomAccessFile(saveFile, "rw")) {
                    raf.setLength(totalSize);
                }
            }

            // 流式下载，支持进度回调
            try (InputStream in = channel.get(remotePath);
                 FileOutputStream fos = new FileOutputStream(saveFile)) {

                byte[] buf = new byte[BUFFER_SIZE];
                int read;
                long downloaded = 0;
                long lastTime = System.currentTimeMillis();
                long lastBytes = 0;
                int progressInterval = getProgressIntervalMs();

                while ((read = in.read(buf)) != -1) {
                    if (cancelled) {
                        saveFile.delete();
                        return;
                    }

                    fos.write(buf, 0, read);
                    downloaded += read;

                    long now = System.currentTimeMillis();
                    if (now - lastTime >= progressInterval) {
                        long speed = (long) ((downloaded - lastBytes)
                                * 1000.0 / (now - lastTime));
                        callback.onProgress(downloaded, totalSize, speed);
                        lastTime = now;
                        lastBytes = downloaded;
                    }
                }
                fos.flush();
            }

            if (!cancelled) {
                callback.onComplete();
            }

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
    }
}
