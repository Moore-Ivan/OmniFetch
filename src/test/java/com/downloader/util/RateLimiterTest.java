package com.downloader.util;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * RateLimiter 单元测试：不限速放行、空安全、限速耗时。
 * 限速断言只设宽松下限（1.5s），规避 CI 慢机器的计时抖动。
 */
public class RateLimiterTest {

    @Test(timeout = 10_000)
    public void zeroLimitIsUnlimited() {
        RateLimiter limiter = new RateLimiter(0);
        long start = System.nanoTime();
        limiter.acquire(1024 * 1024 * 1024);
        assertTrue("不限速时 acquire 必须立即放行",
                (System.nanoTime() - start) < 1_000_000_000L);
    }

    @Test(timeout = 5_000)
    public void nonPositiveBytesIsNoOp() {
        new RateLimiter(100).acquire(0);
        new RateLimiter(100).acquire(-1);
    }

    @Test(timeout = 5_000)
    public void staticAcquireNullSafe() {
        RateLimiter.acquire(null, 1024);
    }

    @Test(timeout = 5_000)
    public void setLimitUpdatesValue() {
        RateLimiter limiter = new RateLimiter(1024);
        assertEquals(1024, limiter.getLimitBytesPerSec());
        limiter.setLimitBytesPerSec(0);
        assertEquals(0, limiter.getLimitBytesPerSec());
    }

    @Test(timeout = 20_000)
    public void limitedAcquireThrottlesToApproximateRate() {
        // 200 KB/s 拿 600 KB：理想耗时 ≈ 3s
        RateLimiter limiter = new RateLimiter(200 * 1024);
        long start = System.nanoTime();
        limiter.acquire(600L * 1024);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertTrue("限速应产生等待（实测 " + elapsedMs + "ms），仅设宽松下限防计时抖动",
                elapsedMs >= 1500);
        assertTrue("等待不应失控（实测 " + elapsedMs + "ms）",
                elapsedMs < 10_000);
    }

    @Test(timeout = 10_000)
    public void settingZeroLimitReleasesImmediately() {
        RateLimiter limiter = new RateLimiter(100 * 1024);
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(300);
                limiter.setLimitBytesPerSec(0); // 热调整后应立即放行
            } catch (InterruptedException ignored) {
            }
        });
        t.start();
        long start = System.nanoTime();
        limiter.acquire(1024 * 1024 * 1024);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertTrue("运行中调整为不限速后应放行（实测 " + elapsedMs + "ms）",
                elapsedMs < 5_000);
    }
}
