package com.github.xingjianluo.edgeapigatewayjava.tests;

import com.github.xingjianluo.edgeapigatewayjava.config.ConfigValidator;
import com.github.xingjianluo.edgeapigatewayjava.config.GatewayConfig;
import com.github.xingjianluo.edgeapigatewayjava.config.RouteConfig;
import com.github.xingjianluo.edgeapigatewayjava.config.UpstreamConfig;
import com.github.xingjianluo.edgeapigatewayjava.quota.QuotaPolicy;
import com.github.xingjianluo.edgeapigatewayjava.quota.QuotaResetPeriod;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 配置自洽性校验单测：重复 id、缺失上游、同优先级可重叠规则等必须被拒绝。
 */
class ConfigValidatorTest {

    private static final Logger log = LoggerFactory.getLogger(ConfigValidatorTest.class);
    private final ConfigValidator validator = new ConfigValidator();

    private RouteConfig route(String id, String prefix, int priority, String upstream) {
        return new RouteConfig(id, prefix, List.of(), Map.of(), upstream,
                null, priority, 1000, 0, 0, 0, List.of());
    }

    @Test
    void 合法配置通过校验() {
        GatewayConfig cfg = new GatewayConfig(
                List.of(route("a", "/a", 10, "u1"), route("b", "/b", 10, "u1")),
                Map.of("u1", new UpstreamConfig("u1", "http://127.0.0.1:9", true)));
        List<String> errors = validator.validate(cfg);

        log.info("[用例:合法配置] 规则数=2 路径=/a,/b -> 错误={}", errors);

        assertTrue(errors.isEmpty(), errors::toString);
    }

    @Test
    void 同优先级且前缀相容的规则被判定为矛盾() {
        GatewayConfig cfg = new GatewayConfig(
                List.of(route("a", "/api", 10, "u1"), route("b", "/api/v2", 10, "u1")),
                Map.of("u1", new UpstreamConfig("u1", "http://127.0.0.1:9", true)));
        List<String> errors = validator.validate(cfg);

        log.info("[用例:同优先级重叠] 输入 a=/api,b=/api/v2,priority=10 -> 错误={}", errors);

        assertTrue(errors.stream().anyMatch(e -> e.contains("优先级相同")), errors::toString);
    }

    @Test
    void 不同优先级的重叠规则允许共存() {
        GatewayConfig cfg = new GatewayConfig(
                List.of(route("a", "/api", 10, "u1"), route("b", "/api/v2", 20, "u1")),
                Map.of("u1", new UpstreamConfig("u1", "http://127.0.0.1:9", true)));
        List<String> errors = validator.validate(cfg);

        log.info("[用例:不同优先级重叠] priority=10/20 -> 错误={}", errors);

        assertFalse(errors.stream().anyMatch(e -> e.contains("优先级相同")));
    }

    @Test
    void 同优先级但方法互斥的规则允许共存() {
        RouteConfig get = new RouteConfig("g", "/api", List.of("GET"), Map.of(),
                "u1", null, 10, 1000, 0, 0, 0, List.of());
        RouteConfig post = new RouteConfig("p", "/api", List.of("POST"), Map.of(),
                "u1", null, 10, 1000, 0, 0, 0, List.of());
        GatewayConfig cfg = new GatewayConfig(List.of(get, post),
                Map.of("u1", new UpstreamConfig("u1", "http://127.0.0.1:9", true)));
        List<String> errors = validator.validate(cfg);

        log.info("[用例:方法互斥] GET vs POST 同前缀同优先级 -> 错误={}", errors);

        assertFalse(errors.stream().anyMatch(e -> e.contains("优先级相同")));
    }

    @Test
    void 必需头互斥的同优先级规则允许共存() {
        RouteConfig va = new RouteConfig("va", "/api", List.of(), Map.of("X-Tenant", "a"),
                "u1", null, 10, 1000, 0, 0, 0, List.of());
        RouteConfig vb = new RouteConfig("vb", "/api", List.of(), Map.of("X-Tenant", "b"),
                "u1", null, 10, 1000, 0, 0, 0, List.of());
        GatewayConfig cfg = new GatewayConfig(List.of(va, vb),
                Map.of("u1", new UpstreamConfig("u1", "http://127.0.0.1:9", true)));
        List<String> errors = validator.validate(cfg);

        log.info("[用例:头值互斥] X-Tenant=a vs b -> 错误={}", errors);

        assertFalse(errors.stream().anyMatch(e -> e.contains("优先级相同")));
    }

    @Test
    void 引用不存在的上游被拒绝() {
        GatewayConfig cfg = new GatewayConfig(
                List.of(route("a", "/a", 10, "ghost")), Map.of());
        List<String> errors = validator.validate(cfg);

        log.info("[用例:幽灵上游] upstream=ghost -> 错误={}", errors);

        assertTrue(errors.stream().anyMatch(e -> e.contains("不存在的上游")));
    }

    @Test
    void 路由id重复被拒绝() {
        GatewayConfig cfg = new GatewayConfig(
                List.of(route("dup", "/a", 10, "u1"), route("dup", "/b", 20, "u1")),
                Map.of("u1", new UpstreamConfig("u1", "http://127.0.0.1:9", true)));
        List<String> errors = validator.validate(cfg);

        log.info("[用例:重复id] 输入=dup,dup -> 错误={}", errors);

        assertTrue(errors.stream().anyMatch(e -> e.contains("路由 id 重复")));
    }

    @Test
    void 非法baseUrl被拒绝() {
        GatewayConfig cfg = new GatewayConfig(List.of(),
                Map.of("bad", new UpstreamConfig("bad", "ftp://nohost", true)));
        List<String> errors = validator.validate(cfg);

        log.info("[用例:非法URL] baseUrl=ftp://nohost -> 错误={}", errors);

        assertTrue(errors.stream().anyMatch(e -> e.contains("http/https")));
    }

    @Test
    void 合法的周期配额配置通过校验() {
        QuotaPolicy q = new QuotaPolicy("budget", 100, QuotaResetPeriod.WEEK, "Asia/Shanghai");
        RouteConfig r = new RouteConfig("a", "/a", List.of(), Map.of(), "u1",
                null, 10, 1000, 0, 0, 0, List.of(), "budget");
        GatewayConfig cfg = new GatewayConfig(
                List.of(r),
                Map.of("u1", new UpstreamConfig("u1", "http://127.0.0.1:9", true)),
                Map.of("budget", q));
        List<String> errors = validator.validate(cfg);
        log.info("[用例:合法配额] quotaId=budget, limit=100/WEEK/Asia/Shanghai -> 错误={}", errors);
        assertTrue(errors.isEmpty(), errors::toString);
    }

    @Test
    void 路由引用不存在的配额被拒绝() {
        RouteConfig r = new RouteConfig("a", "/a", List.of(), Map.of(), "u1",
                null, 10, 1000, 0, 0, 0, List.of(), "ghost-budget");
        GatewayConfig cfg = new GatewayConfig(
                List.of(r),
                Map.of("u1", new UpstreamConfig("u1", "http://127.0.0.1:9", true)),
                Map.of());
        List<String> errors = validator.validate(cfg);
        log.info("[用例:幽灵配额引用] quotaId=ghost-budget 但无此策略 -> 错误={}", errors);
        assertTrue(errors.stream().anyMatch(e -> e.contains("不存在的周期配额")));
    }

    @Test
    void 配额重复id与非正额度与非法时区被拒绝() {
        QuotaPolicy dupA = new QuotaPolicy("dup", 0, QuotaResetPeriod.DAY, "UTC");
        QuotaPolicy dupB = new QuotaPolicy("dup", 5, QuotaResetPeriod.HOUR, "Not/AZone");
        GatewayConfig cfg = new GatewayConfig(List.of(),
                Map.of("u1", new UpstreamConfig("u1", "http://127.0.0.1:9", true)),
                Map.of("dup", dupB));
        // Map.of 无法保留重复 key，因此分别断言两种非法：非正额度 + 非法时区
        List<String> errors = validator.validate(cfg);
        log.info("[用例:非法配额] 非法时区+构造另测 limit=0 -> 错误={}", errors);
        assertTrue(errors.stream().anyMatch(e -> e.contains("timeZone 非法")));

        GatewayConfig zeroLimit = new GatewayConfig(List.of(),
                Map.of("u1", new UpstreamConfig("u1", "http://127.0.0.1:9", true)),
                Map.of("z", dupA));
        List<String> zeroErrors = validator.validate(zeroLimit);
        assertTrue(zeroErrors.stream().anyMatch(e -> e.contains("limit 必须为正数")));
    }
}
