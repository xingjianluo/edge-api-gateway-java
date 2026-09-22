package com.github.xingjianluo.edgeapigatewayjava.routing;

import com.github.xingjianluo.edgeapigatewayjava.model.GatewayConfig;
import com.github.xingjianluo.edgeapigatewayjava.model.RouteConfig;
import com.github.xingjianluo.edgeapigatewayjava.support.TestConfigs;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 路由匹配单测。每个用例均打印：输入请求特征 -> 判定依据 -> 归一化结论。
 */
class RouterTest {

    private static final Logger log = LoggerFactory.getLogger(RouterTest.class);

    private final Router router = new Router();

    private RequestFeatures features(String method, String path, Map<String, String> headers) {
        return new RequestFeatures(method, path, new LinkedHashMap<>(headers));
    }

    @Test
    @DisplayName("更长路径前缀优先于短前缀，与书写顺序无关")
    void longestPrefixWins() {
        RouteConfig broad = TestConfigs.route("broad", "ANY", "/api", "http://127.0.0.1:18081");
        RouteConfig specific = TestConfigs.route("specific", "ANY", "/api/orders", "http://127.0.0.1:18081");
        GatewayConfig config = TestConfigs.config(specific, broad);

        Router.MatchResult result = router.match(config, features("GET", "/api/orders/123", Map.of()));
        log.info("input=GET /api/orders/123 basis='prefix lengths {} vs {}' verdict={}",
                broad.getPathPrefix().length(), specific.getPathPrefix().length(),
                result.match().route().getId());

        assertEquals(Router.Failure.NONE, result.failure());
        assertEquals("specific", result.match().route().getId());
    }

    @Test
    @DisplayName("头部条件多的规则即使前缀更短也优先（条件越具体越优先）")
    void headerSpecificityBeatsPrefixLength() {
        RouteConfig longPrefix = TestConfigs.route("long-prefix", "ANY", "/api/orders/vip",
                Map.of(), "http://127.0.0.1:18081");
        RouteConfig headerRule = TestConfigs.route("header-rule", "ANY", "/api",
                Map.of("x-tier", "gold"), "http://127.0.0.1:18081");
        GatewayConfig config = TestConfigs.config(longPrefix, headerRule);

        Router.MatchResult result = router.match(config,
                features("GET", "/api/orders/vip/list", Map.of("x-tier", "gold")));
        log.info("input='GET /api/orders/vip/list x-tier=gold' basis='header rule +100 specificity' verdict={}",
                result.match().route().getId());

        assertEquals("header-rule", result.match().route().getId());
    }

    @Test
    @DisplayName("完全同分时按路由 id 字典序决出唯一目标（确定性，不依赖配置顺序）")
    void tieBreakById() {
        RouteConfig a = TestConfigs.route("route-a", "GET", "/x", "http://127.0.0.1:18081");
        RouteConfig b = TestConfigs.route("route-b", "GET", "/x", "http://127.0.0.1:18081");
        GatewayConfig forwardOrder = TestConfigs.config(b, a);

        Router.MatchResult r1 = router.match(forwardOrder, features("GET", "/x", Map.of()));
        Router.MatchResult r2 = router.match(TestConfigs.config(a, b), features("GET", "/x", Map.of()));
        log.info("input=GET /x basis='identical score, lexicographic id' verdict={}/{}",
                r1.match().route().getId(), r2.match().route().getId());

        assertEquals("route-a", r1.match().route().getId());
        assertEquals("route-a", r2.match().route().getId());
    }

    @Test
    @DisplayName("无任何路径匹配 -> ROUTE_NOT_FOUND（404，区别于方法不允许）")
    void noMatchIsNotFound() {
        GatewayConfig config = TestConfigs.config(
                TestConfigs.route("r", "GET", "/api", "http://127.0.0.1:18081"));
        Router.MatchResult result = router.match(config, features("GET", "/other", Map.of()));
        log.info("input=GET /other basis='no prefix matched' verdict={} reason='{}'",
                result.failure(), result.reason());
        assertEquals(Router.Failure.ROUTE_NOT_FOUND, result.failure());
    }

    @Test
    @DisplayName("路径匹配但方法不允许 -> METHOD_NOT_ALLOWED（405）")
    void methodMismatch() {
        GatewayConfig config = TestConfigs.config(
                TestConfigs.route("r", "POST", "/api", "http://127.0.0.1:18081"));
        Router.MatchResult result = router.match(config, features("DELETE", "/api/x", Map.of()));
        log.info("input=DELETE /api/x basis='path matched but only POST allowed' verdict={}",
                result.failure());
        assertEquals(Router.Failure.METHOD_NOT_ALLOWED, result.failure());
    }

    @Test
    @DisplayName("路径+方法匹配但必需头不满足 -> HEADER_CONDITION_FAILED（428）")
    void headerConditionFailed() {
        GatewayConfig config = TestConfigs.config(
                TestConfigs.route("r", "GET", "/api",
                        Map.of("x-api-version", "v2"), "http://127.0.0.1:18081"));
        Router.MatchResult result = router.match(config,
                features("GET", "/api/x", Map.of("x-api-version", "v1")));
        log.info("input='GET /api/x x-api-version=v1' basis='requires x-api-version=v2' verdict={} reason='{}'",
                result.failure(), result.reason());
        assertEquals(Router.Failure.HEADER_CONDITION_FAILED, result.failure());
    }

    @Test
    @DisplayName("regex: 头部条件：Accept 匹配正则的请求命中，否则 428")
    void regexHeaderCondition() {
        RouteConfig route = TestConfigs.route("regex-route", "GET", "/api",
                Map.of("x-env", "regex:dev-\\d+"), "http://127.0.0.1:18081");
        GatewayConfig config = TestConfigs.config(route);

        Router.MatchResult ok = router.match(config,
                features("GET", "/api", Map.of("x-env", "dev-3")));
        Router.MatchResult bad = router.match(config,
                features("GET", "/api", Map.of("x-env", "prod")));
        log.info("input x-env=dev-3 -> verdict={}; input x-env=prod -> verdict={}",
                ok.failure(), bad.failure());

        assertNotNull(ok.match());
        assertEquals(Router.Failure.HEADER_CONDITION_FAILED, bad.failure());
    }

    @Test
    @DisplayName("ANY 方法规则让位给同前缀的显式方法规则")
    void explicitMethodBeatsAny() {
        RouteConfig any = TestConfigs.route("any", "ANY", "/api", "http://127.0.0.1:18081");
        RouteConfig get = TestConfigs.route("get", "GET", "/api", "http://127.0.0.1:18081");
        Router.MatchResult result = router.match(TestConfigs.config(any, get),
                features("GET", "/api", Map.of()));
        log.info("input=GET /api basis='explicit method +1 over ANY' verdict={}",
                result.match().route().getId());
        assertEquals("get", result.match().route().getId());
    }
}
