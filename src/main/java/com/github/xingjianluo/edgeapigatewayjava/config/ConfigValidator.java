package com.github.xingjianluo.edgeapigatewayjava.config;

import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 校验配置是否自洽。
 *
 * 拒绝的典型情况：
 * 1. 路由 id 缺失/重复、路径前缀非法、引用了不存在或已禁用的上游；
 * 2. 两条优先级相同、条件可能同时命中（路径前缀相容、方法集合相容、必需头不互斥）
 *    的路由——这属于自相矛盾的规则，因为无法确定性地选出唯一目标；
 * 3. 上游 baseUrl 非法。
 */
@Component
public class ConfigValidator {

    public List<String> validate(GatewayConfig config) {
        List<String> errors = new ArrayList<>();
        if (config == null) {
            errors.add("配置为空");
            return errors;
        }
        validateUpstreams(config, errors);
        validateRoutes(config, errors);
        validateQuotas(config, errors);
        return errors;
    }

    /**
     * 周期配额策略自洽校验：
     * - id 非空且唯一；
     * - limit 为正；period 只能是 HOUR/DAY/WEEK；timeZone 必须是合法 IANA 时区；
     * - routeIds 非空、引用的路由必须存在、同一条路由最多归属一份策略（否则无法确定从哪份额度扣）。
     */
    private void validateQuotas(GatewayConfig config, List<String> errors) {
        Set<String> quotaIds = new HashSet<>();
        Set<String> validPeriods = Set.of(
                QuotaPolicy.PERIOD_HOUR, QuotaPolicy.PERIOD_DAY, QuotaPolicy.PERIOD_WEEK);
        // 路由 id 重复已在路由校验中报错，这里用容错集合，避免 toUnmodifiableMap 直接抛异常中断校验
        Set<String> existingRouteIds = new HashSet<>();
        for (RouteConfig r : config.routes()) {
            existingRouteIds.add(r.id());
        }
        // routeId -> 已归属的策略 id
        Map<String, String> routeOwner = new java.util.HashMap<>();

        for (int i = 0; i < config.quotaPolicies().size(); i++) {
            QuotaPolicy q = config.quotaPolicies().get(i);
            String label = isBlank(q.id()) ? ("第" + i + "个配额策略") : "配额策略 " + q.id();
            if (isBlank(q.id())) {
                errors.add(label + " 缺少 id");
            } else if (!quotaIds.add(q.id())) {
                errors.add("配额策略 id 重复: " + q.id());
            }
            if (q.limit() <= 0) {
                errors.add(label + " 的 limit 必须为正数: " + q.limit());
            }
            if (q.period() == null || !validPeriods.contains(q.period())) {
                errors.add(label + " 的 period 只能是 HOUR/DAY/WEEK: " + q.period());
            }
            if (isBlank(q.timeZone())) {
                errors.add(label + " 缺少 timeZone");
            } else {
                try {
                    ZoneId.of(q.timeZone());
                } catch (Exception e) {
                    errors.add(label + " 的 timeZone 不是合法 IANA 时区: " + q.timeZone());
                }
            }
            if (q.routeIds().isEmpty()) {
                errors.add(label + " 的 routeIds 不能为空（配额至少覆盖一条路由）");
            }
            Set<String> withinPolicy = new HashSet<>();
            for (String routeId : q.routeIds()) {
                if (isBlank(routeId)) {
                    errors.add(label + " 的 routeIds 存在空值");
                    continue;
                }
                if (!withinPolicy.add(routeId)) {
                    errors.add(label + " 的 routeIds 内路由重复: " + routeId);
                }
                if (!existingRouteIds.contains(routeId)) {
                    errors.add(label + " 引用了不存在的路由: " + routeId);
                }
                String previous = routeOwner.putIfAbsent(routeId, q.id());
                if (previous != null) {
                    errors.add(String.format(
                            "路由 %s 同时归属配额策略 %s 与 %s，一条路由最多属于一份配额",
                            routeId, previous, q.id()));
                }
            }
        }
    }

    private void validateUpstreams(GatewayConfig config, List<String> errors) {
        Set<String> ids = new HashSet<>();
        for (UpstreamConfig up : config.upstreams().values()) {
            if (isBlank(up.id())) {
                errors.add("存在缺少 id 的上游定义");
                continue;
            }
            if (!ids.add(up.id())) {
                errors.add("上游 id 重复: " + up.id());
            }
            if (isBlank(up.baseUrl())) {
                errors.add("上游 " + up.id() + " 缺少 baseUrl");
            } else {
                try {
                    URI uri = URI.create(up.baseUrl());
                    if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) {
                        errors.add("上游 " + up.id() + " 的 baseUrl 必须是 http/https: " + up.baseUrl());
                    }
                    if (uri.getHost() == null) {
                        errors.add("上游 " + up.id() + " 的 baseUrl 缺少主机: " + up.baseUrl());
                    }
                } catch (IllegalArgumentException e) {
                    errors.add("上游 " + up.id() + " 的 baseUrl 非法: " + up.baseUrl());
                }
            }
        }
    }

    private void validateRoutes(GatewayConfig config, List<String> errors) {
        Set<String> ids = new HashSet<>();
        List<RouteConfig> routes = config.routes();
        for (int i = 0; i < routes.size(); i++) {
            RouteConfig r = routes.get(i);
            String label = isBlank(r.id()) ? ("第" + i + "条路由") : r.id();
            if (isBlank(r.id())) {
                errors.add(label + " 缺少 id");
            } else if (!ids.add(r.id())) {
                errors.add("路由 id 重复: " + r.id());
            }
            if (isBlank(r.pathPrefix()) || !r.pathPrefix().startsWith("/")) {
                errors.add(label + " 的 pathPrefix 必须是以 / 开头的非空字符串: " + r.pathPrefix());
            }
            if (isBlank(r.upstream())) {
                errors.add(label + " 缺少 upstream");
            } else {
                UpstreamConfig up = config.upstreams().get(r.upstream());
                if (up == null) {
                    errors.add(label + " 引用了不存在的上游: " + r.upstream());
                }
            }
            if (r.priority() < 0) {
                errors.add(label + " 的 priority 不能为负: " + r.priority());
            }
            if (r.timeoutMillis() <= 0) {
                errors.add(label + " 的 timeoutMillis 必须为正数: " + r.timeoutMillis());
            }
            if (r.maxRetries() < 0) {
                errors.add(label + " 的 maxRetries 不能为负: " + r.maxRetries());
            }
            if (r.globalRatePerSecond() < 0 || r.callerRatePerSecond() < 0) {
                errors.add(label + " 的限流速率不能为负");
            }
            for (String m : r.methods()) {
                if (isBlank(m)) {
                    errors.add(label + " 存在空 HTTP 方法");
                }
            }
        }
        // 同优先级且条件可能重叠 => 确定性匹配会产生歧义
        for (int i = 0; i < routes.size(); i++) {
            for (int j = i + 1; j < routes.size(); j++) {
                RouteConfig a = routes.get(i);
                RouteConfig b = routes.get(j);
                if (a.priority() == b.priority() && potentiallyOverlap(a, b)) {
                    errors.add(String.format(
                            "路由 %s 与 %s 优先级相同(%d)且条件可能同时命中，无法确定唯一目标",
                            a.id(), b.id(), a.priority()));
                }
            }
        }
    }

    /**
     * 两条规则是否可能被同一个请求同时命中：
     * 路径前缀相容、方法集合相容、必需头不互斥。
     */
    static boolean potentiallyOverlap(RouteConfig a, RouteConfig b) {
        if (!prefixCompatible(a.pathPrefix(), b.pathPrefix())) {
            return false;
        }
        if (!methodsCompatible(a.methods(), b.methods())) {
            return false;
        }
        return headersCompatible(a.requiredHeaders(), b.requiredHeaders());
    }

    private static boolean prefixCompatible(String p1, String p2) {
        if (p1 == null || p2 == null) {
            return false;
        }
        return p1.startsWith(p2) || p2.startsWith(p1);
    }

    private static boolean methodsCompatible(List<String> m1, List<String> m2) {
        if (m1.isEmpty() || m2.isEmpty()) {
            return true;
        }
        Set<String> s = new HashSet<>();
        for (String m : m1) {
            s.add(m.toUpperCase(Locale.ROOT));
        }
        for (String m : m2) {
            if (s.contains(m.toUpperCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 仅当两条规则对同一个头名要求不同的具体值时才互斥；
     * 一方只要求存在、另一方要求某值，或要求的头名不同，都算相容。
     */
    private static boolean headersCompatible(Map<String, String> h1, Map<String, String> h2) {
        for (Map.Entry<String, String> e : h1.entrySet()) {
            String other = lookupIgnoreCase(h2, e.getKey());
            if (other != null && e.getValue() != null && !other.equalsIgnoreCase(e.getValue())) {
                return false;
            }
        }
        return true;
    }

    private static String lookupIgnoreCase(Map<String, String> map, String key) {
        for (Map.Entry<String, String> e : map.entrySet()) {
            if (e.getKey().equalsIgnoreCase(key)) {
                return e.getValue();
            }
        }
        return null;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
