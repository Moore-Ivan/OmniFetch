package com.downloader.pool;

/**
 * 连接池接口
 * 用于管理和复用连接
 */
public interface ConnectionPool<T> {
    
    /**
     * 获取连接
     * @return 连接对象
     * @throws Exception 异常
     */
    T getConnection() throws Exception;
    
    /**
     * 释放连接
     * @param connection 连接对象
     */
    void releaseConnection(T connection);
    
    /**
     * 关闭连接池
     */
    void close();
    
    /**
     * 获取当前空闲连接数
     * @return 空闲连接数
     */
    int getIdleConnectionCount();
    
    /**
     * 获取当前活跃连接数
     * @return 活跃连接数
     */
    int getActiveConnectionCount();
}
