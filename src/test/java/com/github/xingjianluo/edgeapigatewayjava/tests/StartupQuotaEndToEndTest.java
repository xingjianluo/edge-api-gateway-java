package com.github.xingjianluo.edgeapigatewayjava.tests;

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
 * 端到端：服务「按初始配置启动、期间不做任何热更新」，初始配置里的共享周期配额必须立即生效。
 *
 * 与 {@link GatewayEndToEndTest} 的关键区别：本类全程不调用 /__admin/config/reload，
 * 也不直接 replace 配置——初始快照就是唯一的配置来源（startup-quota-routes.json），
 * 精确对应最常用的「带着配好共享配额的配置直接上线」路径。
 *
 * 初始配额：quotaId=shared-budget，每调用方 3 次/天（UTC），
 * 被 /api/v1、/api/v2、/api/flaky 三条路由共享；按秒限流放到 1000 避免干扰。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@ActiveProfiles({"demo", "startupquota"})
class StartupQuotaEndToEndTest {

    private static final Logger log = LoggerFactory.getLogger(StartupQuotaEndToEndTest.class);

    @Autowired
    private TestRestTemplate rest;

    private ResponseEntity<String> call(String path, String caller, String requestId) {
        HttpHeaders h = new HttpHeaders();
        if (caller != null) {
            h.add("X-Caller-Id", caller);
        }
        if (requestId != null) {
            h.add("X-Request-Id", requestId);
        }
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(h), String.class);
    }

    private void logResults(String label, Object... statuses) {
        StringBuilder sb = new StringBuilder();
        for (Object s : statuses) {
            sb.append(s).append(',');
        }
        log.info("[端到端启动即生效:{}] 连续请求状态序列={}", label, sb);
    }

    @Test
    void 按初始配置启动后不做热更新跨路由共享额度用尽即拒且不到上游() {
        // 同一调用方在两条共享同一份额度的路由上交替请求，全程没有任何热更新。
        ResponseEntity<String> r1 = call("/api/v1/a", "alpha", "sq-1");
        ResponseEntity<String> r2 = call("/api/v2/b", "alpha", "sq-2");
        ResponseEntity<String> r3 = call("/api/v1/c", "alpha", "sq-3");
        ResponseEntity<String> r4 = call("/api/v2/d", "alpha", "sq-4");
        ResponseEntity<String> r5 = call("/api/v1/e", "alpha", "sq-5");
        logResults("跨路由用尽", r1.getStatusCode(), r2.getStatusCode(), r3.getStatusCode(),
                r4.getStatusCode(), r5.getStatusCode());

        assertEquals(HttpStatus.OK, r1.getStatusCode());
        assertEquals(HttpStatus.OK, r2.getStatusCode());
        assertEquals(HttpStatus.OK, r3.getStatusCode());
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, r4.getStatusCode());
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, r5.getStatusCode(), "超额后必须稳定拒绝，不能漏过");
        assertTrue(r4.getBody().contains("QUOTA_EXHAUSTED"),
                "周期配额超额拒绝必须能与按秒限流区分（独立 code）");
        assertTrue(!r4.getBody().contains("RATE_LIMITED_"), "不能误报按秒限流");
        assertTrue(!r4.getBody().contains("\"upstream\""),
                "被周期配额拒绝的请求绝对不能转发到上游");
        assertTrue(!r5.getBody().contains("\"upstream\""));
    }

    @Test
    void 启动路径上不同调用方额度相互隔离() {
        // beta 用满自己的 3 次；gamma 独立拥有完整 3 次
        ResponseEntity<String> b1 = call("/api/v1/b1", "beta", "sq-b1");
        ResponseEntity<String> b2 = call("/api/v2/b2", "beta", "sq-b2");
        ResponseEntity<String> b3 = call("/api/v1/b3", "beta", "sq-b3");
        ResponseEntity<String> b4 = call("/api/v2/b4", "beta", "sq-b4");

        ResponseEntity<String> g1 = call("/api/v1/g1", "gamma", "sq-g1");
        ResponseEntity<String> g2 = call("/api/v2/g2", "gamma", "sq-g2");
        ResponseEntity<String> g3 = call("/api/v1/g3", "gamma", "sq-g3");
        ResponseEntity<String> g4 = call("/api/v2/g4", "gamma", "sq-g4");
        logResults("调用方隔离", b1.getStatusCode(), b2.getStatusCode(), b3.getStatusCode(), b4.getStatusCode(),
                g1.getStatusCode(), g2.getStatusCode(), g3.getStatusCode(), g4.getStatusCode());

        assertEquals(HttpStatus.OK, b1.getStatusCode());
        assertEquals(HttpStatus.OK, b2.getStatusCode());
        assertEquals(HttpStatus.OK, b3.getStatusCode());
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, b4.getStatusCode());
        assertTrue(b4.getBody().contains("QUOTA_EXHAUSTED"));

        assertEquals(HttpStatus.OK, g1.getStatusCode());
        assertEquals(HttpStatus.OK, g2.getStatusCode());
        assertEquals(HttpStatus.OK, g3.getStatusCode(), "beta 用尽不能影响 gamma 的独立额度");
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, g4.getStatusCode());
        assertTrue(g4.getBody().contains("QUOTA_EXHAUSTED"));
    }

    @Test
    void 启动路径上并发请求合计放行不超过总额度() throws InterruptedException {
        int threads = 40;
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger quotaRejected = new AtomicInteger();
        AtomicInteger leaked = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            final int n = i;
            pool.submit(() -> {
                try {
                    start.await();
                    ResponseEntity<String> r = call("/api/v2/race/" + n, "racer", "sq-r" + n);
                    if (r.getStatusCode() == HttpStatus.OK) {
                        ok.incrementAndGet();
                    } else if (r.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS
                            && r.getBody() != null && r.getBody().contains("QUOTA_EXHAUSTED")) {
                        quotaRejected.incrementAndGet();
                    } else {
                        leaked.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS));
        pool.shutdown();

        log.info("[端到端启动即生效:并发不超发] 40 并发（初始配置 limit=3，无热更新）-> 200={}, 429/QUOTA={}, 其他={}",
                ok.get(), quotaRejected.get(), leaked.get());
        assertEquals(3, ok.get(), "同一窗口跨并发的合计放行量绝不能超过总额度 3");
        assertEquals(threads - 3, quotaRejected.get(), "其余请求必须全部以 QUOTA_EXHAUSTED 拒绝");
        assertEquals(0, leaked.get(), "不允许出现既非放行又非配额拒绝的结果");

        // 收敛后再来一次仍必须拒绝
        ResponseEntity<String> after = call("/api/v2/after", "racer", "sq-after");
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, after.getStatusCode());
        assertTrue(after.getBody().contains("QUOTA_EXHAUSTED"));
    }

    @Test
    void 启动路径上缺身份与越权请求不占用周期额度() {
        // v1 白名单为 alpha/beta/gamma：缺 X-Caller-Id 与越权调用都必须在配额扣减前被拒。
        ResponseEntity<String> missing = call("/api/v1/no-caller", null, "sq-m1");
        ResponseEntity<String> forbidden = call("/api/v1/outsider", "outsider", "sq-o1");
        log.info("[端到端启动即生效:不占额度] 缺身份={}, 越权={}",
                missing.getStatusCode(), forbidden.getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED, missing.getStatusCode());
        assertTrue(missing.getBody().contains("CALLER_MISSING"));
        assertEquals(HttpStatus.FORBIDDEN, forbidden.getStatusCode());
        assertTrue(forbidden.getBody().contains("CALLER_FORBIDDEN"));

        // 被前置拒绝的请求不占额度：delta 调用方仍能完整用满 3 次
        ResponseEntity<String> d1 = call("/api/v2/d1", "delta", "sq-d1");
        ResponseEntity<String> d2 = call("/api/v2/d2", "delta", "sq-d2");
        ResponseEntity<String> d3 = call("/api/v2/d3", "delta", "sq-d3");
        ResponseEntity<String> d4 = call("/api/v2/d4", "delta", "sq-d4");
        logResults("缺身份/越权不占额度", d1.getStatusCode(), d2.getStatusCode(), d3.getStatusCode(),
                d4.getStatusCode());
        assertEquals(HttpStatus.OK, d1.getStatusCode());
        assertEquals(HttpStatus.OK, d2.getStatusCode());
        assertEquals(HttpStatus.OK, d3.getStatusCode());
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, d4.getStatusCode());
        assertTrue(d4.getBody().contains("QUOTA_EXHAUSTED"));
    }

    @Test
    void 启动路径上同一次请求的上游重试与重复提交都不多占额度() {
        // flaky 上游前两次 503、第 3 次 200，maxRetries=2：一次网关请求内部重试 3 次只应扣 1 次额度。
        ResponseEntity<String> retried = call("/api/flaky", "retry", "sq-fr1");
        log.info("[端到端启动即生效:重试不多占] flaky 重试后 status={}, body={}",
                retried.getStatusCode(),
                retried.getBody() == null ? "" : retried.getBody().substring(0, Math.min(120, retried.getBody().length())));
        assertEquals(HttpStatus.OK, retried.getStatusCode());
        assertTrue(retried.getBody().contains("FLAKY_OK_ON_3"));

        // 同一 X-Request-Id 重复提交被幂等拒绝，且不能再扣额度
        ResponseEntity<String> dup = call("/api/flaky", "retry", "sq-fr1");
        assertEquals(HttpStatus.CONFLICT, dup.getStatusCode());
        assertTrue(dup.getBody().contains("REQUEST_DUPLICATED"));
        assertTrue(!dup.getBody().contains("\"upstream\""), "重复提交不能再到上游");

        // 额度只应被那次（含内部重试的）请求占用 1 次：剩余仍有 2 次完整额度
        ResponseEntity<String> more2 = call("/api/v2/r2", "retry", "sq-fr2");
        ResponseEntity<String> more3 = call("/api/v2/r3", "retry", "sq-fr3");
        ResponseEntity<String> more4 = call("/api/v2/r4", "retry", "sq-fr4");
        logResults("重试/重复不多占", retried.getStatusCode(), dup.getStatusCode(),
                more2.getStatusCode(), more3.getStatusCode(), more4.getStatusCode());
        assertEquals(HttpStatus.OK, more2.getStatusCode());
        assertEquals(HttpStatus.OK, more3.getStatusCode());
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, more4.getStatusCode());
        assertTrue(more4.getBody().contains("QUOTA_EXHAUSTED"),
                "内部重试与重复提交都不多占：第 1(重试成功) + 2 次后必须到顶");
    }

    @Test
    void 启动路径上原有路由与健康检查不受配额影响() {
        // 不带 X-Caller-Id：未挂配额的 /health 路由不受 CALLER_MISSING/配额约束，正常转发
        HttpHeaders h = new HttpHeaders();
        ResponseEntity<String> health =
                rest.exchange("/health", HttpMethod.GET, new HttpEntity<>(h), String.class);
        log.info("[端到端启动即生效:无配额路由] /health status={}", health.getStatusCode());
        assertEquals(HttpStatus.OK, health.getStatusCode());
        assertTrue(health.getBody().contains("\"upstream\""));
    }
}
