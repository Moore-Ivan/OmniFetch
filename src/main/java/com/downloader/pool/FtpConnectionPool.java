package com.downloader.pool;

import com.downloader.config.ConfigManager;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * FTP连接池
 * 管理FTP连接的创建、复用和释放
 */
public class FtpConnectionPool extends AbstractConnectionPool<FtpConnectionPool.FtpConnection> {
    
    private final String host;
    private final int port;
    private final String user;
    private final String pass;
    private final int connectTimeoutMs;
    
    // 存储连接的创建时间，用于清理超时连接
    private final ConcurrentMap<FtpConnection, Long> connectionCreationTimes;
    
    public FtpConnectionPool(String host, int port, String user, String pass) {
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
        for (FtpConnection connection : idleConnections) {
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
    protected FtpConnection createConnection() throws Exception {
        FtpConnection connection = new FtpConnection();
        connection.connect();
        connectionCreationTimes.put(connection, System.currentTimeMillis());
        return connection;
    }
    
    @Override
    protected boolean isValid(FtpConnection connection) {
        try {
            if (!connection.isConnected()) {
                return false;
            }
            // 发送NOOP命令测试连接
            connection.sendCommand("NOOP", 200);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
    
    @Override
    protected void closeConnection(FtpConnection connection) {
        if (connection != null) {
            connectionCreationTimes.remove(connection);
            connection.close();
        }
    }
    
    /**
     * FTP连接包装类
     */
    public class FtpConnection {
        private Socket controlSocket;
        private BufferedReader controlIn;
        private PrintWriter controlOut;
        
        /**
         * 连接到FTP服务器
         */
        public void connect() throws Exception {
            controlSocket = new Socket();
            controlSocket.connect(new InetSocketAddress(host, port), connectTimeoutMs);
            controlIn = new BufferedReader(new InputStreamReader(
                    controlSocket.getInputStream(), StandardCharsets.UTF_8));
            controlOut = new PrintWriter(new OutputStreamWriter(
                    controlSocket.getOutputStream(), StandardCharsets.UTF_8), true);
            
            readResponse(220);
            sendCommand("USER " + user, 331);
            sendCommand("PASS " + pass, 230);
            sendCommand("TYPE I", 200);
        }
        
        /**
         * 发送命令并等待响应
         */
        public void sendCommand(String cmd, int expectedCode) throws IOException {
            controlOut.println(cmd);
            String line;
            while ((line = controlIn.readLine()) != null) {
                if (line.length() >= 3) {
                    int code = Integer.parseInt(line.substring(0, 3));
                    if (code == expectedCode) return;
                    if (code >= 400) throw new IOException("FTP error: " + line);
                }
                if (line.length() > 3 && line.charAt(3) == '-') continue;
                break;
            }
        }
        
        /**
         * 发送命令并获取响应
         */
        public String sendCommandAndGetResponse(String cmd) throws IOException {
            controlOut.println(cmd);
            String line = controlIn.readLine();
            if (line == null) throw new IOException("No response");
            return line;
        }
        
        /**
         * 读取响应
         */
        public void readResponse(int expectedCode) throws IOException {
            String line;
            while ((line = controlIn.readLine()) != null) {
                if (line.length() >= 3) {
                    int code = Integer.parseInt(line.substring(0, 3));
                    if (code == expectedCode) return;
                    if (code >= 400) throw new IOException("FTP error: " + line);
                }
                if (line.length() > 3 && line.charAt(3) == '-') continue;
                break;
            }
        }
        
        /**
         * 进入被动模式
         */
        public int[] enterPassiveMode() throws IOException {
            controlOut.println("PASV");
            String line = controlIn.readLine();
            if (line == null || !line.startsWith("227")) {
                throw new IOException("PASV failed: " + line);
            }
            
            int start = line.indexOf('(');
            int end = line.indexOf(')');
            if (start < 0 || end < 0) {
                throw new IOException("Cannot parse PASV response: " + line);
            }
            
            String[] parts = line.substring(start + 1, end).split(",");
            if (parts.length != 6) {
                throw new IOException("Invalid PASV format: " + line);
            }
            
            int dataPort = Integer.parseInt(parts[4].trim()) * 256
                    + Integer.parseInt(parts[5].trim());
            
            return new int[]{
                    Integer.parseInt(parts[0].trim()),
                    Integer.parseInt(parts[1].trim()),
                    Integer.parseInt(parts[2].trim()),
                    Integer.parseInt(parts[3].trim()),
                    dataPort
            };
        }
        
        /**
         * 检查连接是否活跃
         */
        public boolean isConnected() {
            return controlSocket != null && controlSocket.isConnected() && !controlSocket.isClosed();
        }
        
        /**
         * 关闭连接
         */
        public void close() {
            try {
                if (controlOut != null) {
                    controlOut.println("QUIT");
                    controlOut.flush();
                }
            } catch (Exception ignored) {}
            
            try {
                if (controlIn != null) controlIn.close();
            } catch (Exception ignored) {}
            
            try {
                if (controlOut != null) controlOut.close();
            } catch (Exception ignored) {}
            
            try {
                if (controlSocket != null) controlSocket.close();
            } catch (Exception ignored) {}
            
            controlSocket = null;
            controlIn = null;
            controlOut = null;
        }
    }
}
