package com.downloader.pool;

import com.downloader.config.ConfigManager;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 抽象连接池基类
 * 实现基本的连接池功能
 */
public abstract class AbstractConnectionPool<T> implements ConnectionPool<T> {
    
    protected final BlockingQueue<T> idleConnections;
    protected final AtomicInteger activeConnections;
    protected final int maxConnections;
    protected final long idleTimeoutMs;
    protected volatile boolean closed = false;
    
    public AbstractConnectionPool(int maxConnections, long idleTimeoutMs) {
        this.maxConnections = maxConnections;
        this.idleTimeoutMs = idleTimeoutMs;
        this.idleConnections = new LinkedBlockingQueue<>(maxConnections);
        this.activeConnections = new AtomicInteger(0);
        startCleanupThread();
    }
    
    public AbstractConnectionPool() {
        ConfigManager configManager = ConfigManager.getInstance();
        this.maxConnections = configManager.getInt("ftp.maxConnections", 10);
        this.idleTimeoutMs = configManager.getLong("ftp.idleTimeoutSec", 60) * 1000;
        this.idleConnections = new LinkedBlockingQueue<>(maxConnections);
        this.activeConnections = new AtomicInteger(0);
        startCleanupThread();
    }
    
    @Override
    public T getConnection() throws Exception {
        if (closed) {
            throw new IllegalStateException("Connection pool is closed");
        }
        
        // 尝试从空闲连接队列获取
        T connection = idleConnections.poll();
        if (connection != null && isValid(connection)) {
            activeConnections.incrementAndGet();
            return connection;
        }
        
        // 如果没有空闲连接且未达到最大连接数，创建新连接
        if (activeConnections.get() < maxConnections) {
            if (activeConnections.incrementAndGet() <= maxConnections) {
                try {
                    return createConnection();
                } catch (Exception e) {
                    activeConnections.decrementAndGet();
                    throw e;
                }
            } else {
                activeConnections.decrementAndGet();
            }
        }
        
        // 等待空闲连接
        connection = idleConnections.poll(30, java.util.concurrent.TimeUnit.SECONDS);
        if (connection != null && isValid(connection)) {
            activeConnections.incrementAndGet();
            return connection;
        }
        
        throw new Exception("No available connections");
    }
    
    @Override
    public void releaseConnection(T connection) {
        if (closed || connection == null) {
            closeConnection(connection);
            return;
        }
        
        if (isValid(connection) && activeConnections.get() > 0) {
            activeConnections.decrementAndGet();
            if (!idleConnections.offer(connection)) {
                // 队列已满，关闭连接
                closeConnection(connection);
            }
        } else {
            closeConnection(connection);
            if (activeConnections.get() > 0) {
                activeConnections.decrementAndGet();
            }
        }
    }
    
    @Override
    public void close() {
        closed = true;
        T connection;
        while ((connection = idleConnections.poll()) != null) {
            closeConnection(connection);
        }
    }
    
    @Override
    public int getIdleConnectionCount() {
        return idleConnections.size();
    }
    
    @Override
    public int getActiveConnectionCount() {
        return activeConnections.get();
    }
    
    /**
     * 启动清理线程，定期清理空闲超时的连接
     */
    private void startCleanupThread() {
        Thread cleanupThread = new Thread(() -> {
            while (!closed) {
                try {
                    Thread.sleep(idleTimeoutMs / 2);
                    cleanupIdleConnections();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });
        cleanupThread.setDaemon(true);
        cleanupThread.start();
    }
    
    /**
     * 清理空闲超时的连接
     */
    protected abstract void cleanupIdleConnections();
    
    /**
     * 创建新连接
     */
    protected abstract T createConnection() throws Exception;
    
    /**
     * 验证连接是否有效
     */
    protected abstract boolean isValid(T connection);
    
    /**
     * 关闭连接
     */
    protected abstract void closeConnection(T connection);
}
