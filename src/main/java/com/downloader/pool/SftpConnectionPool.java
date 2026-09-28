package com.downloader.pool;

import com.downloader.config.ConfigManager;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * SFTP连接池
 * 管理SFTP连接的创建、复用和释放
 */
public class SftpConnectionPool extends AbstractConnectionPool<SftpConnectionPool.SftpConnection> {
    
    private final String host;
    private final int port;
    private final String user;
    private final String pass;
    private final int connectTimeoutMs;
    
    // 存储连接的创建时间，用于清理超时连接
    private final ConcurrentMap<SftpConnection, Long> connectionCreationTimes;
    
    public SftpConnectionPool(String host, int port, String user, String pass) {
        super();
        this.host = host;
        this.port = port;
        this.user = user;
        this.pass = pass;
        ConfigManager configManager = ConfigManager.getInstance();
        this.connectTimeoutMs = configManager.getInt("ftp.connectionTimeoutSec", 30) * 1000;
        this.connectionCreationTimes = new ConcurrentHashMap<>();
    }
    
    @Override
    protected void cleanupIdleConnections() {
        long now = System.currentTimeMillis();
        for (SftpConnection connection : idleConnections) {
            Long createTime = connectionCreationTimes.get(connection);
            if (createTime != null && now - createTime > idleTimeoutMs) {
                if (idleConnections.remove(connection)) {
                    closeConnection(connection);
                    connectionCreationTimes.remove(connection);
                }
            }
        }
    }
    
    @Override
    protected SftpConnection createConnection() throws Exception {
        SftpConnection connection = new SftpConnection();
        connection.connect();
        connectionCreationTimes.put(connection, System.currentTimeMillis());
        return connection;
    }
    
    @Override
    protected boolean isValid(SftpConnection connection) {
        try {
            return connection.isConnected();
        } catch (Exception e) {
            return false;
        }
    }
    
    @Override
    protected void closeConnection(SftpConnection connection) {
        if (connection != null) {
            connectionCreationTimes.remove(connection);
            connection.close();
        }
    }
    
    /**
     * SFTP连接包装类
     */
    public class SftpConnection {
        private Session session;
        private ChannelSftp channel;
        
        /**
         * 连接到SFTP服务器
         */
        public void connect() throws Exception {
            JSch jsch = new JSch();
            session = jsch.getSession(user, host, port);
            session.setPassword(pass);
            session.setConfig("StrictHostKeyChecking", "no");
            session.setTimeout(connectTimeoutMs);
            session.connect();
            
            channel = (ChannelSftp) session.openChannel("sftp");
            channel.connect(10000);
        }
        
        /**
         * 获取SFTP通道
         */
        public ChannelSftp getChannel() {
            return channel;
        }
        
        /**
         * 检查连接是否活跃
         */
        public boolean isConnected() {
            return session != null && session.isConnected() && 
                   channel != null && channel.isConnected();
        }
        
        /**
         * 关闭连接
         */
        public void close() {
            if (channel != null) {
                try { channel.disconnect(); } catch (Exception ignored) {}
                channel = null;
            }
            if (session != null) {
                try { session.disconnect(); } catch (Exception ignored) {}
                session = null;
            }
        }
    }
}
