package com.downloader.cloud;

/**
 * 网盘解析过程中的可读错误（凭据缺失、提取码错误、接口返回异常等）。
 */
public class CloudException extends Exception {

    public CloudException(String message) {
        super(message);
    }

    public CloudException(String message, Throwable cause) {
        super(message, cause);
    }
}
