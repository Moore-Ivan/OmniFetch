package com.downloader.model;

import com.downloader.protocol.DownloadProtocol;
import com.downloader.util.AppLogger;
import com.downloader.util.RateLimiter;

import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
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

    /**
     * 合法状态迁移表（轻量状态机）：所有运行期状态变更必须走 {@link #transitionTo}，
     * 非法迁移拒绝并告警，防止重试/取消竞争把任务带入矛盾状态（如 COMPLETED→DOWNLOADING 复活）。
     * 同状态重复迁移视为幂等操作直接放行。
     */
    private static final Map<TaskStatus, Set<TaskStatus>> ALLOWED_TRANSITIONS = buildTransitions();

    private static Map<TaskStatus, Set<TaskStatus>> buildTransitions() {
        Map<TaskStatus, Set<TaskStatus>> m = new EnumMap<>(TaskStatus.class);
        m.put(TaskStatus.WAITING, EnumSet.of(
                TaskStatus.DOWNLOADING, TaskStatus.PAUSED, TaskStatus.FAILED));
        m.put(TaskStatus.DOWNLOADING, EnumSet.of(
                TaskStatus.DOWNLOADING, TaskStatus.PAUSED,
                TaskStatus.COMPLETED, TaskStatus.FAILED, TaskStatus.WAITING));
        m.put(TaskStatus.PAUSED, EnumSet.of(
                TaskStatus.PAUSED, TaskStatus.WAITING, TaskStatus.FAILED));
        m.put(TaskStatus.COMPLETED, EnumSet.of(TaskStatus.COMPLETED));
        m.put(TaskStatus.FAILED, EnumSet.of(TaskStatus.FAILED, TaskStatus.WAITING));
        return m;
    }

    private final String id;
    private final String url;
    private final String savePath;
    private final String username;
    /** 非 final：历史恢复的任务不落盘密码，继续/重试时由界面补输 */
    private volatile String password;
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
    /** 当前执行代数：每次开始执行递增，暂停/取消时也递增。
     *  旧下载线程携带旧代数，其回调（进度/完成/失败）在新代数下全部作废，
     *  防止"快速暂停→继续"后旧线程污染新尝试的进度统计或错误状态。 */
    private volatile long runGeneration = 0;

    /** 单任务限速覆盖（字节/秒）：>0 任务专属限速；0 跟随全局配置；-1 本任务不限速 */
    private volatile long speedLimitBps = 0;
    /** 本任务的限速器实例（由 DownloadManager 创建并随工厂下发到下载器） */
    private volatile RateLimiter rateLimiter;

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

    public void setPassword(String password) {
        this.password = password;
    }

    public Protocol getProtocol() {
        return protocol;
    }

    public TaskStatus getStatus() {
        return status;
    }

    /**
     * 轻量状态机迁移：仅允许 {@link #ALLOWED_TRANSITIONS} 中声明的变更，
     * 非法迁移拒绝（保持原状态）并记录告警。同状态重复设置视为幂等。
     *
     * @return 是否完成迁移
     */
    public boolean transitionTo(TaskStatus target) {
        TaskStatus current = this.status;
        if (current == target) {
            return true;
        }
        Set<TaskStatus> allowed = ALLOWED_TRANSITIONS.get(current);
        if (allowed != null && allowed.contains(target)) {
            this.status = target;
            return true;
        }
        AppLogger.warn("拒绝非法任务状态迁移: " + id + " "
                + current + " -> " + target);
        return false;
    }

    /**
     * 直接设置状态（绕过状态机）。
     * 仅限历史记录恢复等"构建初始状态"场景，运行期变更必须走 {@link #transitionTo}。
     */
    public void setStatus(TaskStatus status) {
        this.status = status;
    }

    public long getSpeedLimitBps() {
        return speedLimitBps;
    }

    /** 设置单任务限速覆盖（>0 任务专属；0 跟随全局；-1 不限速） */
    public void setSpeedLimitBps(long speedLimitBps) {
        this.speedLimitBps = speedLimitBps;
    }

    public RateLimiter getRateLimiter() {
        return rateLimiter;
    }

    public void setRateLimiter(RateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
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

    /** 开启新一轮执行：代数 +1 并返回该轮代数（调用方捕获后用于过滤过期回调） */
    public long nextRunGeneration() {
        return ++runGeneration;
    }

    /** 该轮执行是否仍然有效（未被暂停/取消/新一轮替代） */
    public boolean isRunValid(long generation) {
        return runGeneration == generation;
    }

    /** 作废当前执行（暂停/取消/移除时调用，旧线程的回调从此被忽略） */
    public void invalidateRun() {
        runGeneration++;
    }

    // 计算进度百分比
    public int getProgress() {
        // 已完成任务恒为 100%：总大小未知的服务器下载完成后不应停留在 0%
        if (status == TaskStatus.COMPLETED) {
            return 100;
        }
        if (totalSize <= 0) {
            return 0;
        }
        return (int) (downloadedSize.get() * 100 / totalSize);
    }

    // 取消下载
    public void cancel() {
        invalidateRun();
        if (downloader != null) {
            downloader.cancel();
        }
        // 状态机校验：已完成任务不会被"取消"改写成失败（非法迁移被拒绝）
        transitionTo(TaskStatus.FAILED);
        errorMessage = "下载已取消";
    }

    // 暂停下载
    public void pause() {
        invalidateRun();
        if (downloader != null) {
            downloader.cancel();
        }
        transitionTo(TaskStatus.PAUSED);
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

    public void setRetryCount(int retryCount) {
        this.retryCount = retryCount;
    }

    public void incrementRetryCount() {
        retryCount++;
    }

    public void resetRetryCount() {
        retryCount = 0;
    }

    /** 重试上限取自配置项 download.maxRetryCount（默认 3，读取时自动钳制） */
    public boolean canRetry() {
        return retryCount < com.downloader.config.ConfigManager.getInstance()
                .getInt("download.maxRetryCount", 3);
    }
}