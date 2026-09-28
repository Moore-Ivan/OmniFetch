package com.downloader.event;

import com.downloader.model.DownloadTask;

/**
 * 下载事件类
 * 用于在事件总线上传递下载相关的事件
 */
public class DownloadEvent {
    
    public enum EventType {
        TASK_ADDED,
        TASK_UPDATED,
        TASK_REMOVED,
        TASK_COMPLETED,
        TASK_FAILED,
        TASK_PAUSED,
        TASK_RESUMED,
        TASK_RETRYING
    }
    
    private final EventType type;
    private final DownloadTask task;
    private final String message;
    
    public DownloadEvent(EventType type, DownloadTask task) {
        this.type = type;
        this.task = task;
        this.message = null;
    }
    
    public DownloadEvent(EventType type, DownloadTask task, String message) {
        this.type = type;
        this.task = task;
        this.message = message;
    }
    
    public EventType getType() {
        return type;
    }
    
    public DownloadTask getTask() {
        return task;
    }
    
    public String getMessage() {
        return message;
    }
}
