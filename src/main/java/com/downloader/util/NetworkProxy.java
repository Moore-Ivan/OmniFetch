package com.downloader.util;

import com.downloader.config.ConfigManager;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 下载代理支持：让应用在无法直连 GitHub 等站点时也能正常下载。
 *
 * 解析顺序：
 * 1. 手动配置（network.proxyHost / network.proxyPort / network.proxyType）；
 * 2. 环境变量（HTTPS_PROXY / HTTP_PROXY / ALL_PROXY，含大小写变体）；
 * 3. Windows 系统代理（注册表 ProxyEnable / ProxyServer，Clash、v2ray 等开启系统代理后即可自动使用）。
 *
 * HttpDownloader 会先按上述结果创建客户端；若直连探测失败，还会再检测一次并自动切换，
 * 因此"应用先启动、代理后启动"也能恢复。
 */
public final class NetworkProxy {

    private static final ConfigManager CONFIG = ConfigManager.getInstance();

    /** 代理配置：选择器 + 可选的代理认证 + 用于日志的描述 */
    public static final class Config {
        public final ProxySelector selector;
        public final Authenticator authenticator;
        public final String description;

        private Config(ProxySelector selector, Authenticator authenticator, String description) {
            this.selector = selector;
            this.authenticator = authenticator;
            this.description = description;
        }
    }

    private NetworkProxy() {
    }

    /** 是否允许使用代理（关闭后完全直连） */
    public static boolean enabled() {
        return CONFIG.getBoolean("network.proxyEnabled", true);
    }

    /** 初始代理：手动配置优先，其次自动检测（环境变量/系统代理）；都没有则返回 null（直连） */
    public static Config initial() {
        if (!enabled()) {
            return null;
        }
        Config manual = manual();
        if (manual != null) {
            return manual;
        }
        return detected();
    }

    /** 手动配置的代理（主机为空时返回 null） */
    public static Config manual() {
        String host = CONFIG.getString("network.proxyHost", "").trim();
        if (host.isEmpty()) {
            return null;
        }
        int port = CONFIG.getInt("network.proxyPort", 7890);
        boolean socks = CONFIG.getString("network.proxyType", "http")
                .trim().equalsIgnoreCase("socks");
        return build(host, port, socks, null, null,
                "手动代理 " + host + ":" + port);
    }

    /** 自动检测系统/环境代理；未检测到返回 null */
    public static Config detected() {
        if (!enabled()) {
            return null;
        }
        Config env = fromEnvironment();
        if (env != null) {
            return env;
        }
        return fromWindowsSystemProxy();
    }

    private static Config fromEnvironment() {
        String[] keys = {"HTTPS_PROXY", "https_proxy", "HTTP_PROXY", "http_proxy",
                "ALL_PROXY", "all_proxy"};
        for (String key : keys) {
            String value = System.getenv(key);
            if (value == null || value.isBlank()) {
                continue;
            }
            String raw = value.trim();
            if (!raw.contains("://")) {
                raw = "http://" + raw;
            }
            try {
                URI uri = URI.create(raw);
                String scheme = uri.getScheme() == null ? "http" : uri.getScheme().toLowerCase();
                boolean socks = scheme.startsWith("socks");
                String host = uri.getHost();
                if (host == null || host.isBlank()) {
                    continue;
                }
                int port = uri.getPort();
                if (port <= 0) {
                    port = socks ? 1080 : 80;
                }
                String userInfo = uri.getUserInfo();
                String user = null;
                String pass = null;
                if (userInfo != null && userInfo.contains(":")) {
                    int idx = userInfo.indexOf(':');
                    user = userInfo.substring(0, idx);
                    pass = userInfo.substring(idx + 1);
                }
                return build(host, port, socks, user, pass,
                        "环境变量 " + key + " " + host + ":" + port);
            } catch (Exception ignored) {
                // 解析失败就继续尝试下一个变量
            }
        }
        return null;
    }

    private static Config fromWindowsSystemProxy() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (!os.contains("win")) {
            return null;
        }
        try {
            String enable = queryRegistry("ProxyEnable");
            String server = queryRegistry("ProxyServer");
            if (enable == null || !enable.toLowerCase().contains("0x1")) {
                return null;
            }
            if (server == null || server.isBlank()) {
                return null;
            }
            String address = pickProxyAddress(server);
            if (address == null) {
                return null;
            }
            int colon = address.lastIndexOf(':');
            if (colon <= 0) {
                return null;
            }
            String host = address.substring(0, colon).trim();
            int port = Integer.parseInt(address.substring(colon + 1).trim());
            if (host.isEmpty() || port <= 0) {
                return null;
            }
            return build(host, port, false, null, null,
                    "系统代理 " + host + ":" + port);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * ProxyServer 可能是 "host:port"，也可能是
     * "http=host:port;https=host:port;socks=host:port"。优先 https，其次 http。
     */
    private static String pickProxyAddress(String server) {
        if (!server.contains("=")) {
            return server.trim();
        }
        String http = null;
        String https = null;
        for (String part : server.split(";")) {
            String[] kv = part.split("=", 2);
            if (kv.length != 2) {
                continue;
            }
            String key = kv[0].trim().toLowerCase();
            String value = kv[1].trim();
            if (key.equals("https")) {
                https = value;
            } else if (key.equals("http")) {
                http = value;
            }
        }
        if (https != null && !https.isEmpty()) {
            return https;
        }
        if (http != null && !http.isEmpty()) {
            return http;
        }
        return null;
    }

    /** 读取注册表字符串值；失败返回 null */
    private static String queryRegistry(String valueName) throws IOException {
        Process process = new ProcessBuilder("reg", "query",
                "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings",
                "/v", valueName)
                .redirectErrorStream(true)
                .start();
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append('\n');
            }
        }
        try {
            process.waitFor(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        for (String line : output.toString().split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.toLowerCase().startsWith(valueName.toLowerCase())) {
                continue;
            }
            String[] parts = trimmed.split("\\s+");
            if (parts.length > 0) {
                return parts[parts.length - 1];
            }
        }
        return null;
    }

    private static Config build(String host, int port, boolean socks,
                                String user, String pass, String description) {
        Proxy.Type type = socks ? Proxy.Type.SOCKS : Proxy.Type.HTTP;
        Proxy proxy = new Proxy(type, InetSocketAddress.createUnresolved(host, port));
        ProxySelector selector = new LocalBypassSelector(new FixedSelector(proxy));
        Authenticator authenticator = null;
        if (user != null && !user.isEmpty()) {
            authenticator = new Authenticator() {
                @Override
                protected PasswordAuthentication getPasswordAuthentication() {
                    if (getRequestorType() == RequestorType.PROXY) {
                        return new PasswordAuthentication(user, pass == null ? new char[0] : pass.toCharArray());
                    }
                    return null;
                }
            };
        }
        return new Config(selector, authenticator, description);
    }

    /** 固定代理选择器（支持 SOCKS；地址保持未解析，由 HttpClient 连接时解析） */
    private static final class FixedSelector extends ProxySelector {
        private final Proxy proxy;

        private FixedSelector(Proxy proxy) {
            this.proxy = proxy;
        }

        @Override
        public List<Proxy> select(URI uri) {
            return List.of(proxy);
        }

        @Override
        public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
            // 由 HttpClient 负责重试/降级
        }
    }

    /** 本地/回环地址始终直连，避免把 127.0.0.1 的下载也绕到代理 */
    private static final class LocalBypassSelector extends ProxySelector {
        private final ProxySelector delegate;

        private LocalBypassSelector(ProxySelector delegate) {
            this.delegate = delegate;
        }

        @Override
        public List<Proxy> select(URI uri) {
            String host = uri == null ? null : uri.getHost();
            if (host != null && isLocalHost(host)) {
                return List.of(Proxy.NO_PROXY);
            }
            return delegate.select(uri);
        }

        @Override
        public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
            delegate.connectFailed(uri, sa, ioe);
        }

        private static boolean isLocalHost(String host) {
            String lower = host.toLowerCase();
            return lower.equals("localhost") || lower.equals("127.0.0.1")
                    || lower.equals("::1") || lower.equals("[::1]");
        }
    }
}
