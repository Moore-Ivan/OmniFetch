package com.downloader.util;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import javax.net.ssl.HostnameVerifier;
import java.security.cert.X509Certificate;

/**
 * 信任所有证书的 HTTPS 配置（仅用于更新检查/下载等访问公开 GitHub 资源的场景）。
 * 解决部分运行环境 JDK 自带 cacerts 信任库不完整导致的
 * "PKIX path building failed / certificate_unknown" 连接失败。
 */
public final class HttpTrust {

    /** 信任所有证书的 SSLContext；初始化失败时为 null（回退 JDK 默认） */
    public static final SSLContext SSL_CONTEXT;
    public static final SSLSocketFactory SOCKET_FACTORY;
    private static final HostnameVerifier ALLOW_ALL_HOSTS = (hostname, session) -> true;

    static {
        SSLContext context = null;
        try {
            TrustManager[] trustAll = {
                new X509TrustManager() {
                    @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                    @Override public void checkClientTrusted(X509Certificate[] c, String t) { }
                    @Override public void checkServerTrusted(X509Certificate[] c, String t) { }
                }
            };
            context = SSLContext.getInstance("TLS");
            context.init(null, trustAll, new java.security.SecureRandom());
        } catch (Exception e) {
            System.err.println("初始化信任所有证书的 SSLContext 失败，将使用 JDK 默认信任库: "
                    + e.getMessage());
        }
        SSL_CONTEXT = context;
        SOCKET_FACTORY = context != null ? context.getSocketFactory() : null;
    }

    private HttpTrust() {
    }

    /**
     * 让一个 HTTPS 连接信任所有证书并放行主机名校验（含重定向后的其他域名，
     * 如 GitHub release 资源会跳转到 objects.githubusercontent.com）。
     * 非 HTTPS 连接或初始化失败时不做处理。
     */
    public static void apply(HttpsURLConnection conn) {
        if (SOCKET_FACTORY != null) {
            conn.setSSLSocketFactory(SOCKET_FACTORY);
            conn.setHostnameVerifier(ALLOW_ALL_HOSTS);
        }
    }
}
