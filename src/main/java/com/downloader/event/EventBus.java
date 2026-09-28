package com.downloader.event;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 事件总线实现
 * 支持多监听器订阅和事件发布
 */
public class EventBus {
    
    private static EventBus instance;
    private final List<DownloadListener> downloadListeners;
    private final List<ConfigChangeListener> configListeners;
    
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
        for (DownloadListener listener : downloadListeners) {
            try {
                listener.onDownloadEvent(event);
            } catch (Exception e) {
                // 捕获监听器异常，避免影响其他监听器
                System.err.println("监听器处理事件时出现异常: " + e.getMessage());
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
                System.err.println("配置监听器处理事件时出现异常: " + e.getMessage());
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
