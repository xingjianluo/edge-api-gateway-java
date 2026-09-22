package com.github.xingjianluo.edgeapigatewayjava.proxy;

import com.github.xingjianluo.edgeapigatewayjava.config.ConfigStore;
import com.github.xingjianluo.edgeapigatewayjava.ratelimit.RateLimitService;
import com.github.xingjianluo.edgeapigatewayjava.model.RouteConfig;
import com.github.xingjianluo.edgeapigatewayjava.support.FakeUpstreams;
import com.github.xingjianluo.edgeapigatewayjava.support.GatewayClient;
import com.github.xingjianluo.edgeapigatewayjava.support.TestConfigs;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 全链路测试：真实 Spring Boot 网关 + 本机假上游。
 * 每个用例通过 ConfigStore 原子发布内存路由，断言对外可观察的归一化结果。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayEndToEndTest {

    private static final Logger log = LoggerFactory.getLogger(GatewayEndToEndTest.class);

    @LocalServerPort
    private int port;

    @Autowired
    private ConfigStore configStore;

    @Autowired
    private RateLimitService rateLimitService;

    private FakeUpstreams upstream;
    private GatewayClient client;

    @BeforeEach
    void setUp() throws IOException {
        upstream = new FakeUpstreams(2, 800);
        upstream.start();
        client = new GatewayClient(port);
        // 每个用例独立的窗口/配额/去重状态，避免单例计数器在用例间串扰
        rateLimitService.resetForTest();
    }

    @AfterEach
    void tearDown() {
        upstream.stop();
    }

    private void publish(RouteConfig... routes) {
        configStore.publishForTest(TestConfigs.config(routes));
    }

    private Map<String, String> headers(String... kv) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put(kv[i], kv[i + 1]);
        }
        return map;
    }

    @Test
    @DisplayName("端到端转发：请求体与业务头透传，响应带 X-Gateway 标记与 requestId")
    void happyPathProxy() {
        publish(TestConfigs.route("echo", "POST", "/echo", upstream.baseUrl()));
        GatewayClient.Response resp = client.send("POST", "/echo/abc",
                headers("Content-Type", "text/plain", "X-Business", "biz-1"), "ping");

        log.info("input=POST /echo/abc body=ping basis='route echo' verdict status={} body={}",
                resp.status(), resp.body());
        assertEquals(200, resp.status());
        assertTrue(resp.body().contains("ping"));
        assertTrue(resp.body().contains("biz-1"));
        assertEquals("edge-api-gateway", resp.headers().get("x-gateway"));
        assertFalse(resp.headers().get("x-gateway-route") == null);
    }

    @Test
    @DisplayName("无路由 -> 404 ROUTE_NOT_FOUND 错误信封，原因可与 405/428 区分")
    void routeNotFoundEnvelope() {
        publish(TestConfigs.route("r", "GET", "/exists", upstream.baseUrl()));
        GatewayClient.Response resp = client.send("GET", "/nope", Map.of(), null);
        log.info("input=GET /nope verdict status={} body={}", resp.status(), resp.body());
        assertEquals(404, resp.status());
        assertTrue(resp.body().contains("ROUTE_NOT_FOUND"));
    }

    @Test
    @DisplayName("方法不允许 -> 405 METHOD_NOT_ALLOWED")
    void methodNotAllowedEnvelope() {
        publish(TestConfigs.route("r", "POST", "/api", upstream.baseUrl()));
        GatewayClient.Response resp = client.send("DELETE", "/api/x", Map.of(), null);
        log.info("input=DELETE /api/x verdict status={} body={}", resp.status(), resp.body());
        assertEquals(405, resp.status());
        assertTrue(resp.body().contains("METHOD_NOT_ALLOWED"));
    }

    @Test
    @DisplayName("缺失调用方身份 -> 401 MISSING_CALLER；越权调用方 -> 403 CALLER_FORBIDDEN")
    void authEnforcement() {
        RouteConfig route = TestConfigs.withCaller(
                TestConfigs.route("secure", "GET", "/secure", upstream.baseUrl()),
                true, "trusted-a");
        route.setStripPrefix("/secure");
        publish(route);

        GatewayClient.Response missing = client.send("GET", "/secure", Map.of(), null);
        GatewayClient.Response forbidden = client.send("GET", "/secure",
                headers("X-Caller-Id", "intruder"), null);
        GatewayClient.Response allowed = client.send("GET", "/secure/echo",
                headers("X-Caller-Id", "trusted-a"), null);

        log.info("input=no-caller -> {}; caller=intruder -> {}; caller=trusted-a -> {}",
                missing.status(), forbidden.status(), allowed.status());
        assertEquals(401, missing.status());
        assertTrue(missing.body().contains("MISSING_CALLER"));
        assertEquals(403, forbidden.status());
        assertTrue(forbidden.body().contains("CALLER_FORBIDDEN"));
        assertEquals(200, allowed.status());
    }

    @Test
    @DisplayName("端到端限流：第 3 个请求 429 RATE_LIMITED，错误信封可区分")
    void rateLimitedEndToEnd() {
        RouteConfig route = TestConfigs.withRateLimit(
                TestConfigs.route("rl", "GET", "/count", upstream.baseUrl()),
                2, 1000, -1);
        publish(route);
        GatewayClient.Response r1 = client.send("GET", "/count", headers("X-Caller-Id", "c"), null);
        GatewayClient.Response r2 = client.send("GET", "/count", headers("X-Caller-Id", "c"), null);
        GatewayClient.Response r3 = client.send("GET", "/count", headers("X-Caller-Id", "c"), null);
        log.info("input=3 sequential requests limit=2 verdict={}/{}/{} bodies3='{}'",
                r1.status(), r2.status(), r3.status(), r3.body());
        assertEquals(200, r1.status());
        assertEquals(200, r2.status());
        assertEquals(429, r3.status());
        assertTrue(r3.body().contains("RATE_LIMITED"));
    }

    @Test
    @DisplayName("同号请求重试：第二次携带相同 X-Request-Id 不重复计数（第 3 个新请求才被拒）")
    void idempotentRetryNotDoubleCounted() {
        RouteConfig route = TestConfigs.withRateLimit(
                TestConfigs.route("rl", "GET", "/count", upstream.baseUrl()),
                2, 1000, -1);
        publish(route);
        Map<String, String> retryHeaders = headers("X-Caller-Id", "c", "X-Request-Id", "fixed-id");
        GatewayClient.Response r1 = client.send("GET", "/count", retryHeaders, null);
        GatewayClient.Response r2 = client.send("GET", "/count", retryHeaders, null);
        GatewayClient.Response r3 = client.send("GET", "/count", retryHeaders, null);
        log.info("input=3 requests same X-Request-Id verdict={}/{}/{} (expect 200/200/200, dedup)",
                r1.status(), r2.status(), r3.status());
        assertEquals(200, r1.status());
        assertEquals(200, r2.status());
        assertEquals(200, r3.status());

        GatewayClient.Response fresh = client.send("GET", "/count",
                headers("X-Caller-Id", "c", "X-Request-Id", "fresh-id"), null);
        GatewayClient.Response fresh2 = client.send("GET", "/count",
                headers("X-Caller-Id", "c", "X-Request-Id", "fresh-id-2"), null);
        log.info("input=2 fresh ids after dedup verdict={}/{} (window had 1 slot left)",
                fresh.status(), fresh2.status());
        assertEquals(200, fresh.status());
        assertEquals(429, fresh2.status());
    }

    @Test
    @DisplayName("GET 幂等：上游两次 503 后第 3 次成功，网关对客户端返回 200")
    void idempotentRetryEndToEnd() {
        RouteConfig route = TestConfigs.withTimeoutAndRetries(
                TestConfigs.route("flaky", "GET", "/flaky", upstream.baseUrl()), 1000, 3);
        publish(route);
        GatewayClient.Response resp = client.send("GET", "/flaky", Map.of(), null);
        log.info("input=GET /flaky basis='2 failures then ok, retries=3' verdict={} body={}",
                resp.status(), resp.body());
        assertEquals(200, resp.status());
        assertEquals(3, upstream.hitCount("/flaky"));
    }

    @Test
    @DisplayName("POST 慢上游：不重试且返回 504 UPSTREAM_TIMEOUT 标准信封，上游只命中一次")
    void timeoutNormalizedEndToEnd() {
        RouteConfig route = TestConfigs.withTimeoutAndRetries(
                TestConfigs.route("slow", "POST", "/slow", upstream.baseUrl()), 200, 3);
        publish(route);
        GatewayClient.Response resp = client.send("POST", "/slow", Map.of(), "x");
        log.info("input=POST /slow verdict status={} body='{}' upstreamHits={}",
                resp.status(), resp.body(), upstream.hitCount("/slow"));
        assertEquals(504, resp.status());
        assertTrue(resp.body().contains("UPSTREAM_TIMEOUT"));
        assertEquals(1, upstream.hitCount("/slow"));
    }

    @Test
    @DisplayName("上游无监听 -> 502 UPSTREAM_CONNECT_FAILED 标准信封")
    void connectFailedNormalizedEndToEnd() throws IOException {
        int freePort;
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            freePort = socket.getLocalPort();
        }
        publish(TestConfigs.withTimeoutAndRetries(
                TestConfigs.route("dead", "GET", "/dead", "http://127.0.0.1:" + freePort),
                300, 0));
        GatewayClient.Response resp = client.send("GET", "/dead", Map.of(), null);
        log.info("input=GET /dead verdict status={} body='{}'", resp.status(), resp.body());
        assertEquals(502, resp.status());
        assertTrue(resp.body().contains("UPSTREAM_CONNECT_FAILED"));
    }

    @Test
    @DisplayName("请求体超 maxBodyBytes -> 413 PAYLOAD_TOO_LARGE，请求不进入转发")
    void payloadTooLarge() {
        publish(TestConfigs.route("echo", "POST", "/echo", upstream.baseUrl()));
        GatewayClient.Response resp = client.send("POST", "/echo",
                headers("Content-Type", "text/plain"), "x".repeat(2 * 1024 * 1024));
        log.info("input=POST /echo 2MB body (limit 1MB) verdict status={} body='{}'",
                resp.status(), resp.body());
        assertEquals(413, resp.status());
        assertTrue(resp.body().contains("PAYLOAD_TOO_LARGE"));
        assertEquals(0, upstream.hitCount("/echo"));
    }
}
