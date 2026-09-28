package com.downloader.protocol;

/**
 * 所有下载协议的统一接口
 */
public interface DownloadProtocol {

    /** 开始下载（阻塞直到完成或取消） */
    void download() throws Exception;

    /** 取消下载 */
    void cancel();

    /** 释放所有资源 */
    void close();
}
