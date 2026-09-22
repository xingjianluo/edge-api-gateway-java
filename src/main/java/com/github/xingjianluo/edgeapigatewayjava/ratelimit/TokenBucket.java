package com.github.xingjianluo.edgeapigatewayjava.ratelimit;

/**
 * 令牌桶。所有状态在锁内更新，{@link #tryAcquire()} 保证并发下不超发：
 * 桶内令牌不足时拒绝，拒绝不产生任何扣除。
 *
 * 桶容量取速率（允许初始突发最多 1 秒配额），稳态下每秒平均放行速率不超过 rate。
 */
public class TokenBucket {

    private final double ratePerSecond;
    private final double capacity;
    private double tokens;
    private long lastRefillNanos;

    public TokenBucket(double ratePerSecond) {
        if (ratePerSecond <= 0) {
            throw new IllegalArgumentException("rate must be positive: " + ratePerSecond);
        }
        this.ratePerSecond = ratePerSecond;
        this.capacity = ratePerSecond;
        this.tokens = capacity;
        this.lastRefillNanos = System.nanoTime();
    }

    public synchronized boolean tryAcquire() {
        refill();
        if (tokens >= 1.0d) {
            tokens -= 1.0d;
            return true;
        }
        return false;
    }

    /** 当前可用令牌（诊断用）。 */
    public synchronized double availableTokens() {
        refill();
        return tokens;
    }

    /**
     * 回滚一次已扣减的令牌。仅用于"调用方桶已扣、路由桶拒绝"的补偿，
     * 保证多维度检查的原子语义。
     */
    public synchronized void refillOneForRollback() {
        refill();
        tokens = Math.min(capacity, tokens + 1.0d);
    }

    private void refill() {
        long now = System.nanoTime();
        long elapsed = now - lastRefillNanos;
        if (elapsed > 0) {
            double seconds = elapsed / 1_000_000_000.0d;
            tokens = Math.min(capacity, tokens + seconds * ratePerSecond);
            lastRefillNanos = now;
        }
    }
}
