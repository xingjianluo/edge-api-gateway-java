package com.github.xingjianluo.edgeapigatewayjava.tests;

import com.github.xingjianluo.edgeapigatewayjava.config.ConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 端到端测试：网关与 demo 上游运行在同一个随机端口的真实服务里，
 * 网关把请求经 JDK HttpClient 转发回 127.0.0.1 本机自身的 /__upstream/**，
 * 全程真实 HTTP 但不依赖外部网络或真实凭据。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("demo")
class GatewayEndToEndTest {

    private static final Logger log = LoggerFactory.getLogger(GatewayEndToEndTest.class);

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private ConfigService configService;

    /** 每个用例前原子装入指向本进程端口的完整配置（高配额，避免用例间互相限流）。 */
    @BeforeEach
    void installConfig() {
        configService.replaceFromJson(configJson("true", 1000, 1000));
    }

    private String configJson(String enabled, double globalRate, double callerRate) {
        return """
                {
                  "upstreams":[{"id":"demo","baseUrl":"http://127.0.0.1:%PORT%","enabled":%ENABLED%}],
                  "routes":[
                    {"id":"v2-orders-explicit","pathPrefix":"/api/v2/orders","methods":["POST"],
                     "requiredHeaders":{"X-Channel":"partner"},"upstream":"demo",
                     "prefixRewrite":"/__upstream/ok","priority":10,"timeoutMillis":3000,"maxRetries":0,
                     "globalRatePerSecond":%GR%,"callerRatePerSecond":%CR%,
                     "allowedCallers":["alpha","beta"]},
                    {"id":"v1-api","pathPrefix":"/api/v1","upstream":"demo",
                     "prefixRewrite":"/__upstream","priority":20,"timeoutMillis":3000,"maxRetries":2,
                     "globalRatePerSecond":%GR%,"callerRatePerSecond":%CR%,
                     "allowedCallers":["alpha","beta","gamma"]},
                    {"id":"v2-api","pathPrefix":"/api/v2","upstream":"demo",
                     "prefixRewrite":"/__upstream","priority":30,"timeoutMillis":3000,"maxRetries":2,
                     "globalRatePerSecond":%GR%,"callerRatePerSecond":%CR%}
                  ]
                }
                """
                .replace("%PORT%", String.valueOf(port))
                .replace("%ENABLED%", enabled)
                .replace("%GR%", String.valueOf(globalRate))
                .replace("%CR%", String.valueOf(callerRate));
    }

    private ResponseEntity<String> exchange(String path, HttpMethod method,
                                            String caller, String requestId, String channel, Object body) {
        HttpHeaders h = new HttpHeaders();
        if (caller != null) {
            h.add("X-Caller-Id", caller);
        }
        if (requestId != null) {
            h.add("X-Request-Id", requestId);
        }
        if (channel != null) {
            h.add("X-Channel", channel);
        }
        h.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Object> entity = new HttpEntity<>(body, h);
        return rest.exchange(path, method, entity, String.class);
    }

    private void logResult(String label, ResponseEntity<String> r) {
        String b = r.getBody() == null ? "" : r.getBody();
        log.info("[端到端:{}] status={}, body={}", label, r.getStatusCode().value(),
                b.substring(0, Math.min(240, b.length())));
    }

    @Test
    void 正常请求被转发并返回上游结果() {
        ResponseEntity<String> r = exchange("/api/v1/widgets", HttpMethod.GET, "alpha", null, null, null);
        logResult("正常转发", r);
        assertEquals(HttpStatus.OK, r.getStatusCode());
        assertTrue(r.getBody().contains("\"upstream\":\"demo\""));
    }

    @Test
    void 无法匹配路由返回可区分的404() {
        ResponseEntity<String> r = exchange("/no/such/route", HttpMethod.GET, "alpha", null, null, null);
        logResult("无匹配", r);
        assertEquals(HttpStatus.NOT_FOUND, r.getStatusCode());
        assertTrue(r.getBody().contains("ROUTE_NOT_FOUND"));
    }

    @Test
    void 缺少调用方身份被拒绝且不转发() {
        ResponseEntity<String> r = exchange("/api/v2/orders/1", HttpMethod.POST, null, null, "partner", "{}");
        logResult("缺身份", r);
        assertEquals(HttpStatus.UNAUTHORIZED, r.getStatusCode());
        assertTrue(r.getBody().contains("CALLER_MISSING"));
    }

    @Test
    void 越权调用方被拒绝() {
        ResponseEntity<String> r = exchange("/api/v2/orders/1", HttpMethod.POST, "outsider", null, "partner", "{}");
        logResult("越权", r);
        assertEquals(HttpStatus.FORBIDDEN, r.getStatusCode());
        assertTrue(r.getBody().contains("CALLER_FORBIDDEN"));
    }

    @Test
    void 重复幂等键被拒绝() {
        exchange("/api/v2/orders/1", HttpMethod.POST, "alpha", "e2e-dedup-1", "partner", "{}");
        ResponseEntity<String> r = exchange("/api/v2/orders/1", HttpMethod.POST, "alpha", "e2e-dedup-1", "partner", "{}");
        logResult("重复请求", r);
        assertEquals(HttpStatus.CONFLICT, r.getStatusCode());
        assertTrue(r.getBody().contains("REQUEST_DUPLICATED"));
    }

    @Test
    void 调用方超额返回429且与其他调用方隔离() {
        // 低配额配置：每调用方 1/s（桶容量 1）。alpha 快速突发 3 次，必然至少 2 次被拒；
        // beta 拥有独立的调用方桶，不受 alpha 超额影响。
        configService.replaceFromJson(configJson("true", 1000, 1));
        int alphaAllowed = 0;
        int alphaRejected = 0;
        for (int i = 0; i < 3; i++) {
            ResponseEntity<String> r = exchange("/api/v1/a" + i, HttpMethod.GET, "alpha", "rl-a" + i, null, null);
            if (r.getStatusCode() == HttpStatus.OK) {
                alphaAllowed++;
            } else if (r.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS
                    && r.getBody().contains("RATE_LIMITED_CALLER")) {
                alphaRejected++;
            }
        }
        ResponseEntity<String> other = exchange("/api/v1/c", HttpMethod.GET, "beta", "rl-b1", null, null);
        log.info("[端到端:限流隔离] alpha 突发3次: 放行={} 429={}; beta={}",
                alphaAllowed, alphaRejected, other.getStatusCode());
        assertEquals(1, alphaAllowed, "桶容量为 1，突发 3 次只能放行 1 次");
        assertTrue(alphaRejected >= 2, "超额请求必须返回 429/RATE_LIMITED_CALLER");
        assertEquals(HttpStatus.OK, other.getStatusCode(), "beta 不应受 alpha 超额影响");
    }

    @Test
    void 幂等请求遇到上游故障会重试成功() {
        ResponseEntity<String> r = exchange("/api/v2/flaky", HttpMethod.GET, "alpha", null, null, null);
        logResult("幂等重试", r);
        assertEquals(HttpStatus.OK, r.getStatusCode());
        assertTrue(r.getBody().contains("FLAKY_OK_ON_3"));
    }

    @Test
    void 非幂等请求遇错不重试直接归一化() {
        ResponseEntity<String> r = exchange("/api/v2/error", HttpMethod.POST, "alpha", null, null, "{}");
        logResult("非幂等不重试", r);
        assertEquals(HttpStatus.BAD_GATEWAY, r.getStatusCode());
        assertTrue(r.getBody().contains("UPSTREAM_ERROR"));
        assertTrue(r.getBody().contains("已尝试 1 次"), "非幂等必须只尝试一次");
    }

    @Test
    void 上游超时被归一化为504() {
        long t0 = System.currentTimeMillis();
        ResponseEntity<String> r = exchange("/api/v2/slow", HttpMethod.GET, "alpha", null, null, null);
        long cost = System.currentTimeMillis() - t0;
        logResult("超时", r);
        assertEquals(HttpStatus.GATEWAY_TIMEOUT, r.getStatusCode());
        assertTrue(r.getBody().contains("RETRIES_EXHAUSTED"));
        assertTrue(cost < 11_000, "3 次 3s 超时应在约 9s 完成，实际 " + cost + "ms");
    }

    @Test
    void 热更新禁用上游后新请求被拒绝而坏配置不会半生效() {
        configService.replaceFromJson(configJson("false", 1000, 1000));

        ResponseEntity<String> rejected = exchange("/api/v1/x", HttpMethod.GET, "alpha", null, null, null);
        logResult("热更新后下线", rejected);
        assertEquals(HttpStatus.NOT_FOUND, rejected.getStatusCode());
        assertTrue(rejected.getBody().contains("ROUTE_NOT_FOUND"));

        ResponseEntity<String> invalid = rest.postForEntity("/__admin/config/reload",
                new HttpEntity<>("""
                        {"upstreams":[],"routes":[{"id":"bad","pathPrefix":"/x","upstream":"ghost",
                        "priority":1,"timeoutMillis":3000,"maxRetries":0}]}
                        """, jsonHeaders()), String.class);
        logResult("坏配置拒绝", invalid);
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, invalid.getStatusCode());
        assertTrue(invalid.getBody().contains("CONFIG_INVALID"));

        ResponseEntity<String> stillDisabled = exchange("/api/v1/x", HttpMethod.GET, "alpha", null, null, null);
        logResult("旧配置保持", stillDisabled);
        assertEquals(HttpStatus.NOT_FOUND, stillDisabled.getStatusCode(),
                "坏配置被拒后必须原子保留旧配置，绝不允许半生效");
    }

    @Test
    void 并发热更新期间每个请求都得到完整一致的结果() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger explainable = new AtomicInteger();
        AtomicInteger illegal = new AtomicInteger();

        int reloadCount = 4;
        int requestCount = 24;
        for (int i = 0; i < reloadCount + requestCount; i++) {
            final boolean reload = i < reloadCount;
            pool.submit(() -> {
                try {
                    start.await();
                    if (reload) {
                        // 在"启用"与"禁用"两套都完整合法的快照间反复原子切换
                        String json = Math.random() < 0.5
                                ? configJson("true", 1000, 1000)
                                : configJson("false", 1000, 1000);
                        rest.postForEntity("/__admin/config/reload",
                                new HttpEntity<>(json, jsonHeaders()), String.class);
                    } else {
                        ResponseEntity<String> r =
                                exchange("/api/v1/x", HttpMethod.GET, "alpha", null, null, null);
                        if (r.getStatusCode() == HttpStatus.OK) {
                            ok.incrementAndGet();
                        } else if (r.getBody() != null && r.getBody().contains("\"code\"")) {
                            explainable.incrementAndGet();
                        } else {
                            illegal.incrementAndGet();
                        }
                    }
                } catch (Exception e) {
                    log.warn("[端到端:并发热更新] 出现异常: {}", e.toString());
                    illegal.incrementAndGet();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        log.info("[端到端:并发热更新] 200响应={}, 可解释拒绝={}, 异常={}",
                ok.get(), explainable.get(), illegal.get());
        assertEquals(0, illegal.get(), "热更新期间不允许出现不可解释的结果或异常");
        assertTrue(ok.get() + explainable.get() == requestCount);
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }
}
