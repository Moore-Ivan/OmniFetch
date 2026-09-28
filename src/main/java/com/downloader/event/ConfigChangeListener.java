package com.downloader.event;

/**
 * 配置变更监听器
 * 用于监听配置文件的变更
 */
public interface ConfigChangeListener {
    
    /**
     * 当配置文件变更时调用
     * @param event 配置变更事件
     */
    void onConfigChange(ConfigChangeEvent event);
}