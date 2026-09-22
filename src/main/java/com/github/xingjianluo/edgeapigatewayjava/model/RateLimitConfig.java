package com.github.xingjianluo.edgeapigatewayjava.model;

/** 限流/配额配置（按路由 + 调用方维度），骨架占位。 */
public class RateLimitConfig {

    private int permitsPerWindow = -1;
    private int windowMillis = 1000;
    private long dailyQuota = -1L;

    public int getPermitsPerWindow() {
        return permitsPerWindow;
    }

    public void setPermitsPerWindow(int permitsPerWindow) {
        this.permitsPerWindow = permitsPerWindow;
    }

    public int getWindowMillis() {
        return windowMillis;
    }

    public void setWindowMillis(int windowMillis) {
        this.windowMillis = windowMillis;
    }

    public long getDailyQuota() {
        return dailyQuota;
    }

    public void setDailyQuota(long dailyQuota) {
        this.dailyQuota = dailyQuota;
    }
}
