package com.github.xingjianluo.edgeapigatewayjava.model;

import java.util.ArrayList;
import java.util.List;

/** 网关配置模型（可由 JSON 反序列化），骨架占位。 */
public class GatewayConfig {

    private String callerHeader;
    private int defaultTimeoutMillis = 5000;
    private int maxBodyBytes = 10 * 1024 * 1024;
    private int dedupTtlMillis = 60_000;
    private List<RouteConfig> routes = new ArrayList<>();

    public String getCallerHeader() {
        return callerHeader;
    }

    public void setCallerHeader(String callerHeader) {
        this.callerHeader = callerHeader;
    }

    public int getDefaultTimeoutMillis() {
        return defaultTimeoutMillis;
    }

    public void setDefaultTimeoutMillis(int defaultTimeoutMillis) {
        this.defaultTimeoutMillis = defaultTimeoutMillis;
    }

    public int getMaxBodyBytes() {
        return maxBodyBytes;
    }

    public void setMaxBodyBytes(int maxBodyBytes) {
        this.maxBodyBytes = maxBodyBytes;
    }

    public int getDedupTtlMillis() {
        return dedupTtlMillis;
    }

    public void setDedupTtlMillis(int dedupTtlMillis) {
        this.dedupTtlMillis = dedupTtlMillis;
    }

    public List<RouteConfig> getRoutes() {
        return routes;
    }

    public void setRoutes(List<RouteConfig> routes) {
        this.routes = routes;
    }
}
