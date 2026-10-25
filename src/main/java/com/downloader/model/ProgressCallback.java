package com.downloader.model;

/**
 * 下载进度回调接口
 * 所有方法均在后台线程调用，GUI 实现需用 SwingUtilities.invokeLater 包装
 */
public interface ProgressCallback {

    /** 连接成功，返回文件名和总大小（-1 表示未知） */
    void onConnected(String fileName, long totalSize);

    /**
     * 最终落盘文件已确定（处理重名避让之后）。
     * 下载器内部可能因重名生成 "file (1).ext"，或使用与文件名不同的输出路径，
     * 上层必须以本回调为准记录实际保存路径，否则"打开文件/续传"会指向错误位置。
     */
    default void onSaveFile(java.io.File saveFile) {
    }

    /** 进度更新 */
    void onProgress(long downloaded, long totalSize, long speedBps);

    /** 状态消息 */
    void onStatusUpdate(String message);

    /** 下载完成 */
    void onComplete();

    /** 下载失败 */
    void onError(String message);
}
