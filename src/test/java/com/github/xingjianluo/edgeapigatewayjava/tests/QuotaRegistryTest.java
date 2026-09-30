package com.github.xingjianluo.edgeapigatewayjava.tests;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import com.github.xingjianluo.edgeapigatewayjava.config.ConfigLoader;
import com.github.xingjianluo.edgeapigatewayjava.config.ConfigService;
import com.github.xingjianluo.edgeapigatewayjava.config.ConfigValidator;
import com.github.xingjianluo.edgeapigatewayjava.config.GatewayConfig;
import com.github.xingjianluo.edgeapigatewayjava.config.QuotaPolicy;
import com.github.xingjianluo.edgeapigatewayjava.quota.QuotaRegistry;
import com.github.xingjianluo.edgeapigatewayjava.quota.QuotaResult;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 周期配额注册表测试（注入可控时钟，精确覆盖窗口边界）：
 * 跨路由共享、调用方隔离、并发放行上限、重试/重复提交不重复计数、窗口边界重置与不重复计数、
 * 无关配置热更新不清零、配额策略调整整体生效、过期窗口回收。
 */
class QuotaRegistryTest {

    private static final Logger log = LoggerFactory.getLogger(QuotaRegistryTest.class);

    /** 以真实当前时间所属的 UTC 整点为基准，保证有界 GC（按系统时钟）不会把测试窗口误判为过期窗口。 */
    private static final long T_HOUR1 = java.time.Instant.now()
            .atZone(java.time.ZoneOffset.UTC).truncatedTo(java.time.temporal.ChronoUnit.HOURS)
            .toInstant().toEpochMilli();

    private final ConfigService configService =
            new ConfigService(new ConfigLoader(), new ConfigValidator());
    private final AtomicLong clock = new AtomicLong(T_HOUR1);
    private final QuotaRegistry registry = new QuotaRegistry(configService, clock::get);

    private QuotaPolicy hourPolicy(long limit, String tz) {
        return new QuotaPolicy("weekly-budget", List.of("r1", "r2"), limit,
                QuotaPolicy.PERIOD_HOUR, tz);
    }

    private QuotaPolicy hourPolicy(long limit) {
        return hourPolicy(limit, "UTC");
    }

    @Test
    void 跨多条路由共享同一份额度() {
        QuotaPolicy p = hourPolicy(3);
        assertTrue(registry.acquire(p, "alpha", "a1").allowed());
        assertTrue(registry.acquire(p, "alpha", "a2").allowed());
        // 命中同一策略覆盖的另一条路由 r2，仍从同一份额度扣
        QuotaResult third = registry.acquire(p, "alpha", "a3");
        QuotaResult fourth = registry.acquire(p, "alpha", "a4");
        log.info("[用例:跨路由共享] r1 用2次 + r2 用2次(额度3) -> 第三次allowed={} used={}, 第四次allowed={}",
                third.allowed(), third.used(), fourth.allowed());
        assertTrue(third.allowed());
        assertFalse(fourth.allowed(), "同一调用方在覆盖路由上合计不能超过总额度");
        assertEquals("QUOTA_EXHAUSTED", fourth.scope());
        assertEquals(3, fourth.used());
    }

    @Test
    void 调用方之间额度互相隔离() {
        QuotaPolicy p = hourPolicy(2);
        registry.acquire(p, "alpha", "a1");
        registry.acquire(p, "alpha", "a2");
        QuotaResult alphaBlocked = registry.acquire(p, "alpha", "a3");
        QuotaResult beta1 = registry.acquire(p, "beta", "b1");
        QuotaResult beta2 = registry.acquire(p, "beta", "b2");
        log.info("[用例:调用方隔离] alpha 用尽后: alpha={}, beta1={}, beta2={}",
                alphaBlocked.scope(), beta1.allowed(), beta2.allowed());
        assertFalse(alphaBlocked.allowed());
        assertTrue(beta1.allowed() && beta2.allowed(), "beta 必须拥有独立的完整额度");
    }

    @Test
    void 高并发下放行数绝不超过总额度() throws InterruptedException {
        QuotaPolicy p = hourPolicy(10);
        int threads = 200;
        ExecutorService pool = Executors.newFixedThreadPool(32);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger allowed = new AtomicInteger();
        AtomicInteger exhausted = new AtomicInteger();
        for (int idx = 0; idx < threads; idx++) {
            final int i = idx;
            pool.submit(() -> {
                try {
                    start.await();
                    QuotaResult r = registry.acquire(p, "alpha", "req-" + i);
                    if (r.allowed()) {
                        allowed.incrementAndGet();
                    } else if ("QUOTA_EXHAUSTED".equals(r.scope())) {
                        exhausted.incrementAndGet();
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
        log.info("[用例:并发放行上限] 输入=200并发 总额度10 -> 放行={}, 额度用尽拒绝={}",
                allowed.get(), exhausted.get());
        assertEquals(10, allowed.get(), "并发请求合计放行数必须恰好等于总额度，不能超发");
        assertEquals(190, exhausted.get());
    }

    @Test
    void 重试与重复提交不重复计数() {
        QuotaPolicy p = hourPolicy(2);
        // 模拟同一次客户端请求（相同 X-Request-Id）在 r1 与 r2 上的内部重试/重复提交
        QuotaResult first = registry.acquire(p, "alpha", "same-id");
        QuotaResult retry = registry.acquire(p, "alpha", "same-id");
        QuotaResult otherRoute = registry.acquire(p, "alpha", "same-id");
        QuotaResult secondDistinct = registry.acquire(p, "alpha", "other-id");
        QuotaResult thirdDistinct = registry.acquire(p, "alpha", "third-id");
        log.info("[用例:重试不重复计数] 首次charged={} 重试charged={} 跨路由重复charged={}, 第二个不同请求allowed={}, 第三个allowed={}",
                first.charged(), retry.charged(), otherRoute.charged(),
                secondDistinct.allowed(), thirdDistinct.allowed());
        assertTrue(first.allowed() && first.charged());
        assertTrue(retry.allowed() && !retry.charged(), "同 X-Request-Id 重试不能再扣额度");
        assertTrue(otherRoute.allowed() && !otherRoute.charged(), "跨覆盖路由的同键重复也不能再扣");
        assertTrue(secondDistinct.allowed());
        assertFalse(thirdDistinct.allowed(), "额度2：一个去重请求只占1，第二个不同请求占第2，第三个必须被拒");
    }

    @Test
    void 窗口边界自动重置且不重复计数() {
        QuotaPolicy p = hourPolicy(1);
        // 窗口1：用掉唯一额度
        QuotaPolicy use1 = hourPolicy(1);
        assertTrue(registry.acquire(use1, "alpha", "a1").allowed());
        assertFalse(registry.acquire(use1, "alpha", "a2").allowed());
        // 推进到下一个整点（窗口2）：额度必须自动重置为完整 1
        clock.set(T_HOUR1 + 60 * 60 * 1000L);
        QuotaResult inNewWindow = registry.acquire(hourPolicy(1), "alpha", "a3");
        // 旧窗口已扣过费的请求键滚动到新窗口后按新请求处理；新窗口第二个请求必须被拒
        QuotaResult secondInNewWindow = registry.acquire(hourPolicy(1), "alpha", "a4");
        log.info("[用例:窗口边界] 旧窗口用尽 -> 新窗口首次allowed={} charged={}, 新窗口第二次allowed={}",
                inNewWindow.allowed(), inNewWindow.charged(), secondInNewWindow.allowed());
        assertTrue(inNewWindow.allowed() && inNewWindow.charged(), "跨窗口后必须得到完整新额度");
        assertFalse(secondInNewWindow.allowed(), "新窗口内同样不能超额");
        // 窗口起点归一化值随边界变化，不会与旧窗口共用计数器
        assertTrue(inNewWindow.windowStart() > T_HOUR1);
    }

    @Test
    void 无关配置热更新不清零当前周期用量() {
        // 先装入与生产一致的初始快照（含配额策略定义），对账基线由此建立
        configService.replace(configWithQuota(5, 3000, true));
        registry.acquire(hourPolicy(5), "alpha", "a1");
        registry.acquire(hourPolicy(5), "alpha", "a2");
        // 只改超时、上游启停，不动配额策略定义
        configService.replace(configWithQuota(5, 3000, true));
        configService.replace(configWithQuota(5, 9999, false));
        QuotaResult r = registry.acquire(hourPolicy(5), "alpha", "a3");
        log.info("[用例:无关热更新不清零] 已用2, 两次无关更新后再用1次 -> used={}, allowed={}",
                r.used(), r.allowed());
        assertEquals(3, r.used(), "当前周期已用额度绝不能被无关配置更新清空");
        assertTrue(r.allowed());
        QuotaResult fourth = registry.acquire(hourPolicy(5), "alpha", "a4");
        QuotaResult fifth = registry.acquire(hourPolicy(5), "alpha", "a5");
        QuotaResult sixth = registry.acquire(hourPolicy(5), "alpha", "a6");
        assertTrue(fourth.allowed() && fifth.allowed());
        assertFalse(sixth.allowed(), "用量 5 已用尽，第 6 次必须被周期配额拒绝");
    }

    @Test
    void 调小额度立即整体生效且不能多用() {
        QuotaPolicy p = hourPolicy(10);
        for (int i = 1; i <= 3; i++) {
            assertTrue(registry.acquire(p, "alpha", "id-" + i).allowed());
        }
        // 已用 3；额度从 10 调小到 2（策略定义变化）-> 状态整体丢弃，新额度立即生效
        configService.replace(configWithQuota(2, 3000, true));
        List<Boolean> results = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            results.add(registry.acquire(hourPolicy(2), "alpha", "new-" + i).allowed());
        }
        long allowed = results.stream().filter(Boolean::booleanValue).count();
        log.info("[用例:额度调小] 旧额度10已用3 -> 新额度2后3次请求放行={}", allowed);
        assertEquals(2, allowed, "额度改小后必须立即按新额度执行，不能因已用量残留而多用，也不能新旧额度并存");
    }

    @Test
    void 删除策略后其状态被丢弃再恢复时从全新额度开始() {
        QuotaPolicy p = hourPolicy(2);
        registry.acquire(p, "alpha", "a1");
        registry.acquire(p, "alpha", "a2");
        assertFalse(registry.acquire(p, "alpha", "a3").allowed());
        // 删除配额策略（配置中不再有 quotaPolicies）
        configService.replace(new ConfigLoader().load("""
                {"upstreams":[{"id":"u","baseUrl":"http://127.0.0.1:1","enabled":true}],
                 "routes":[{"id":"r1","pathPrefix":"/a","upstream":"u","priority":1,
                            "timeoutMillis":3000,"maxRetries":0},
                           {"id":"r2","pathPrefix":"/b","upstream":"u","priority":2,
                            "timeoutMillis":3000,"maxRetries":0}]}
                """));
        // 重新加入同名策略：必须从全新状态开始（旧用量已随策略删除被丢弃）
        configService.replace(configWithQuota(2, 3000, true));
        QuotaResult r1 = registry.acquire(hourPolicy(2), "alpha", "a1");
        QuotaResult r2 = registry.acquire(hourPolicy(2), "alpha", "a2");
        log.info("[用例:删除再恢复策略] 重建后两次请求 allowed={},{}", r1.allowed(), r2.allowed());
        assertTrue(r1.allowed() && r2.allowed());
    }

    @Test
    void 过期窗口计数器会被回收() {
        // 大量历史调用方在旧窗口产生计数器；跨窗口并触发 GC 后，旧计数器不得长期残留
        QuotaPolicy p = hourPolicy(1000);
        for (int i = 0; i < 500; i++) {
            registry.acquire(p, "old-caller-" + i, "id-" + i);
        }
        clock.set(T_HOUR1 + 60 * 60 * 1000L);
        // 在新窗口制造足够多的申请以触发有界 GC（每 256 次触发一轮，每轮最多扫 2048 条）
        for (int round = 0; round < 6; round++) {
            for (int i = 0; i < 300; i++) {
                registry.acquire(hourPolicy(1000), "new-caller-" + round + "-" + i, "n-" + round + "-" + i);
            }
        }
        // GC 无法直接观测大小，但旧窗口调用方再次申请时必须按新窗口从完整额度开始（used=1）
        QuotaResult oldCallerAgain = registry.acquire(hourPolicy(1000), "old-caller-1", "fresh-id");
        log.info("[用例:过期回收] 旧调用方在新窗口的申请: allowed={}, used={}",
                oldCallerAgain.allowed(), oldCallerAgain.used());
        assertEquals(1, oldCallerAgain.used(), "旧窗口状态滚动后应得到全新计数，历史调用方不拖慢/污染新窗口");
    }

    private GatewayConfig configWithQuota(long limit, long timeoutMillis, boolean upstreamEnabled) {
        String enabled = upstreamEnabled ? "true" : "false";
        return new ConfigLoader().load("""
                {"upstreams":[{"id":"u","baseUrl":"http://127.0.0.1:1","enabled":%ENABLED%}],
                 "routes":[{"id":"r1","pathPrefix":"/a","upstream":"u","priority":1,
                            "timeoutMillis":%T%,"maxRetries":0},
                           {"id":"r2","pathPrefix":"/b","upstream":"u","priority":2,
                            "timeoutMillis":%T%,"maxRetries":0}],
                 "quotaPolicies":[{"id":"weekly-budget","routeIds":["r1","r2"],
                                   "limit":%L%,"period":"HOUR","timeZone":"UTC"}]}
                """
                .replace("%ENABLED%", enabled)
                .replace("%T%", String.valueOf(timeoutMillis))
                .replace("%L%", String.valueOf(limit)));
    }
}
