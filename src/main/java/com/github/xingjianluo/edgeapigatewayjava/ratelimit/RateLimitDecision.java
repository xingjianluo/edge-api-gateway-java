package com.github.xingjianluo.edgeapigatewayjava.ratelimit;

/**
 * 限流判定结果。
 */
public record RateLimitDecision(boolean allowed, String scope, String reason) {

    public static RateLimitDecision allow() {
        return new RateLimitDecision(true, null, null);
    }

    public static RateLimitDecision reject(String scope, String reason) {
        return new RateLimitDecision(false, scope, reason);
    }
}
