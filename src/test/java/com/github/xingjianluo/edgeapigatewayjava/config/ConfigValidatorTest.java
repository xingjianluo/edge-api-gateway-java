package com.github.xingjianluo.edgeapigatewayjava.config;

import com.github.xingjianluo.edgeapigatewayjava.error.ErrorCode;
import com.github.xingjianluo.edgeapigatewayjava.error.GatewayException;
import com.github.xingjianluo.edgeapigatewayjava.model.GatewayConfig;
import com.github.xingjianluo.edgeapigatewayjava.model.RouteConfig;
import com.github.xingjianluo.edgeapigatewayjava.support.TestConfigs;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 配置校验单测：非法配置必须以 CONFIG_INVALID 拒绝且原因可区分。 */
class ConfigValidatorTest {

    private static final Logger log = LoggerFactory.getLogger(ConfigValidatorTest.class);

    private final ConfigValidator validator = new ConfigValidator();

    private void assertInvalid(GatewayConfig config, String reasonHint) {
        GatewayException ex = assertThrows(GatewayException.class, () -> validator.validate(config));
        log.info("input={} basis=validation verdict={} reason='{}'",
                reasonHint, ex.errorCode(), ex.getMessage());
        assertEquals(ErrorCode.CONFIG_INVALID, ex.errorCode());
        assertTrue(ex.getMessage().contains(reasonHint),
                "reason should mention '" + reasonHint + "', got: " + ex.getMessage());
    }

    @Test
    @DisplayName("相同匹配签名（同方法+前缀+必需头集合）即使上游不同也是矛盾规则，拒绝")
    void contradictorySignaturesRejected() {
        RouteConfig r1 = TestConfigs.route("dup-a", "GET", "/api", Map.of(), "http://127.0.0.1:18081");
        RouteConfig r2 = TestConfigs.route("dup-b", "GET", "/api", Map.of(), "http://127.0.0.1:18082");
        GatewayConfig config = TestConfigs.config(r1, r2);
        assertInvalid(config, "contradictory/duplicate match signature");
    }

    @Test
    @DisplayName("必需头集合不同则签名不同，属于合法的更具体规则，通过")
    void differentHeaderSetsAreValid() {
        RouteConfig r1 = TestConfigs.route("base", "GET", "/api", Map.of(), "http://127.0.0.1:18081");
        RouteConfig r2 = TestConfigs.route("with-v2", "GET", "/api",
                Map.of("x-api-version", "v2"), "http://127.0.0.1:18081");
        GatewayConfig config = TestConfigs.config(r1, r2);
        assertDoesNotThrow(() -> validator.validate(config));
        log.info("input='same prefix, different header sets' basis='distinct signatures' verdict=VALID");
    }

    @Test
    @DisplayName("重复路由 id 拒绝")
    void duplicateIdRejected() {
        RouteConfig r1 = TestConfigs.route("same-id", "GET", "/a", "http://127.0.0.1:18081");
        RouteConfig r2 = TestConfigs.route("same-id", "GET", "/b", "http://127.0.0.1:18081");
        assertInvalid(TestConfigs.config(r1, r2), "duplicate route id");
    }

    @Test
    @DisplayName("上游指向非 loopback 主机拒绝（不依赖外部网络）")
    void nonLoopbackUpstreamRejected() {
        RouteConfig route = TestConfigs.route("ext", "GET", "/a", "http://example.com:80");
        assertInvalid(TestConfigs.config(route), "loopback");
    }

    @Test
    @DisplayName("非法正则头部条件拒绝")
    void badRegexRejected() {
        RouteConfig route = TestConfigs.route("bad", "GET", "/a",
                Map.of("x-env", "regex:[unclosed"), "http://127.0.0.1:18081");
        assertInvalid(TestConfigs.config(route), "bad regex");
    }

    @Test
    @DisplayName("限流 permits=0 拒绝")
    void zeroPermitsRejected() {
        RouteConfig route = TestConfigs.withRateLimit(
                TestConfigs.route("rl", "GET", "/a", "http://127.0.0.1:18081"),
                0, 1000, -1);
        assertInvalid(TestConfigs.config(route), "permitsPerWindow");
    }

    @Test
    @DisplayName("requireCaller=false 却配置白名单：语义矛盾，拒绝")
    void allowlistWithoutAuthRejected() {
        RouteConfig route = TestConfigs.withCaller(
                TestConfigs.route("confused", "GET", "/a", "http://127.0.0.1:18081"),
                false, "someone");
        assertInvalid(TestConfigs.config(route), "allowedCallers");
    }

    @Test
    @DisplayName("非法 HTTP 方法拒绝")
    void badMethodRejected() {
        RouteConfig route = TestConfigs.route("m", "BREW", "/a", "http://127.0.0.1:18081");
        assertInvalid(TestConfigs.config(route), "unsupported method");
    }
}
