package com.downloader.plugin;

import com.downloader.model.ProgressCallback;
import com.downloader.protocol.DownloadProtocol;

/**
 * 协议插件接口
 * 第三方开发者可以实现此接口来添加新的下载协议支持
 */
public interface ProtocolPlugin {
    
    /**
     * 获取插件名称
     * @return 插件名称
     */
    String getName();
    
    /**
     * 获取插件支持的协议名称
     * @return 协议名称
     */
    String getProtocolName();
    
    /**
     * 检测URL是否使用此插件支持的协议
     * @param url 下载URL
     * @return 是否支持
     */
    boolean canHandle(String url);
    
    /**
     * 创建下载器实例
     * @param url 下载URL
     * @param savePath 保存路径
     * @param username 用户名
     * @param password 密码
     * @param callback 进度回调
     * @param downloadedSize 已下载大小
     * @param saveFilePath 保存文件路径
     * @return 下载器实例
     * @throws Exception 异常
     */
    DownloadProtocol createDownloader(String url, String savePath, String username, String password,
                                     ProgressCallback callback, long downloadedSize, String saveFilePath) throws Exception;
}
