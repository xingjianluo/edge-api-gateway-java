package com.github.xingjianluo.edgeapigatewayjava.config;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 网关配置的不可变快照。热更新时整体替换引用，杜绝半生效状态。
 */
public record GatewayConfig(List<RouteConfig> routes, Map<String, UpstreamConfig> upstreams) {

    public GatewayConfig {
        routes = routes == null ? List.of() : List.copyOf(routes);
        upstreams = upstreams == null ? Map.of() : Map.copyOf(upstreams);
    }

    public UpstreamConfig upstream(String id) {
        return upstreams.get(id);
    }

    public Map<String, RouteConfig> routeIndex() {
        return routes.stream().collect(Collectors.toUnmodifiableMap(RouteConfig::id, Function.identity()));
    }
}
