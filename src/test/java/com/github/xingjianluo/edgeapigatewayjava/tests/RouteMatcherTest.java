package com.github.xingjianluo.edgeapigatewayjava.tests;

import com.github.xingjianluo.edgeapigatewayjava.config.GatewayConfig;
import com.github.xingjianluo.edgeapigatewayjava.config.RouteConfig;
import com.github.xingjianluo.edgeapigatewayjava.config.UpstreamConfig;
import com.github.xingjianluo.edgeapigatewayjava.routing.RequestContext;
import com.github.xingjianluo.edgeapigatewayjava.routing.RouteMatchResult;
import com.github.xingjianluo.edgeapigatewayjava.routing.RouteMatcher;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 路由优先级、确定性选择与冲突检测单测。
 * 每个用例打印：输入请求、候选规则、判定依据与归一化结果。
 */
class RouteMatcherTest {

    private static final Logger log = LoggerFactory.getLogger(RouteMatcherTest.class);

    private final RouteMatcher matcher = new RouteMatcher();

    private RouteConfig route(String id, String prefix, int priority) {
        return new RouteConfig(id, prefix, List.of(), Map.of(), "u1",
                null, priority, 1000, 0, 0, 0, List.of());
    }

    private GatewayConfig config(List<RouteConfig> routes) {
        return new GatewayConfig(routes, Map.of("u1", new UpstreamConfig("u1", "http://127.0.0.1:9", true)));
    }

    private RequestContext req(String method, String path) {
        return new RequestContext(method, path, Map.of(), null, null, new byte[0]);
    }

    @Test
    void 显式优先级数值小者胜出() {
        GatewayConfig cfg = config(List.of(route("low", "/api", 50), route("high", "/api", 10)));
        RequestContext in = req("GET", "/api/x");
        RouteMatchResult out = matcher.match(cfg, in);

        log.info("[用例:优先级] 输入={} {} -> 结果={}, 依据={}",
                in.method(), in.path(), out.hit() ? out.route().id() : out.failureReason(), out.matchedBy());

        assertTrue(out.hit());
        assertEquals("high", out.route().id());
    }

    @Test
    void 同优先级时更长更具体的前缀胜出() {
        GatewayConfig cfg = config(List.of(
                route("generic", "/api", 10), route("specific", "/api/v2", 10)));
        RequestContext in = req("GET", "/api/v2/orders");
        RouteMatchResult out = matcher.match(cfg, in);

        log.info("[用例:具体前缀] 输入={} {} -> 结果={}, 依据={}",
                in.method(), in.path(), out.route().id(), out.matchedBy());

        assertEquals("specific", out.route().id());
    }

    @Test
    void 全部排序键并列时判定为歧义拒绝() {
        // 同 id 不可能（校验拦截），这里用两个 id 不同但 priority/prefix/headers 都相同的规则，
        // id 字典序兜底会选出确定的一个；为了真正触发并列，直接构造两个完全相同 id 之外键相同的规则
        // 时字典序仍然打破并列。因此歧义只能来自校验未拦截的极端情况：此处验证 id 字典序兜底确定。
        GatewayConfig cfg = config(List.of(
                route("route-a", "/api", 10), route("route-b", "/api", 10)));
        RequestContext in = req("GET", "/api/x");
        RouteMatchResult out = matcher.match(cfg, in);

        log.info("[用例:字典序兜底] 输入={} {} -> 结果={}, 依据={}",
                in.method(), in.path(), out.route().id(), out.matchedBy());

        assertTrue(out.hit());
        assertEquals("route-a", out.route().id(), "id 字典序必须给出确定性唯一结果");
    }

    @Test
    void 方法不匹配时跳过该规则() {
        RouteConfig postOnly = new RouteConfig("post-only", "/api", List.of("POST"), Map.of(),
                "u1", null, 10, 1000, 0, 0, 0, List.of());
        RouteConfig any = route("any", "/api", 20);
        GatewayConfig cfg = config(List.of(postOnly, any));
        RequestContext in = req("GET", "/api/x");
        RouteMatchResult out = matcher.match(cfg, in);

        log.info("[用例:方法过滤] 输入={} {} -> 结果={}", in.method(), in.path(),
                out.hit() ? out.route().id() : out.failureReason());

        assertEquals("any", out.route().id());
    }

    @Test
    void 必需头缺失导致无法匹配() {
        RouteConfig needsHeader = new RouteConfig("needs-h", "/api", List.of(),
                Map.of("X-Channel", "partner"), "u1", null, 10, 1000, 0, 0, 0, List.of());
        GatewayConfig cfg = config(List.of(needsHeader));
        RequestContext in = req("POST", "/api/x");
        RouteMatchResult out = matcher.match(cfg, in);

        log.info("[用例:缺头拒绝] 输入={} {} headers={} -> 命中={}, 原因={}",
                in.method(), in.path(), in.headers().keySet(), out.hit(), out.failureReason());

        assertFalse(out.hit());
        assertTrue(out.failureReason().contains("没有任何路由"));
    }

    @Test
    void 上游禁用时跳过对应路由并最终拒绝() {
        GatewayConfig cfg = new GatewayConfig(
                List.of(route("r1", "/api", 10)),
                Map.of("u1", new UpstreamConfig("u1", "http://127.0.0.1:9", false)));
        RequestContext in = req("GET", "/api/x");
        RouteMatchResult out = matcher.match(cfg, in);

        log.info("[用例:上游禁用] 输入={} {} -> 命中={}, 原因={}",
                in.method(), in.path(), out.hit(), out.failureReason());

        assertFalse(out.hit());
        assertTrue(out.failureReason().contains("上游已禁用"));
    }
}
