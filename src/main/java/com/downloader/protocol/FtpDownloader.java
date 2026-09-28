package com.downloader.protocol;

import com.downloader.model.ProgressCallback;
import com.downloader.pool.FtpConnectionPool;
import com.downloader.util.FileUtils;

import java.io.*;
import java.net.*;

/**
 * FTP 下载器
 * 纯 Java Socket 实现，使用 PASV 被动模式
 */
public class FtpDownloader implements DownloadProtocol {

    private final String remotePath;
    private final String savePath;
    private final ProgressCallback callback;
    private volatile boolean cancelled = false;

    private final FtpConnectionPool connectionPool;
    private FtpConnectionPool.FtpConnection connection;

    private static final int BUFFER_SIZE = 16_384;

    public FtpDownloader(String host, int port, String user, String pass,
                         String remotePath, String savePath,
                         ProgressCallback callback) {
        this.remotePath = remotePath;
        this.savePath = savePath;
        this.callback = callback;
        this.connectionPool = new FtpConnectionPool(host, port, user, pass);
    }

    @Override
    public void download() throws Exception {
        try {
            // 从连接池获取连接
            connection = connectionPool.getConnection();

            // 获取文件大小
            long totalSize = -1;
            try {
                String sizeResp = connection.sendCommandAndGetResponse("SIZE " + remotePath);
                if (sizeResp.startsWith("213 ")) {
                    totalSize = Long.parseLong(sizeResp.substring(4).trim());
                }
            } catch (Exception ignored) {}

            // PASV 被动模式
            int[] pasvAddr = connection.enterPassiveMode();
            String dataIp = pasvAddr[0] + "." + pasvAddr[1] + "."
                    + pasvAddr[2] + "." + pasvAddr[3];
            int dataPort = pasvAddr[4];

            // 发送 RETR
            connection.sendCommand("RETR " + remotePath, 150);

            // 建立数据连接
            Socket dataSocket = new Socket();
            dataSocket.connect(
                    new InetSocketAddress(dataIp, dataPort), 10000);

            String fileName = remotePath.contains("/")
                    ? remotePath.substring(remotePath.lastIndexOf('/') + 1)
                    : remotePath;
            if (fileName.isEmpty()) fileName = "ftp_download";

            callback.onConnected(fileName, totalSize);
            callback.onStatusUpdate("FTP 被动模式");

            File saveFile = FileUtils.buildSaveFile(savePath, fileName);
            
            // 预分配磁盘空间，避免文件系统碎片
            if (totalSize > 0) {
                try (RandomAccessFile raf = new RandomAccessFile(saveFile, "rw")) {
                    raf.setLength(totalSize);
                }
            }

            try (InputStream in = dataSocket.getInputStream();
                 FileOutputStream fos = new FileOutputStream(saveFile)) {

                byte[] buf = new byte[BUFFER_SIZE];
                int read;
                long downloaded = 0;
                long lastTime = System.currentTimeMillis();
                long lastBytes = 0;

                while ((read = in.read(buf)) != -1) {
                    if (cancelled) {
                        saveFile.delete();
                        try { connection.sendCommand("ABOR", 226); }
                        catch (Exception ignored) {}
                        return;
                    }

                    fos.write(buf, 0, read);
                    downloaded += read;

                    long now = System.currentTimeMillis();
                    if (now - lastTime >= 250) {
                        long speed = (long) ((downloaded - lastBytes)
                                * 1000.0 / (now - lastTime));
                        callback.onProgress(downloaded, totalSize, speed);
                        lastTime = now;
                        lastBytes = downloaded;
                    }
                }
                fos.flush();
            } finally {
                FileUtils.closeQuietly(dataSocket);
            }

            connection.readResponse(226);
            if (!cancelled) callback.onComplete();

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
