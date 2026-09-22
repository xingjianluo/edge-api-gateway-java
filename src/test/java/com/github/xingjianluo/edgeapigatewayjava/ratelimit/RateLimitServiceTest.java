package com.github.xingjianluo.edgeapigatewayjava.ratelimit;

import com.github.xingjianluo.edgeapigatewayjava.model.RouteConfig;
import com.github.xingjianluo.edgeapigatewayjava.support.TestConfigs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * 限流服务单测/并发测。日志打印输入（调用方/请求号）、判定依据（窗口/配额/去重）与结论。
 */
class RateLimitServiceTest {

    private static final Logger log = LoggerFactory.getLogger(RateLimitServiceTest.class);

    private RateLimitService service;
    private RouteConfig route;

    @BeforeEach
    void setUp() {
        service = new RateLimitService();
        route = TestConfigs.withRateLimit(
                TestConfigs.route("r", "GET", "/r", "http://127.0.0.1:18081"),
                5, 1000, -1);
    }

    @Test
    @DisplayName("50 个并发请求抢 5 个许可证：恰好 5 个放行，绝不超发")
    void concurrentNoOversell() throws Exception {
        int threads = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        AtomicInteger allowed = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        CountDownAndRun barrier = new CountDownAndRun(threads);

        for (int i = 0; i < threads; i++) {
            final int n = i;
            futures.add(pool.submit((Callable<Void>) () -> {
                barrier.await();
                RateLimitService.Decision d = service.check(route, "caller-x", "req-" + n, 60_000);
                if (d.allowed()) {
                    allowed.incrementAndGet();
                }
                return null;
            }));
        }
        barrier.fire();
        for (Future<?> f : futures) {
            f.get();
        }
        pool.shutdown();
        log.info("input=50 concurrent requests, permits=5 basis='atomic fixed-window' verdict={} allowed",
                allowed.get());
        assertEquals(5, allowed.get(), "must never oversell");
    }

    @Test
    @DisplayName("同一 requestId 串行重试多次：只计一次，结论全部一致")
    void retrySameRequestIdCountedOnce() {
        RateLimitService.Decision first = service.check(route, "c", "same-id", 60_000);
        RateLimitService.Decision second = service.check(route, "c", "same-id", 60_000);
        RateLimitService.Decision third = service.check(route, "c", "same-id", 60_000);
        log.info("input=3x same requestId basis='dedup cache' verdict={}/{}/{}",
                first.verdict(), second.verdict(), third.verdict());

        assertEquals(RateLimitService.Verdict.ALLOW, first.verdict());
        assertEquals(first.verdict(), second.verdict());
        assertEquals(first.verdict(), third.verdict());

        // 新 id 仍有 4 个余量，证明重试没有重复占用窗口
        int allowed = 0;
        for (int i = 0; i < 6; i++) {
            if (service.check(route, "c", "other-" + i, 60_000).allowed()) {
                allowed++;
            }
        }
        log.info("input=6 fresh ids after dedup retries basis='window has 4 left' verdict={} allowed",
                allowed);
        assertEquals(4, allowed);
    }

    @Test
    @DisplayName("同号请求并发进入：只计数一次，且都拿到同一结论")
    void concurrentSameIdCountedOnce() throws Exception {
        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownAndRun barrier = new CountDownAndRun(threads);
        List<Future<RateLimitService.Decision>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                barrier.await();
                return service.check(route, "c", "shared-id", 60_000);
            }));
        }
        barrier.fire();
        long allowed = futures.stream().filter(f -> {
            try {
                return f.get().allowed();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }).count();
        pool.shutdown();
        // 同号请求是同一请求的并发副本：它们必须复用首请求的结论（全部 ALLOW），
        // 但“不得重复计数”由下面窗口余量检查证明（只消耗 1 个名额）。
        log.info("input=20 concurrent requests with same id basis='reservation token' verdict={}/20 ALLOW (same verdict reused)",
                allowed);
        assertEquals(20, allowed);

        // 窗口应只消耗了这 1 个名额
        int extraAllowed = 0;
        for (int i = 0; i < 5; i++) {
            if (service.check(route, "c", "new-" + i, 60_000).allowed()) {
                extraAllowed++;
            }
        }
        assertEquals(4, extraAllowed);
    }

    @Test
    @DisplayName("A 调用方超额不影响 B：B 的计数独立")
    void callersAreIsolated() {
        int aLimited = 0;
        int bAllowed = 0;
        for (int i = 0; i < 8; i++) {
            if (!service.check(route, "A", "a-" + i, 60_000).allowed()) {
                aLimited++;
            }
        }
        for (int i = 0; i < 5; i++) {
            if (service.check(route, "B", "b-" + i, 60_000).allowed()) {
                bAllowed++;
            }
        }
        log.info("input='8 requests A then 5 requests B' basis='per-caller keys' verdict=A rejected {} times, B allowed {}",
                aLimited, bAllowed);
        assertEquals(3, aLimited);
        assertEquals(5, bAllowed);
    }

    @Test
    @DisplayName("日配额耗尽返回 QUOTA_EXCEEDED，与窗口限流可区分")
    void dailyQuotaExhausted() {
        RouteConfig quotaRoute = TestConfigs.withRateLimit(
                TestConfigs.route("q", "GET", "/q", "http://127.0.0.1:18081"),
                1000, 1000, 2);
        assertEquals(RateLimitService.Verdict.ALLOW,
                service.check(quotaRoute, "c", "1", 60_000).verdict());
        assertEquals(RateLimitService.Verdict.ALLOW,
                service.check(quotaRoute, "c", "2", 60_000).verdict());
        RateLimitService.Decision third = service.check(quotaRoute, "c", "3", 60_000);
        log.info("input=3 requests, dailyQuota=2 basis='atomic daily counter' verdict={} reason='{}'",
                third.verdict(), third.reason());
        assertEquals(RateLimitService.Verdict.QUOTA_EXCEEDED, third.verdict());
    }

    @Test
    @DisplayName("同号请求落到不同调用方：IDEMPOTENCY_CONFLICT（409），且不影响第二次计数")
    void fingerprintConflict() {
        RateLimitService.Decision d1 = service.check(route, "caller-A", "dup", 60_000);
        RateLimitService.Decision d2 = service.check(route, "caller-B", "dup", 60_000);
        log.info("input='same id reused by B on same route' basis='fingerprint route|caller mismatch' verdict={} then {}",
                d1.verdict(), d2.verdict());
        assertEquals(RateLimitService.Verdict.ALLOW, d1.verdict());
        assertEquals(RateLimitService.Verdict.IDEMPOTENCY_CONFLICT, d2.verdict());
        assertNotEquals(d1.verdict(), d2.verdict());
    }

    /** 简易栅栏：所有线程就绪后统一放行，最大化竞争。 */
    private static final class CountDownAndRun {
        private final java.util.concurrent.CountDownLatch ready;
        private final java.util.concurrent.CountDownLatch fire = new java.util.concurrent.CountDownLatch(1);

        private CountDownAndRun(int n) {
            this.ready = new java.util.concurrent.CountDownLatch(n);
        }

        void await() {
            ready.countDown();
            try {
                ready.await();
                fire.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        void fire() {
            try {
                ready.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            fire.countDown();
        }
    }
}
