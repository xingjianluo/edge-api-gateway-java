package com.github.xingjianluo.edgeapigatewayjava.config;

import com.github.xingjianluo.edgeapigatewayjava.quota.QuotaPolicy;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 网关配置的不可变快照。热更新时整体替换引用，杜绝半生效状态。
 *
 * @param routes    路由规则
 * @param upstreams 上游定义
 * @param quotas    周期配额策略：id -> 策略；一条路由可通过 {@link RouteConfig#quotaId()}
 *                  归属到某份配额，多条路由引用同一 id 即共享同一份按调用方计的周期额度
 */
public record GatewayConfig(List<RouteConfig> routes,
                            Map<String, UpstreamConfig> upstreams,
                            Map<String, QuotaPolicy> quotas) {

    public GatewayConfig {
        routes = routes == null ? List.of() : List.copyOf(routes);
        upstreams = upstreams == null ? Map.of() : Map.copyOf(upstreams);
        quotas = quotas == null ? Map.of() : Map.copyOf(quotas);
    }

    /** 不含周期配额的兼容构造器（等价于 quotas 为空）。 */
    public GatewayConfig(List<RouteConfig> routes, Map<String, UpstreamConfig> upstreams) {
        this(routes, upstreams, Map.of());
    }

    public UpstreamConfig upstream(String id) {
        return upstreams.get(id);
    }

    /** 按 id 查询周期配额策略；不存在返回 null（该路由即不受周期配额约束）。 */
    public QuotaPolicy quota(String id) {
        return id == null ? null : quotas.get(id);
    }

    public Map<String, RouteConfig> routeIndex() {
        return routes.stream().collect(Collectors.toUnmodifiableMap(RouteConfig::id, Function.identity()));
    }
}
