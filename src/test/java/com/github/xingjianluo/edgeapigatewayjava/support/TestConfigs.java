package com.github.xingjianluo.edgeapigatewayjava.support;

import com.github.xingjianluo.edgeapigatewayjava.model.GatewayConfig;
import com.github.xingjianluo.edgeapigatewayjava.model.RateLimitConfig;
import com.github.xingjianluo.edgeapigatewayjava.model.RouteConfig;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 测试配置工厂：构造一份本机上游、可调限流参数的内存配置。 */
public final class TestConfigs {

    private TestConfigs() {
    }

    public static RouteConfig route(String id, String method, String prefix,
                                    Map<String, String> headers, String upstreamBaseUrl) {
        RouteConfig route = new RouteConfig();
        route.setId(id);
        route.setMethod(method);
        route.setPathPrefix(prefix);
        route.setRequiredHeaders(headers == null ? new LinkedHashMap<>() : new LinkedHashMap<>(headers));
        route.setUpstreamBaseUrl(upstreamBaseUrl);
        route.setTimeoutMillis(1000);
        route.setRetries(0);
        route.setRequireCaller(false);
        return route;
    }

    public static RouteConfig route(String id, String method, String prefix, String upstreamBaseUrl) {
        return route(id, method, prefix, Map.of(), upstreamBaseUrl);
    }

    public static RouteConfig withRateLimit(RouteConfig route, int permits, int windowMillis, long dailyQuota) {
        RateLimitConfig rl = new RateLimitConfig();
        rl.setPermitsPerWindow(permits);
        rl.setWindowMillis(windowMillis);
        rl.setDailyQuota(dailyQuota);
        route.setRateLimit(rl);
        return route;
    }

    public static RouteConfig withCaller(RouteConfig route, boolean require, String... allowed) {
        route.setRequireCaller(require);
        route.setAllowedCallers(new ArrayList<>(List.of(allowed)));
        return route;
    }

    public static RouteConfig withTimeoutAndRetries(RouteConfig route, int timeoutMillis, int retries) {
        route.setTimeoutMillis(timeoutMillis);
        route.setRetries(retries);
        return route;
    }

    public static RouteConfig withStripPrefix(RouteConfig route, String stripPrefix) {
        route.setStripPrefix(stripPrefix);
        return route;
    }

    public static GatewayConfig config(RouteConfig... routes) {
        GatewayConfig config = new GatewayConfig();
        config.setDefaultTimeoutMillis(1000);
        config.setMaxBodyBytes(1024 * 1024);
        config.setDedupTtlMillis(60_000);
        config.setRoutes(new ArrayList<>(List.of(routes)));
        return config;
    }
}
