package com.downloader.model;

import com.downloader.protocol.DownloadProtocol;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 下载任务类
 * 表示一个完整的下载任务，包含所有相关信息和状态
 */
public class DownloadTask {

    public enum TaskStatus {
        WAITING("等待中"),
        DOWNLOADING("下载中"),
        PAUSED("暂停"),
        COMPLETED("完成"),
        FAILED("失败");

        private final String displayName;

        TaskStatus(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    private final String id;
    private final String url;
    private final String savePath;
    private final String username;
    private final String password;
    private final Protocol protocol;

    private volatile TaskStatus status = TaskStatus.WAITING;
    private volatile String fileName;
    private volatile String saveFilePath;
    private volatile long totalSize = -1;
    private final AtomicLong downloadedSize = new AtomicLong(0);
    private volatile long speed = 0;
    private volatile String errorMessage;
    
    private LocalDateTime startTime;
    private LocalDateTime completedTime;
    
    private volatile int retryCount = 0;
    private static final int MAX_RETRY_COUNT = 3;
    
    private DownloadProtocol downloader;
    private ProgressCallback callback;

    public DownloadTask(String id, String url, String savePath, String username, String password, Protocol protocol) {
        this.id = id;
        this.url = url;
        this.savePath = savePath;
        this.username = username;
        this.password = password;
        this.protocol = protocol;
    }

    // Getters and setters
    public String getId() {
        return id;
    }

    public String getUrl() {
        return url;
    }

    public String getSavePath() {
        return savePath;
    }

    public String getUsername() {
        return username;
    }

    public String getPassword() {
        return password;
    }

    public Protocol getProtocol() {
        return protocol;
    }

    public TaskStatus getStatus() {
        return status;
    }

    public void setStatus(TaskStatus status) {
        this.status = status;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public long getTotalSize() {
        return totalSize;
    }

    public void setTotalSize(long totalSize) {
        this.totalSize = totalSize;
    }

    public long getDownloadedSize() {
        return downloadedSize.get();
    }

    public void addDownloadedSize(long size) {
        downloadedSize.addAndGet(size);
    }

    public void setDownloadedSize(long size) {
        // 使用getAndSet来设置新值
        downloadedSize.getAndSet(size);
    }

    public long getSpeed() {
        return speed;
    }

    public void setSpeed(long speed) {
        this.speed = speed;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public LocalDateTime getStartTime() {
        return startTime;
    }

    public void setStartTime(LocalDateTime startTime) {
        this.startTime = startTime;
    }

    public LocalDateTime getCompletedTime() {
        return completedTime;
    }

    public void setCompletedTime(LocalDateTime completedTime) {
        this.completedTime = completedTime;
    }

    public DownloadProtocol getDownloader() {
        return downloader;
    }

    public void setDownloader(DownloadProtocol downloader) {
        this.downloader = downloader;
    }

    public ProgressCallback getCallback() {
        return callback;
    }

    public void setCallback(ProgressCallback callback) {
        this.callback = callback;
    }

    public String getSaveFilePath() {
        return saveFilePath;
    }

    public void setSaveFilePath(String saveFilePath) {
        this.saveFilePath = saveFilePath;
    }

    // 计算进度百分比
    public int getProgress() {
        if (totalSize <= 0) {
            return 0;
        }
        return (int) (downloadedSize.get() * 100 / totalSize);
    }

    // 取消下载
    public void cancel() {
        if (downloader != null) {
            downloader.cancel();
        }
        status = TaskStatus.FAILED;
        errorMessage = "下载已取消";
    }

    // 暂停下载
    public void pause() {
        if (downloader != null) {
            downloader.cancel();
        }
        status = TaskStatus.PAUSED;
    }

    // 关闭资源
    public void close() {
        if (downloader != null) {
            downloader.close();
            downloader = null;
        }
    }
    
    // 重试相关方法
    public int getRetryCount() {
        return retryCount;
    }
    
    public void incrementRetryCount() {
        retryCount++;
    }
    
    public void resetRetryCount() {
        retryCount = 0;
    }
    
    public boolean canRetry() {
        return retryCount < MAX_RETRY_COUNT;
    }
    
    public static int getMaxRetryCount() {
        return MAX_RETRY_COUNT;
    }
}