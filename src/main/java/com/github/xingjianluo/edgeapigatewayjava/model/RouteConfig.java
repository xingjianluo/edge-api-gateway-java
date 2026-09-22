package com.github.xingjianluo.edgeapigatewayjava.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 单条路由规则，骨架占位。 */
public class RouteConfig {

    private String id;
    private String pathPrefix;
    private String method;
    private Map<String, String> requiredHeaders = new LinkedHashMap<>();
    private String upstreamBaseUrl;
    private String stripPrefix;
    private int timeoutMillis;
    private int retries = 1;
    private boolean requireCaller = true;
    private List<String> allowedCallers = new ArrayList<>();
    private RateLimitConfig rateLimit;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getPathPrefix() {
        return pathPrefix;
    }

    public void setPathPrefix(String pathPrefix) {
        this.pathPrefix = pathPrefix;
    }

    public String getMethod() {
        return method;
    }

    public void setMethod(String method) {
        this.method = method;
    }

    public Map<String, String> getRequiredHeaders() {
        return requiredHeaders;
    }

    public void setRequiredHeaders(Map<String, String> requiredHeaders) {
        this.requiredHeaders = requiredHeaders;
    }

    public String getUpstreamBaseUrl() {
        return upstreamBaseUrl;
    }

    public void setUpstreamBaseUrl(String upstreamBaseUrl) {
        this.upstreamBaseUrl = upstreamBaseUrl;
    }

    public String getStripPrefix() {
        return stripPrefix;
    }

    public void setStripPrefix(String stripPrefix) {
        this.stripPrefix = stripPrefix;
    }

    public int getTimeoutMillis() {
        return timeoutMillis;
    }

    public void setTimeoutMillis(int timeoutMillis) {
        this.timeoutMillis = timeoutMillis;
    }

    public int getRetries() {
        return retries;
    }

    public void setRetries(int retries) {
        this.retries = retries;
    }

    public boolean isRequireCaller() {
        return requireCaller;
    }

    public void setRequireCaller(boolean requireCaller) {
        this.requireCaller = requireCaller;
    }

    public List<String> getAllowedCallers() {
        return allowedCallers;
    }

    public void setAllowedCallers(List<String> allowedCallers) {
        this.allowedCallers = allowedCallers;
    }

    public RateLimitConfig getRateLimit() {
        return rateLimit;
    }

    public void setRateLimit(RateLimitConfig rateLimit) {
        this.rateLimit = rateLimit;
    }
}
