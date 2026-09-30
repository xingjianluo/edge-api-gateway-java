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

    /**
     * 原子预占一个令牌：成功时返回可用于精确撤销的检查点（扣减完成后的状态），
     * 失败（无令牌）返回 null 且不改变任何状态。
     *
     * 用于需要跨多个维度/层级做原子决策的场景：后续步骤失败时用 {@link #release(Reservation)}
     * 把桶精确恢复到预占发生前的状态（包括撤销本次预占触发的时间补充）。
     */
    public synchronized Reservation reserve() {
        double before = tokens;
        long beforeTime = lastRefillNanos;
        refill();
        if (tokens < 1.0d) {
            return null;
        }
        tokens -= 1.0d;
        return new Reservation(before, beforeTime);
    }

    /** 精确撤销一次成功的 {@link #reserve()}。 */
    public synchronized void release(Reservation reservation) {
        this.tokens = reservation.tokensBefore();
        this.lastRefillNanos = reservation.timeBefore();
    }

    /** 一次成功预占的凭证，记录预占发生前的精确状态。 */
    public record Reservation(double tokensBefore, long timeBefore) {
    }

    /** 当前可用令牌（诊断用）。 */
    public synchronized double availableTokens() {
        refill();
        return tokens;
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
