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
    
    // 存储连接的最后使用时间，用于清理空闲超时的连接
    // （此前按"创建时间"清理，刚归还的活跃连接也可能被立刻清掉）
    private final ConcurrentMap<FtpConnection, Long> connectionLastUsedTimes;

    public FtpConnectionPool(String host, int port, String user, String pass) {
        super();
        this.host = host;
        this.port = port;
        this.user = user;
        this.pass = pass;
        ConfigManager configManager = ConfigManager.getInstance();
        this.connectTimeoutMs = configManager.getInt("ftp.connectionTimeoutSec", 30) * 1000;
        this.connectionLastUsedTimes = new ConcurrentHashMap<>();
    }

    /** 归还连接时刷新最后使用时间，避免活跃连接被空闲清理误杀 */
    @Override
    public void releaseConnection(FtpConnection connection) {
        if (connection != null) {
            connectionLastUsedTimes.put(connection, System.currentTimeMillis());
        }
        super.releaseConnection(connection);
    }

    @Override
    protected void cleanupIdleConnections() {
        long now = System.currentTimeMillis();
        for (FtpConnection connection : idleConnections) {
            Long lastUsed = connectionLastUsedTimes.get(connection);
            if (lastUsed != null && now - lastUsed > idleTimeoutMs) {
                if (idleConnections.remove(connection)) {
                    closeConnection(connection);
                }
            }
        }
    }

    /** 私网/环回/未指定地址：PASV 返回这类 IP 时应回退用控制连接的对端地址 */
    static boolean isUnroutableIp(String ip) {
        String[] octets = ip.split("\\.");
        if (octets.length != 4) {
            return true;
        }
        try {
            int a = Integer.parseInt(octets[0]);
            int b = Integer.parseInt(octets[1]);
            return a == 0 || a == 10 || a == 127
                    || (a == 100 && b >= 64 && b <= 127)      // CGNAT
                    || (a == 169 && b == 254)                 // link-local
                    || (a == 172 && b >= 16 && b <= 31)       // 私网
                    || (a == 192 && b == 168);                // 私网
        } catch (NumberFormatException e) {
            return true;
        }
    }

    @Override
    protected FtpConnection createConnection() throws Exception {
        FtpConnection connection = new FtpConnection();
        connection.connect();
        connectionLastUsedTimes.put(connection, System.currentTimeMillis());
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
            connectionLastUsedTimes.remove(connection);
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
            // 匿名 FTP 服务器可能对 USER 直接回 230（已登录），此时不需要再发 PASS
            int userCode = sendCommand("USER " + user, 230, 331);
            if (userCode == 331) {
                sendCommand("PASS " + pass, 230);
            }
            sendCommand("TYPE I", 200);
        }

        /**
         * 读取一条（可能多行的）FTP 响应，返回最终响应行（形如 "226 Transfer complete"）；
         * 不在接受列表内的最终码一律抛异常，避免上层在错误状态下继续操作
         */
        public String readResponseLine(int... acceptedCodes) throws IOException {
            String line = null;
            String finalLine = null;
            int code = -1;
            while ((line = controlIn.readLine()) != null) {
                if (line.length() < 4) {
                    continue;
                }
                int parsed;
                try {
                    parsed = Integer.parseInt(line.substring(0, 3));
                } catch (NumberFormatException e) {
                    continue;
                }
                if (line.charAt(3) == '-') {
                    // 多行响应的续行（形如 "220-Welcome ..."）
                    continue;
                }
                // 第 4 位为空格（或无续行标记）即为最终响应行
                code = parsed;
                finalLine = line;
                break;
            }
            if (finalLine == null) {
                throw new IOException("FTP 服务器关闭了连接或未返回响应");
            }
            // 不指定接受码 = 仅取响应行，由调用方自行解析（如 SIZE 的多码响应）
            if (acceptedCodes.length == 0) {
                return finalLine;
            }
            for (int accepted : acceptedCodes) {
                if (code == accepted) {
                    return finalLine;
                }
            }
            throw new IOException("FTP 响应码异常（期望 "
                    + java.util.Arrays.toString(acceptedCodes)
                    + "，实际 " + code + "）: " + finalLine);
        }

        /**
         * 发送命令并等待响应，返回最终响应码；不在接受列表内时抛异常（不再静默返回）
         */
        public int sendCommand(String cmd, int... acceptedCodes) throws IOException {
            controlOut.println(cmd);
            return readResponse(acceptedCodes);
        }

        /**
         * 发送命令并返回最终响应行（不校验响应码，由调用方自行解析）。
         * 适用于响应码多样的命令（如可选能力 SIZE：213 成功 / 500 不支持）。
         */
        public String sendCommandAndGetLine(String cmd) throws IOException {
            controlOut.println(cmd);
            return readResponseLine();
        }

        /**
         * 读取响应并返回最终响应码
         */
        public int readResponse(int... acceptedCodes) throws IOException {
            String finalLine = readResponseLine(acceptedCodes);
            try {
                return Integer.parseInt(finalLine.substring(0, 3));
            } catch (NumberFormatException e) {
                throw new IOException("FTP 响应码解析失败: " + finalLine);
            }
        }

        /**
         * 进入被动模式。兼容不带括号的 227 响应与多行响应；
         * 服务器返回私网/环回 IP（NAT 场景）时回退用控制连接的对端地址。
         */
        public int[] enterPassiveMode() throws IOException {
            controlOut.println("PASV");
            String line = readResponseLine(227);

            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("(\\d{1,3}),(\\d{1,3}),(\\d{1,3}),(\\d{1,3}),(\\d{1,3}),(\\d{1,3})")
                    .matcher(line);
            if (!m.find()) {
                throw new IOException("Cannot parse PASV response: " + line);
            }

            int[] octets = new int[4];
            for (int i = 0; i < 4; i++) {
                octets[i] = Integer.parseInt(m.group(i + 1));
            }
            int dataPort = Integer.parseInt(m.group(5)) * 256
                    + Integer.parseInt(m.group(6));

            String dataIp = octets[0] + "." + octets[1] + "."
                    + octets[2] + "." + octets[3];
            if (isUnroutableIp(dataIp)) {
                // NAT 后的私网地址外部不可达：改用控制连接实际对端地址
                java.net.InetAddress remote = controlSocket != null
                        ? controlSocket.getInetAddress() : null;
                if (remote == null) {
                    throw new IOException("PASV 返回不可路由地址且无法获取控制连接对端地址: " + line);
                }
                byte[] addr = remote.getAddress();
                if (addr.length == 4) {
                    octets[0] = addr[0] & 0xFF;
                    octets[1] = addr[1] & 0xFF;
                    octets[2] = addr[2] & 0xFF;
                    octets[3] = addr[3] & 0xFF;
                }
            }

            return new int[]{octets[0], octets[1], octets[2], octets[3], dataPort};
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
