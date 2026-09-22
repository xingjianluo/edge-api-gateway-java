package com.github.xingjianluo.edgeapigatewayjava.tests;

import com.github.xingjianluo.edgeapigatewayjava.config.ConfigLoader;
import com.github.xingjianluo.edgeapigatewayjava.config.ConfigService;
import com.github.xingjianluo.edgeapigatewayjava.config.ConfigValidator;
import com.github.xingjianluo.edgeapigatewayjava.config.RouteConfig;
import com.github.xingjianluo.edgeapigatewayjava.ratelimit.RateLimiter;
import com.github.xingjianluo.edgeapigatewayjava.routing.RequestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 限流并发语义测试：
 * - 高并发下放行数绝不超过桶容量（不超发）；
 * - 同一 X-Request-Id 并发只计数一次；
 * - 一个调用方超额不影响其他调用方；
 * - 越权与缺少身份的请求不占用配额。
 */
class RateLimiterConcurrencyTest {

    private static final Logger log = LoggerFactory.getLogger(RateLimiterConcurrencyTest.class);

    private final RateLimiter rateLimiter =
            new RateLimiter(new ConfigService(new ConfigLoader(), new ConfigValidator()));

    @AfterEach
    void tearDown() {
        rateLimiter.reset();
    }

    private RouteConfig route(double callerRate, double globalRate, List<String> callers) {
        return new RouteConfig("r", "/r", List.of(), Map.of(), "u",
                null, 10, 1000, 0, globalRate, callerRate, callers);
    }

    private RequestContext ctx(String caller, String requestId) {
        return new RequestContext("GET", "/r/x",
                Map.of("X-Caller-Id", List.of(caller == null ? "" : caller)),
                caller, requestId, new byte[0]);
    }

    @Test
    void 并发请求不超发() throws InterruptedException {
        // 桶初始容量=速率=10；200 个并发请求在同一瞬间竞争。
        // 令牌桶的硬约束是"每个令牌最多被一个请求拿走"，因此放行数 = 初始容量 +
        // 竞争窗口内按时间合法补充的令牌。竞争窗口在毫秒级，补充量极小，
        // 关键断言是放行数绝不会随并发数增长而超过 容量+时间补偿（这里上限取 11）。
        RouteConfig r = route(10, 0, List.of());
        int threads = 200;
        ExecutorService pool = Executors.newFixedThreadPool(32);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger allowed = new AtomicInteger();
        long begin = System.nanoTime();

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    if (rateLimiter.check(r, ctx("c", "id-" + idx)).allowed()) {
                        allowed.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS));
        long windowMillis = (System.nanoTime() - begin) / 1_000_000;
        pool.shutdown();

        long maxExpected = 10 + Math.max(1, windowMillis * 10 / 1000) + 1;
        log.info("[用例:并发不超发] 输入=200并发, callerRate=10/s, 竞争窗口={}ms -> 放行={}, 拒绝={}, 理论上限={}",
                windowMillis, allowed.get(), threads - allowed.get(), maxExpected);

        assertTrue(allowed.get() >= 10, "初始 10 个令牌必须全部被成功领取");
        assertTrue(allowed.get() <= maxExpected,
                "并发竞争下绝不能超发: 放行 " + allowed.get() + " > 上限 " + maxExpected);
    }

    @Test
    void 同一幂等键并发只计数一次() throws InterruptedException {
        RouteConfig r = route(1000, 0, List.of());
        int threads = 50;
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger allowed = new AtomicInteger();
        AtomicInteger duplicated = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    var d = rateLimiter.check(r, ctx("c", "same-request-id"));
                    if (d.allowed()) {
                        allowed.incrementAndGet();
                    } else if ("REQUEST_DUPLICATED".equals(d.scope())) {
                        duplicated.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS));
        pool.shutdown();

        log.info("[用例:幂等去重并发] 输入=50并发同一X-Request-Id -> 放行={}, 重复拒绝={}",
                allowed.get(), duplicated.get());

        assertEquals(1, allowed.get(), "同一请求无论怎么并发都只能成功计数一次");
        assertEquals(threads - 1, duplicated.get());
    }

    @Test
    void 一个调用方超额不影响其他调用方() {
        RouteConfig r = route(2, 0, List.of());
        List<String> aResults = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            aResults.add(rateLimiter.check(r, ctx("alpha", "a-" + i)).allowed() ? "allow" : "reject");
        }
        boolean betaAllowed = rateLimiter.check(r, ctx("beta", "b-1")).allowed();
        boolean gammaAllowed = rateLimiter.check(r, ctx("gamma", "g-1")).allowed();

        long alphaAllow = aResults.stream().filter("allow"::equals).count();
        log.info("[用例:调用方隔离] alpha 6连发放行={} beta={} gamma={}", alphaAllow, betaAllowed, gammaAllowed);

        assertEquals(2, alphaAllow);
        assertTrue(betaAllowed);
        assertTrue(gammaAllowed);
    }

    @Test
    void 越权请求与缺少身份请求不消耗配额() {
        // callerRate=2 => 桶容量 2；非法请求发生后 alpha 必须仍保有完整容量
        RouteConfig r = route(2, 0, List.of("alpha"));
        var forbidden = rateLimiter.check(r, ctx("hacker", "h-1"));
        var missing = rateLimiter.check(r, ctx(null, "m-1"));

        boolean alpha1 = rateLimiter.check(r, ctx("alpha", "ok-1")).allowed();
        boolean alpha2 = rateLimiter.check(r, ctx("alpha", "ok-2")).allowed();

        log.info("[用例:非法请求不占配额] 越权={} 缺身份={} -> alpha 两次判定={},{}",
                forbidden.scope(), missing.scope(), alpha1, alpha2);

        assertEquals("CALLER_FORBIDDEN", forbidden.scope());
        assertEquals("CALLER_MISSING", missing.scope());
        assertTrue(alpha1, "非法请求绝不能消耗 alpha 的配额");
        assertTrue(alpha2, "alpha 的桶容量应保持完整（2 个令牌）");
    }

    @Test
    void 路由维度与调用方维度同时生效() {
        // 路由桶 3/s，每个调用方 10/s；两个调用方合计最多放行 3
        RouteConfig r = route(10, 3, List.of());
        AtomicInteger allowed = new AtomicInteger();
        ConcurrentLinkedQueue<String> rejectedScopes = new ConcurrentLinkedQueue<>();
        for (int i = 0; i < 8; i++) {
            String caller = i < 4 ? "alpha" : "beta";
            var d = rateLimiter.check(r, ctx(caller, "id-" + i));
            if (d.allowed()) {
                allowed.incrementAndGet();
            } else {
                rejectedScopes.add(d.scope());
            }
        }
        log.info("[用例:双维度限流] 输入=alpha4+beta4 global=3 caller=10 -> 放行={} 拒绝范围={}",
                allowed.get(), rejectedScopes);
        assertEquals(3, allowed.get(), "路由维度全局上限必须生效");
        assertTrue(rejectedScopes.contains("RATE_LIMITED_GLOBAL"));
    }
}
