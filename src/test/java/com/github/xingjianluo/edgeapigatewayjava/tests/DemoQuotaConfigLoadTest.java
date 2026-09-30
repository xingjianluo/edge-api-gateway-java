package com.github.xingjianluo.edgeapigatewayjava.tests;

import com.github.xingjianluo.edgeapigatewayjava.config.ConfigLoader;
import com.github.xingjianluo.edgeapigatewayjava.config.ConfigValidator;
import com.github.xingjianluo.edgeapigatewayjava.config.GatewayConfig;
import com.github.xingjianluo.edgeapigatewayjava.config.QuotaPolicy;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 校验随包发布的演示配置 gateway-routes-demo.json：
 * 能被解析+校验通过，共享配额归属被正确回填到覆盖路由。
 */
class DemoQuotaConfigLoadTest {

    private static final Logger log = LoggerFactory.getLogger(DemoQuotaConfigLoadTest.class);

    @Test
    void 演示配置中的共享配额被正确解析并回填路由归属() throws Exception {
        String json = new String(new ClassPathResource("gateway-routes-demo.json")
                .getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        GatewayConfig cfg = new ConfigLoader().load(json);
        var errors = new ConfigValidator().validate(cfg);

        log.info("[用例:演示配额配置] 校验错误={}, 策略数={}", errors, cfg.quotaPolicies().size());
        assertTrue(errors.isEmpty(), errors::toString);
        assertEquals(1, cfg.quotaPolicies().size());

        QuotaPolicy q = cfg.quotaPolicies().get(0);
        assertEquals("partner-weekly-budget", q.id());
        assertEquals(5000, q.limit());
        assertEquals("WEEK", q.period());
        assertEquals("Asia/Shanghai", q.timeZone());
        assertTrue(q.routeIds().contains("v1-api") && q.routeIds().contains("v2-api"));

        cfg.routes().forEach(r -> log.info("[用例:演示配额配置] 路由 {} -> quotaPolicyId={}",
                r.id(), r.quotaPolicyId()));
        assertEquals("partner-weekly-budget",
                cfg.routeIndex().get("v1-api").quotaPolicyId());
        assertEquals("partner-weekly-budget",
                cfg.routeIndex().get("v2-api").quotaPolicyId());
        assertEquals(null, cfg.routeIndex().get("health-route").quotaPolicyId(),
                "未被任何配额覆盖的路由 quotaPolicyId 必须为 null");
    }
}
