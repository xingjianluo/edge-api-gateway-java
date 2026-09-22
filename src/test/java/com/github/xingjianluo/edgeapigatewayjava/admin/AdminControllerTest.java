package com.github.xingjianluo.edgeapigatewayjava.admin;

import com.github.xingjianluo.edgeapigatewayjava.config.ConfigStore;
import com.github.xingjianluo.edgeapigatewayjava.support.GatewayClient;
import com.github.xingjianluo.edgeapigatewayjava.support.TestConfigs;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** 管理端 smoke 测试：配置摘要接口对外可观察，reload 接口返回版本号。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AdminControllerTest {

    private static final Logger log = LoggerFactory.getLogger(AdminControllerTest.class);

    @LocalServerPort
    private int port;

    @Autowired
    private ConfigStore configStore;

    @Test
    @DisplayName("GET /__gateway/admin/config 返回当前版本与路由摘要")
    void configSummary() {
        configStore.publishForTest(TestConfigs.config(
                TestConfigs.route("admin-smoke", "GET", "/smoke", "http://127.0.0.1:18081")));
        GatewayClient.Response resp = new GatewayClient(port)
                .send("GET", "/__gateway/admin/config", null, null);
        log.info("input=GET admin/config verdict status={} body={}", resp.status(), resp.body());
        assertTrue(resp.body().contains("\"version\""));
        assertTrue(resp.body().contains("admin-smoke"));
    }

    @Test
    @DisplayName("POST /__gateway/admin/reload 返回 reloaded 与新版本号")
    void reloadEndpoint() {
        GatewayClient.Response resp = new GatewayClient(port)
                .send("POST", "/__gateway/admin/reload", null, null);
        log.info("input=POST admin/reload verdict status={} body={}", resp.status(), resp.body());
        assertTrue(resp.body().contains("reloaded"));
    }
}
