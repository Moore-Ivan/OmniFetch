package com.downloader.event;

import com.downloader.util.AppLogger;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 事件总线实现
 * 支持多监听器订阅和事件发布
 */
public class EventBus {

    private static EventBus instance;
    private final List<DownloadListener> downloadListeners;
    private final List<ConfigChangeListener> configListeners;
    /**
     * 高频进度事件（TASK_UPDATED）按窗口节流：同一任务在窗口内的重复事件直接丢弃。
     * UI 侧已有 80ms 合并定时器按任务对象实时取值，事件仅作触发信号，丢中间帧无副作用；
     * 状态类事件（完成/失败/暂停等）不节流。
     */
    private static final long TASK_UPDATED_THROTTLE_MS = 100;
    private final ConcurrentMap<String, Long> lastTaskUpdatedAt = new ConcurrentHashMap<>();

    private EventBus() {
        // 使用CopyOnWriteArrayList确保线程安全
        downloadListeners = new CopyOnWriteArrayList<>();
        configListeners = new CopyOnWriteArrayList<>();
    }
    
    public static synchronized EventBus getInstance() {
        if (instance == null) {
            instance = new EventBus();
        }
        return instance;
    }
    
    /**
     * 注册下载事件监听器
     * @param listener 下载事件监听器
     */
    public void register(DownloadListener listener) {
        if (listener != null && !downloadListeners.contains(listener)) {
            downloadListeners.add(listener);
        }
    }
    
    /**
     * 移除下载事件监听器
     * @param listener 下载事件监听器
     */
    public void unregister(DownloadListener listener) {
        if (listener != null) {
            downloadListeners.remove(listener);
        }
    }
    
    /**
     * 注册配置变更监听器
     * @param listener 配置变更监听器
     */
    public void registerConfigChangeListener(ConfigChangeListener listener) {
        if (listener != null && !configListeners.contains(listener)) {
            configListeners.add(listener);
        }
    }
    
    /**
     * 移除配置变更监听器
     * @param listener 配置变更监听器
     */
    public void unregisterConfigChangeListener(ConfigChangeListener listener) {
        if (listener != null) {
            configListeners.remove(listener);
        }
    }
    
    /**
     * 发布下载事件
     * @param event 下载事件
     */
    public void publish(DownloadEvent event) {
        // 进度事件节流：同一任务窗口内的重复 TASK_UPDATED 丢弃，降低监听器与 EDT 压力
        if (event.getType() == DownloadEvent.EventType.TASK_UPDATED && event.getTask() != null) {
            String taskId = event.getTask().getId();
            long now = System.currentTimeMillis();
            Long last = lastTaskUpdatedAt.get(taskId);
            if (last != null && now - last < TASK_UPDATED_THROTTLE_MS) {
                return;
            }
            lastTaskUpdatedAt.put(taskId, now);
        } else if (event.getType() == DownloadEvent.EventType.TASK_REMOVED
                && event.getTask() != null) {
            // 任务移除后清理节流时间戳，避免长期运行时的无效键
            lastTaskUpdatedAt.remove(event.getTask().getId());
        }
        for (DownloadListener listener : downloadListeners) {
            try {
                listener.onDownloadEvent(event);
            } catch (Exception e) {
                // 捕获监听器异常，避免影响其他监听器
                AppLogger.warn("监听器处理事件时出现异常: " + e.getMessage());
            }
        }
    }
    
    /**
     * 发布配置变更事件
     * @param event 配置变更事件
     */
    public void publishConfigChangeEvent(ConfigChangeEvent event) {
        for (ConfigChangeListener listener : configListeners) {
            try {
                listener.onConfigChange(event);
            } catch (Exception e) {
                // 捕获监听器异常，避免影响其他监听器
                AppLogger.warn("配置监听器处理事件时出现异常: " + e.getMessage());
            }
        }
    }
    
    /**
     * 获取当前注册的下载事件监听器数量
     * @return 监听器数量
     */
    public int getDownloadListenerCount() {
        return downloadListeners.size();
    }
    
    /**
     * 获取当前注册的配置变更监听器数量
     * @return 监听器数量
     */
    public int getConfigListenerCount() {
        return configListeners.size();
    }
}
