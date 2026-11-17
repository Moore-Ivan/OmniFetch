package com.downloader.cloud;

import java.util.List;

/**
 * 单个网盘的分享解析实现。
 * <p>实现类只负责「识别链接 → 列文件 → 取直链」，网络会话与凭据由
 * {@link CloudContext} 统一提供，下载与断点续传复用项目已有的 HTTP 引擎。
 */
public interface CloudProvider {

    CloudDrive drive();

    /** 输入文本（可能是整段口令/带提取码的文案）是否属于本平台 */
    boolean matches(String text);

    /** 从输入文本解析分享标识与提取码；无法解析时抛出可读错误 */
    CloudShare parse(String text) throws CloudException;

    /**
     * 列出目录内容。{@code dir == null} 表示分享根目录。
     * 实现内部自行处理分页，返回当前目录的完整列表。
     */
    List<CloudFile> list(CloudContext ctx, CloudShare share, CloudFile dir) throws Exception;

    /** 获取文件的可下载直链（每次下载前重新调用，应对签名过期） */
    CloudLink resolve(CloudContext ctx, CloudShare share, CloudFile file) throws Exception;
}
