package com.github.xingjianluo.edgeapigatewayjava.routing;

import com.github.xingjianluo.edgeapigatewayjava.model.RouteConfig;

/** 路由匹配结果，骨架占位。 */
public record RouteMatch(RouteConfig route, String upstreamPath) {
}
