package com.github.xingjianluo.edgeapigatewayjava.tests;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 专项回归：服务「按初始配置启动、期间不做任何热更新」时，配置里已有的共享周期配额必须立即生效。
 *
 * 与 {@link GatewayEndToEndTest} 的区别：本测试类不调用 ConfigService.replaceFromJson，
 * 配置只来自外部文件的首次加载，精确复现生产上最常见的「用配置文件直接启动」路径。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@ActiveProfiles("demo")
class StartupQuotaEndToEndTest {

    private static final Logger log = LoggerFactory.getLogger(StartupQuotaEndToEndTest.class);

    /** 启动用配置文件（小额度共享配额），仅在首次加载时被读取，用例过程中绝不修改。 */
    private static Path startupConfigFile;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        startupConfigFile = Files.createTempFile("gateway-startup-quota-", ".json");
        startupConfigFile.toFile().deleteOnExit();
        Files.writeString(startupConfigFile, """
                {
                  "upstreams": [
                    { "id": "demo", "baseUrl": "http://127.0.0.1:%PORT%", "enabled": true }
                  ],
                  "quotas": [
                    { "id": "startup-budget", "limit": 3, "resetPeriod": "DAY", "timeZone": "UTC" }
                  ],
                  "routes": [
                    { "id": "v1-api", "pathPrefix": "/api/v1", "upstream": "demo",
                      "prefixRewrite": "/__upstream", "priority": 20, "timeoutMillis": 3000,
                      "maxRetries": 0, "globalRatePerSecond": 1000, "callerRatePerSecond": 1000,
                      "allowedCallers": ["alpha", "beta", "gamma", "core", "race", "retry", "guard"],
                      "quotaId": "startup-budget" },
                    { "id": "v2-api", "pathPrefix": "/api/v2", "upstream": "demo",
                      "prefixRewrite": "/__upstream", "priority": 30, "timeoutMillis": 3000,
                      "maxRetries": 0, "globalRatePerSecond": 1000, "callerRatePerSecond": 1000,
                      "allowedCallers": ["alpha", "beta", "gamma", "core", "race", "retry", "guard"],
                      "quotaId": "startup-budget" },
                    { "id": "v3-flaky", "pathPrefix": "/flaky-api", "upstream": "demo",
                      "prefixRewrite": "/__upstream/flaky", "priority": 10, "timeoutMillis": 3000,
                      "maxRetries": 2, "globalRatePerSecond": 1000, "callerRatePerSecond": 1000,
                      "allowedCallers": ["alpha", "beta", "gamma", "core", "race", "retry", "guard"],
                      "quotaId": "startup-budget" }
                  ]
                }
                """.replace("%PORT%", String.valueOf(port)), StandardCharsets.UTF_8);
        registry.add("server.port", () -> port);
        registry.add("gateway.config.path", () -> startupConfigFile.toString());
    }

    @Autowired
    private TestRestTemplate rest;

    @BeforeAll
    static void note() {
        log.info("[启动即生效] 启动配置文件: {}, 用例过程中不做任何热更新", startupConfigFile);
    }

    private ResponseEntity<String> call(String path, String caller, String requestId) {
        HttpHeaders h = new HttpHeaders();
        if (caller != null) {
            h.add("X-Caller-Id", caller);
        }
        if (requestId != null) {
            h.add("X-Request-Id", requestId);
        }
        h.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(h), String.class);
    }

    @Test
    void 初始配置启动后不做任何热更新共享周期配额立即生效() {
        // 初始配置：v1-api 与 v2-api 共享 startup-budget，调用方 core 每窗口额度 3。
        // 全程不触发热更新；前 3 次（跨两条路由）放行，第 4 次起必须被配额拒绝。
        ResponseEntity<String> r1 = call("/api/v1/a", "core", "startup-q-1");
        ResponseEntity<String> r2 = call("/api/v2/b", "core", "startup-q-2");
        ResponseEntity<String> r3 = call("/api/v1/c", "core", "startup-q-3");
        ResponseEntity<String> r4 = call("/api/v2/d", "core", "startup-q-4");

        log.info("[启动即生效] 跨v1/v2连续4次: {},{},{},{}",
                r1.getStatusCode().value(), r2.getStatusCode().value(),
                r3.getStatusCode().value(), r4.getStatusCode().value());

        assertEquals(HttpStatus.OK, r1.getStatusCode());
        assertEquals(HttpStatus.OK, r2.getStatusCode());
        assertEquals(HttpStatus.OK, r3.getStatusCode());
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, r4.getStatusCode(),
                "按初始配置启动后共享周期配额必须立即生效，超额请求必须被拒");
        assertTrue(r4.getBody().contains("QUOTA_EXHAUSTED"),
                "周期配额超额必须返回独立 code，与按秒限流可区分");
        assertFalse(r4.getBody().contains("RATE_LIMITED"),
                "不能与按秒限流 RATE_LIMITED_* 混淆");
        assertFalse(r4.getBody().contains("\"upstream\""),
                "被周期配额拒绝的请求绝对不能到达上游");
    }

    @Test
    void 启动即生效时调用方之间额度相互隔离() {
        // alpha 先用满自己的 3 次（跨 v1/v2），其超额不能影响 beta 的独立额度。
        ResponseEntity<String> a1 = call("/api/v1/iso-a1", "alpha", "startup-iso-a1");
        ResponseEntity<String> a2 = call("/api/v2/iso-a2", "alpha", "startup-iso-a2");
        ResponseEntity<String> a3 = call("/api/v1/iso-a3", "alpha", "startup-iso-a3");
        ResponseEntity<String> a4 = call("/api/v2/iso-a4", "alpha", "startup-iso-a4");
        assertEquals(HttpStatus.OK, a1.getStatusCode());
        assertEquals(HttpStatus.OK, a2.getStatusCode());
        assertEquals(HttpStatus.OK, a3.getStatusCode());
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, a4.getStatusCode());

        ResponseEntity<String> b1 = call("/api/v1/iso-b1", "beta", "startup-iso-b1");
        ResponseEntity<String> b2 = call("/api/v2/iso-b2", "beta", "startup-iso-b2");
        ResponseEntity<String> b3 = call("/api/v1/iso-b3", "beta", "startup-iso-b3");
        ResponseEntity<String> b4 = call("/api/v2/iso-b4", "beta", "startup-iso-b4");
        log.info("[启动即生效:隔离] alpha={},{},{},{}; beta={},{},{},{}",
                a1.getStatusCode().value(), a2.getStatusCode().value(), a3.getStatusCode().value(),
                a4.getStatusCode().value(), b1.getStatusCode().value(), b2.getStatusCode().value(),
                b3.getStatusCode().value(), b4.getStatusCode().value());
        assertEquals(HttpStatus.OK, b1.getStatusCode(), "beta 拥有独立周期额度，不受 alpha 超额影响");
        assertEquals(HttpStatus.OK, b2.getStatusCode());
        assertEquals(HttpStatus.OK, b3.getStatusCode());
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, b4.getStatusCode());
        assertTrue(b4.getBody().contains("QUOTA_EXHAUSTED"));
    }

    @Test
    void 启动即生效时跨两条路由并发合计放行不超过总额度() throws Exception {
        // 新调用方 race，启动配置额度 3；30 个请求在 v1/v2 两条共享路由上同时竞争。
        int total = 30;
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(total);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger quotaRejected = new AtomicInteger();
        AtomicInteger other = new AtomicInteger();

        for (int i = 0; i < total; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    // 路由在 v1/v2 间交替，验证共享的是同一份调用方计数
                    String path = (idx % 2 == 0 ? "/api/v1/race-" : "/api/v2/race-") + idx;
                    ResponseEntity<String> r = call(path, "race", "startup-race-" + idx);
                    if (r.getStatusCode() == HttpStatus.OK) {
                        ok.incrementAndGet();
                    } else if (r.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS
                            && r.getBody() != null && r.getBody().contains("QUOTA_EXHAUSTED")) {
                        quotaRejected.incrementAndGet();
                    } else {
                        other.incrementAndGet();
                        log.warn("[启动即生效:并发] 非预期响应 status={} body={}",
                                r.getStatusCode(), r.getBody());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "并发请求必须全部完成");
        pool.shutdown();
        log.info("[启动即生效:并发] 30并发跨v1/v2: 放行={}, QUOTA_EXHAUSTED={}, 其他={}",
                ok.get(), quotaRejected.get(), other.get());
        assertEquals(0, other.get(), "启动即生效的并发期间只能出现 200 或 QUOTA_EXHAUSTED");
        assertEquals(3, ok.get(), "同一窗口内跨两条路由的并发合计放行绝不能超过总额度 3");
        assertEquals(total - 3, quotaRejected.get(), "其余请求必须稳定被周期配额拒绝");
    }

    @Test
    void 启动即生效时同一次请求的上游重试不多占周期额度() {
        // v3-flaky 上游前两次 503、第三次 200，maxRetries=2：网关内部会尝试 3 次，
        // 但周期额度只能扣 1 次。调用方 retry 额度 3：3 个这样的请求后仍应恰好用满 3 次。
        for (int i = 1; i <= 3; i++) {
            ResponseEntity<String> r = call("/flaky-api", "retry", "startup-retry-" + i);
            assertEquals(HttpStatus.OK, r.getStatusCode(),
                    "带重试的第 " + i + " 个请求必须最终成功（FLAKY_OK_ON_3）");
            assertTrue(r.getBody().contains("FLAKY_OK_ON_3"));
        }
        ResponseEntity<String> forth = call("/flaky-api/retry-4", "retry", "startup-retry-4");
        log.info("[启动即生效:重试] 3个重试请求后第4个请求状态={}", forth.getStatusCode().value());
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, forth.getStatusCode(),
                "3 个请求各经历 3 次上游尝试也只能占用 3 次额度，第 4 个请求必须被周期配额拒绝");
        assertTrue(forth.getBody().contains("QUOTA_EXHAUSTED"));
    }

    @Test
    void 启动即生效时缺身份和越权请求不多占周期额度() {
        // 越权调用方 outsider（不在白名单）连发 5 次，必须全部 403 且不扣周期额度。
        for (int i = 1; i <= 5; i++) {
            ResponseEntity<String> forbidden =
                    call("/api/v1/forbid-" + i, "outsider", "startup-forbid-" + i);
            assertEquals(HttpStatus.FORBIDDEN, forbidden.getStatusCode());
            assertTrue(forbidden.getBody().contains("CALLER_FORBIDDEN"));
        }
        // 缺 X-Caller-Id 连发 5 次，必须全部 401 且不扣周期额度（该路由挂载了配额，身份必填）。
        for (int i = 1; i <= 5; i++) {
            ResponseEntity<String> missing = call("/api/v1/anon-" + i, null, "startup-anon-" + i);
            assertEquals(HttpStatus.UNAUTHORIZED, missing.getStatusCode());
            assertTrue(missing.getBody().contains("CALLER_MISSING"));
        }
        // 全新的合法调用方 guard：若上面 10 个非法请求误占了它的额度，这里就凑不齐 3 次成功。
        for (int i = 1; i <= 3; i++) {
            ResponseEntity<String> ok = call("/api/v1/guard-" + i, "guard", "startup-guard-" + i);
            assertEquals(HttpStatus.OK, ok.getStatusCode(),
                    "越权/缺身份请求绝不能占用周期额度，合法调用方仍应完整拥有 3 次额度");
        }
        ResponseEntity<String> guardForth = call("/api/v1/guard-4", "guard", "startup-guard-4");
        log.info("[启动即生效:非法请求] 10个401/403之后 guard 前3次={}, 第4次={}",
                HttpStatus.OK, guardForth.getStatusCode().value());
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, guardForth.getStatusCode());
        assertTrue(guardForth.getBody().contains("QUOTA_EXHAUSTED"));
    }
}
