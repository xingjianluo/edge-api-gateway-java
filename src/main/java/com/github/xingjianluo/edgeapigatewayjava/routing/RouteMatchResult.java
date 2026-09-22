package com.github.xingjianluo.edgeapigatewayjava.routing;

import com.github.xingjianluo.edgeapigatewayjava.config.RouteConfig;

import java.util.List;

/**
 * 路由判定结果：要么唯一命中，要么给出可区分的失败原因。
 */
public record RouteMatchResult(RouteConfig route, List<String> matchedBy, String failureReason) {

    public static RouteMatchResult hit(RouteConfig route, List<String> matchedBy) {
        return new RouteMatchResult(route, List.copyOf(matchedBy), null);
    }

    public static RouteMatchResult miss(String reason) {
        return new RouteMatchResult(null, List.of(), reason);
    }

    public boolean hit() {
        return route != null;
    }
}
