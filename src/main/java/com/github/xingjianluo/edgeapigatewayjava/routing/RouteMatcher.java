package com.github.xingjianluo.edgeapigatewayjava.routing;

import com.github.xingjianluo.edgeapigatewayjava.config.GatewayConfig;
import com.github.xingjianluo.edgeapigatewayjava.config.RouteConfig;
import com.github.xingjianluo.edgeapigatewayjava.config.UpstreamConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 按确定性优先级选出唯一路由。
 *
 * 排序键（依次比较）：
 * 1. priority 数值小者优先（显式优先级）；
 * 2. 路径前缀长者优先（更具体的规则优先）；
 * 3. 必需头数量多者优先（约束更强的规则优先）；
 * 4. 路由 id 字典序（最终兜底，保证结果确定）。
 *
 * 若最终仍出现并列（理论上已被配置校验拦截），返回 ROUTE_AMBIGUOUS。
 */
@Component
public class RouteMatcher {

    private static final Logger log = LoggerFactory.getLogger(RouteMatcher.class);

    private static final Comparator<RouteConfig> ORDER = Comparator
            .comparingInt(RouteConfig::priority)
            .thenComparing((RouteConfig r) -> r.pathPrefix().length(), Comparator.reverseOrder())
            .thenComparing((RouteConfig r) -> r.requiredHeaders().size(), Comparator.reverseOrder())
            .thenComparing(RouteConfig::id);

    public RouteMatchResult match(GatewayConfig config, RequestContext request) {
        List<Scored> hits = new ArrayList<>();
        List<String> considered = new ArrayList<>();

        for (RouteConfig route : config.routes()) {
            String mismatch = firstMismatch(route, request);
            if (mismatch == null) {
                UpstreamConfig up = config.upstream(route.upstream());
                if (up == null) {
                    considered.add(route.id() + "(上游定义缺失，跳过)");
                    continue;
                }
                if (!up.enabled()) {
                    considered.add(route.id() + "(上游已禁用，跳过)");
                    continue;
                }
                hits.add(new Scored(route, explain(route)));
            } else {
                considered.add(route.id() + "(" + mismatch + ")");
            }
        }

        log.info("[route] 请求 {} {} caller={} 候选判定: {}",
                request.method(), request.path(), request.callerId(), considered);

        if (hits.isEmpty()) {
            String reason = "没有任何路由同时满足路径/方法/请求头条件（或命中路由的上游均已禁用）: " + considered;
            log.info("[route] 判定=ROUTE_NOT_FOUND, 依据={}", reason);
            return RouteMatchResult.miss(reason);
        }
        hits.sort(Comparator.comparing(s -> s.route, ORDER));
        Scored best = hits.get(0);
        if (hits.size() > 1) {
            Scored second = hits.get(1);
            if (ORDER.compare(best.route, second.route) == 0) {
                String reason = "路由 " + best.route.id() + " 与 " + second.route.id() + " 在全部排序键上并列";
                log.warn("[route] 判定=ROUTE_AMBIGUOUS, 依据={}", reason);
                return RouteMatchResult.miss("AMBIGUOUS:" + reason);
            }
        }
        log.info("[route] 判定=HIT route={}, 依据={}", best.route.id(), best.matchedBy);
        return RouteMatchResult.hit(best.route, best.matchedBy);
    }

    private String firstMismatch(RouteConfig route, RequestContext request) {
        if (!request.path().startsWith(route.pathPrefix())) {
            return "路径前缀不匹配: " + route.pathPrefix();
        }
        if (!route.methods().isEmpty()) {
            boolean methodOk = route.methods().stream()
                    .anyMatch(m -> m.equalsIgnoreCase(request.method()));
            if (!methodOk) {
                return "方法 " + request.method() + " 不在允许集合 " + route.methods();
            }
        }
        for (Map.Entry<String, String> need : route.requiredHeaders().entrySet()) {
            String actual = request.header(need.getKey());
            if (actual == null) {
                return "缺少必需头 " + need.getKey();
            }
            if (need.getValue() != null && !need.getValue().equalsIgnoreCase(actual.trim())) {
                return "头 " + need.getKey() + "=" + actual + " 不满足期望值 " + need.getValue();
            }
        }
        return null;
    }

    private List<String> explain(RouteConfig route) {
        List<String> basis = new ArrayList<>();
        basis.add("priority=" + route.priority());
        basis.add("pathPrefix=" + route.pathPrefix() + "(长度" + route.pathPrefix().length() + ")");
        basis.add("requiredHeaders=" + route.requiredHeaders().size() + "个");
        basis.add("id=" + route.id());
        return basis;
    }

    private record Scored(RouteConfig route, List<String> matchedBy) {
    }
}
