package com.downloader.util;

/**
 * 令牌桶限速器：以字节/秒为限速单位，供各下载器在读写循环中调用。
 * <ul>
 *   <li>{@code limitBps <= 0} 表示不限速，acquire 直接放行；</li>
 *   <li>令牌平滑补充，桶容量为一个周期的配额（突发上限 = 1 秒限速值）；</li>
 *   <li>运行中可通过 {@link #setLimitBytesPerSec(long)} 即时调整（如全局配置热更新、
 *       单任务右键限速），调整后立即生效；</li>
 *   <li>多个下载线程共享同一实例时分摊总速率（HTTP 多分片 / M3U8 并发）。</li>
 * </ul>
 */
public final class RateLimiter {

    /** 限速值（字节/秒），volatile 保证配置线程修改对下载线程立即可见 */
    private volatile long limitBps;
    /** 当前可用令牌（字节） */
    private double available;
    private long lastRefillNanos;

    public RateLimiter(long limitBps) {
        this.limitBps = Math.max(0, limitBps);
        this.lastRefillNanos = System.nanoTime();
    }

    /** 调整限速值；0 或负数表示不限速。调整后重置桶使新限速立即生效 */
    public void setLimitBytesPerSec(long bytesPerSec) {
        synchronized (this) {
            this.limitBps = Math.max(0, bytesPerSec);
            this.available = 0;
            this.lastRefillNanos = System.nanoTime();
            this.notifyAll(); // 唤醒正在等待令牌的线程按新限速重新计算
        }
    }

    public long getLimitBytesPerSec() {
        return limitBps;
    }

    /**
     * 获取 n 字节令牌；配额不足时阻塞等待。
     * <p>缺口超过单次等待窗口（大请求 / 低限速）时采用透支预约：整笔扣减
     * （available 允许为负），等待按当前限速补足缺口的时间后返回——
     * 否则 available 封顶在桶容量（1 秒配额）后永远追不上 n，请求死锁。
     * <p>被中断时保留中断标记并放行——调用方循环中由 cancelled 标志统一接管取消。
     */
    public void acquire(long n) {
        if (n <= 0) {
            return;
        }
        // 快路径：不限速直接放行（volatile 读，无锁）
        if (limitBps <= 0) {
            return;
        }
        synchronized (this) {
            while (limitBps > 0) {
                refill();
                if (available >= n) {
                    available -= n;
                    return;
                }
                double deficit = n - available;
                long needMs = (long) Math.ceil(deficit / limitBps * 1000.0);
                if (needMs > 500) {
                    // 透支预约：整笔扣减后等待补足时间（被 notifyAll 提前唤醒的
                    // 场景是限速热调整，桶已重置，直接放行交由后续请求按新限速约束）
                    available -= n;
                    try {
                        this.wait(Math.min(needMs, 10_000));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return;
                }
                try {
                    // 短缺口：等待后下一轮 refill 即可满足
                    this.wait(Math.max(needMs, 1));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            // 限速被改为 0（不限速）：直接放行
        }
    }

    /** 按经过时间补充令牌，桶上限为 1 秒配额（平滑突发） */
    private void refill() {
        long now = System.nanoTime();
        long elapsed = now - lastRefillNanos;
        if (elapsed > 0 && limitBps > 0) {
            available = Math.min(available + elapsed / 1_000_000_000.0 * limitBps, limitBps);
            lastRefillNanos = now;
        }
    }

    /** 空安全取令牌：下载器构造时可传入 null（如插件通道），统一在此判空 */
    public static void acquire(RateLimiter limiter, long n) {
        if (limiter != null) {
            limiter.acquire(n);
        }
    }
}
