package com.downloader.event;

/**
 * 下载事件监听器接口
 * 实现此接口的类可以订阅下载事件
 */
public interface DownloadListener {
    
    /**
     * 处理下载事件
     * @param event 下载事件
     */
    void onDownloadEvent(DownloadEvent event);
}
