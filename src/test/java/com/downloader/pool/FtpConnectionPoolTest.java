package com.downloader.pool;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * FtpConnectionPool.isUnroutableIp 单元测试：
 * PASV 返回私网/环回/链路本地地址时必须回退用控制连接对端地址（NAT 场景）。
 */
public class FtpConnectionPoolTest {

    @Test
    public void loopbackIsUnroutable() {
        assertTrue(FtpConnectionPool.isUnroutableIp("127.0.0.1"));
    }

    @Test
    public void privateNetworksAreUnroutable() {
        assertTrue(FtpConnectionPool.isUnroutableIp("10.1.2.3"));
        assertTrue(FtpConnectionPool.isUnroutableIp("192.168.0.1"));
        assertTrue(FtpConnectionPool.isUnroutableIp("172.16.0.1"));
        assertTrue(FtpConnectionPool.isUnroutableIp("172.31.255.254"));
    }

    @Test
    public void cgnatRangeIsUnroutable() {
        assertTrue(FtpConnectionPool.isUnroutableIp("100.64.0.1"));
        assertTrue(FtpConnectionPool.isUnroutableIp("100.127.255.254"));
    }

    @Test
    public void linkLocalAndUnspecifiedAreUnroutable() {
        assertTrue(FtpConnectionPool.isUnroutableIp("169.254.1.1"));
        assertTrue(FtpConnectionPool.isUnroutableIp("0.0.0.0"));
    }

    @Test
    public void malformedIpsAreTreatedAsUnroutable() {
        // 格式异常一律按不可路由处理，走控制连接对端回退
        assertTrue(FtpConnectionPool.isUnroutableIp("abc"));
        assertTrue(FtpConnectionPool.isUnroutableIp("1.2.3"));
        assertTrue(FtpConnectionPool.isUnroutableIp("1.2.3.4.5"));
        assertTrue(FtpConnectionPool.isUnroutableIp("a.b.c.d"));
    }

    @Test
    public void publicIpsAreRoutable() {
        assertFalse(FtpConnectionPool.isUnroutableIp("8.8.8.8"));
        assertFalse(FtpConnectionPool.isUnroutableIp("1.1.1.1"));
        // 边界外一格：172.32 / 172.15、100.63 / 100.128、192.169、169.253 都属可路由
        assertFalse(FtpConnectionPool.isUnroutableIp("172.32.0.1"));
        assertFalse(FtpConnectionPool.isUnroutableIp("172.15.0.1"));
        assertFalse(FtpConnectionPool.isUnroutableIp("100.63.0.1"));
        assertFalse(FtpConnectionPool.isUnroutableIp("100.128.0.1"));
        assertFalse(FtpConnectionPool.isUnroutableIp("192.169.0.1"));
        assertFalse(FtpConnectionPool.isUnroutableIp("169.253.0.1"));
    }
}
