package com.github.xingjianluo.edgeapigatewayjava.tests;

import com.github.xingjianluo.edgeapigatewayjava.quota.QuotaDecision;
import com.github.xingjianluo.edgeapigatewayjava.quota.QuotaManager;
import com.github.xingjianluo.edgeapigatewayjava.quota.QuotaPolicy;
import com.github.xingjianluo.edgeapigatewayjava.quota.QuotaResetPeriod;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 周期配额管理器单测：
 * 跨路由共享、调用方隔离、窗口边界翻转、并发不超发、时区归一化、
 * 无关热更新不清零、策略调整整体生效（改小不能多用/周期时区变化重新计账/删除即回收）、过期窗口回收。
 */
class QuotaManagerTest {

    private static final Logger log = LoggerFactory.getLogger(QuotaManagerTest.class);

    /** 可控时钟：固定起点 2026-09-30T10:15:45Z（周三），便于断言小时/天/周边界。 */
    private final MutableClock clock = new MutableClock(Instant.parse("2026-09-30T10:15:45Z"), ZoneOffset.UTC);
    private final QuotaManager quotaManager = new QuotaManager(clock);

    @AfterEach
    void tearDown() {
        quotaManager.stop();
    }

    private QuotaPolicy policy(String id, long limit, QuotaResetPeriod period, String zone) {
        return new QuotaPolicy(id, limit, period, zone);
    }

    private boolean allow(QuotaDecision d) {
        return d.allowed();
    }

    @Test
    void 跨路由命中同一配额范围时从同一份额度扣减() {
        QuotaPolicy p = policy("shared", 3, QuotaResetPeriod.DAY, "UTC");
        quotaManager.reconcile(Map.of("shared", p));

        // 路由 a/b 只是 quotaId 相同的不同入口，对管理器而言都是对同一 (策略,调用方) 计数
        int allowed = 0;
        for (int i = 0; i < 5; i++) {
            if (allow(quotaManager.acquire(p, "alpha"))) {
                allowed++;
            }
        }
        log.info("[用例:跨路由共享] 输入=同一quotaId=shared, caller=alpha 连续5次, limit=3/天 -> 放行={}, 拒绝={}",
                allowed, 5 - allowed);
        assertEquals(3, allowed, "同一调用方在同一配额范围内多条路由合计只能用 3 次");
        assertEquals("QUOTA_EXHAUSTED", quotaManager.acquire(p, "alpha").scope());
    }

    @Test
    void 不同调用方额度相互隔离() {
        QuotaPolicy p = policy("q", 2, QuotaResetPeriod.HOUR, "UTC");
        quotaManager.reconcile(Map.of("q", p));

        assertTrue(allow(quotaManager.acquire(p, "alpha")));
        assertTrue(allow(quotaManager.acquire(p, "alpha")));
        QuotaDecision alphaThird = quotaManager.acquire(p, "alpha");
        QuotaDecision beta1 = quotaManager.acquire(p, "beta");
        QuotaDecision beta2 = quotaManager.acquire(p, "beta");

        log.info("[用例:调用方隔离] alpha 用满2次后第3次={}, beta 两次={},{}",
                alphaThird.scope(), beta1.allowed(), beta2.allowed());
        assertEquals("QUOTA_EXHAUSTED", alphaThird.scope());
        assertTrue(beta1.allowed() && beta2.allowed(), "beta 必须拥有独立的周期额度");
    }

    @Test
    void 小时窗口跨边界翻转后已用量重置且不重复计数() {
        QuotaPolicy p = policy("hourly", 2, QuotaResetPeriod.HOUR, "UTC");
        quotaManager.reconcile(Map.of("hourly", p));

        assertTrue(allow(quotaManager.acquire(p, "alpha")));
        assertTrue(allow(quotaManager.acquire(p, "alpha")));
        assertEquals("QUOTA_EXHAUSTED", quotaManager.acquire(p, "alpha").scope(), "窗口内超额必须拒绝");

        // 推进到下一个小时整点后：新窗口额度完整恢复
        clock.setInstant(Instant.parse("2026-09-30T11:00:00Z"));
        QuotaDecision afterFlip = quotaManager.acquire(p, "alpha");
        boolean secondInNewWindow = allow(quotaManager.acquire(p, "alpha"));
        log.info("[用例:小时窗口边界] 10点窗口用满 -> 11点整点后第1次={}, 第2次={}",
                afterFlip.allowed(), secondInNewWindow);
        assertTrue(afterFlip.allowed(), "跨小时边界后必须按新窗口重新计账");
        assertTrue(secondInNewWindow);
    }

    @Test
    void 并发请求合计放行绝不超过周期总额度() throws InterruptedException {
        QuotaPolicy p = policy("cap", 10, QuotaResetPeriod.DAY, "UTC");
        quotaManager.reconcile(Map.of("cap", p));

        int threads = 200;
        ExecutorService pool = Executors.newFixedThreadPool(32);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger allowed = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    if (allow(quotaManager.acquire(p, "alpha"))) {
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
        pool.shutdown();
        log.info("[用例:并发上限] 输入=200并发竞争 limit=10/天 -> 放行={}, 拒绝={}",
                allowed.get(), threads - allowed.get());
        assertEquals(10, allowed.get(), "同一窗口内多路由/并发请求合计绝不能超过总额度");
    }

    @Test
    void 重置边界按配置时区归一化() {
        // 上海时间(UTC+8) 按天重置：UTC 2026-09-30T17:00 = 上海 2026-10-01 01:00，应属于 10-01 的窗口
        QuotaPolicy p = policy("tz", 1, QuotaResetPeriod.DAY, "Asia/Shanghai");
        quotaManager.reconcile(Map.of("tz", p));

        long beforeDayBoundary = quotaManager.windowStartMillis(
                p, Instant.parse("2026-09-30T15:59:59Z").toEpochMilli());
        long afterDayBoundary = quotaManager.windowStartMillis(
                p, Instant.parse("2026-09-30T16:00:00Z").toEpochMilli());

        // 周窗口：上海周一 00:00 = UTC 周日 16:00
        QuotaPolicy weekly = policy("wk", 1, QuotaResetPeriod.WEEK, "Asia/Shanghai");
        long weekStartShanghaiMonday = quotaManager.windowStartMillis(
                weekly, Instant.parse("2026-09-30T10:15:45Z").toEpochMilli());

        log.info("[用例:时区归一化] 上海日窗口 UTC15:59起点={}, UTC16:00起点={}; 周一起点(UTC)={}",
                Instant.ofEpochMilli(beforeDayBoundary), Instant.ofEpochMilli(afterDayBoundary),
                Instant.ofEpochMilli(weekStartShanghaiMonday));

        assertEquals(Instant.parse("2026-09-29T16:00:00Z").toEpochMilli(), beforeDayBoundary);
        assertEquals(Instant.parse("2026-09-30T16:00:00Z").toEpochMilli(), afterDayBoundary);
        assertEquals(Instant.parse("2026-09-27T16:00:00Z").toEpochMilli(), weekStartShanghaiMonday,
                "按周重置应归一化到上海时区的周一 00:00");

        // 跨上海日界：上海 9/30 用掉唯一一次后，UTC 16:00（上海 10/01）必须恢复
        assertTrue(allow(quotaManager.acquire(p, "alpha")));
        assertEquals("QUOTA_EXHAUSTED", quotaManager.acquire(p, "alpha").scope());
        clock.setInstant(Instant.parse("2026-09-30T16:00:00Z"));
        assertTrue(allow(quotaManager.acquire(p, "alpha")), "跨上海时区日界后必须是新窗口");
    }

    @Test
    void 无关配置热更新不清零当前窗口已用额度() {
        QuotaPolicy p = policy("keep", 5, QuotaResetPeriod.DAY, "UTC");
        quotaManager.reconcile(Map.of("keep", p));
        for (int i = 0; i < 3; i++) {
            assertTrue(allow(quotaManager.acquire(p, "alpha")));
        }

        // 模拟只改超时/上游启停后重新加载：配额策略对象原样（甚至新对象但内容一致）再次对账
        quotaManager.reconcile(Map.of("keep", policy("keep", 5, QuotaResetPeriod.DAY, "UTC")));

        boolean forth = allow(quotaManager.acquire(p, "alpha"));
        boolean fifth = allow(quotaManager.acquire(p, "alpha"));
        QuotaDecision sixth = quotaManager.acquire(p, "alpha");
        log.info("[用例:无关热更新不清零] 已用3次后对账(limit不变) -> 第4={}, 第5={}, 第6={}",
                forth, fifth, sixth.scope());
        assertTrue(forth && fifth, "无关配置热更新绝不能清空已用额度");
        assertEquals("QUOTA_EXHAUSTED", sixth.scope(), "3+2=5 后必须继续拒绝");
    }

    @Test
    void 配额调小后不能多用而调大可在剩余窗口内继续使用() {
        QuotaPolicy big = policy("adj", 5, QuotaResetPeriod.DAY, "UTC");
        quotaManager.reconcile(Map.of("adj", big));
        for (int i = 0; i < 4; i++) {
            assertTrue(allow(quotaManager.acquire(big, "alpha")));
        }

        // 热更新把额度从 5 改小为 3：已用 4 >= 3，后续请求必须立即被拒（绝不能因为改配反而多用）
        QuotaPolicy small = policy("adj", 3, QuotaResetPeriod.DAY, "UTC");
        quotaManager.reconcile(Map.of("adj", small));
        QuotaDecision afterShrink = quotaManager.acquire(small, "alpha");
        log.info("[用例:配额调小] 已用4次后 limit 5->3 -> 判定={}", afterShrink.scope());
        assertEquals("QUOTA_EXHAUSTED", afterShrink.scope());

        // 另一调用方在新上限下也只能用 3 次
        int betaAllowed = 0;
        for (int i = 0; i < 5; i++) {
            if (allow(quotaManager.acquire(small, "beta"))) {
                betaAllowed++;
            }
        }
        assertEquals(3, betaAllowed, "新上限必须对新请求整体生效");

        // 再把额度调大为 10：alpha 已用 4，同窗口内应还能用 6 次
        QuotaPolicy larger = policy("adj", 10, QuotaResetPeriod.DAY, "UTC");
        quotaManager.reconcile(Map.of("adj", larger));
        int extra = 0;
        for (int i = 0; i < 6; i++) {
            if (allow(quotaManager.acquire(larger, "alpha"))) {
                extra++;
            }
        }
        QuotaDecision eleventh = quotaManager.acquire(larger, "alpha");
        log.info("[用例:配额调大] limit 3->10, alpha已用4 -> 追加放行={}, 再下一次={}", extra, eleventh.scope());
        assertEquals(6, extra, "调大后在同窗口内只能补到新上限");
        assertEquals("QUOTA_EXHAUSTED", eleventh.scope());
    }

    @Test
    void 重置周期或时区变化时旧窗口作废且不重复计数() {
        QuotaPolicy hourly = policy("mut", 2, QuotaResetPeriod.HOUR, "UTC");
        quotaManager.reconcile(Map.of("mut", hourly));
        assertTrue(allow(quotaManager.acquire(hourly, "alpha")));
        assertTrue(allow(quotaManager.acquire(hourly, "alpha")));
        assertEquals("QUOTA_EXHAUSTED", quotaManager.acquire(hourly, "alpha").scope());

        // 周期 HOUR -> DAY：旧窗口计数必须整体作废，按新规则开新窗口
        QuotaPolicy daily = policy("mut", 2, QuotaResetPeriod.DAY, "UTC");
        quotaManager.reconcile(Map.of("mut", daily));
        boolean firstAfterChange = allow(quotaManager.acquire(daily, "alpha"));
        log.info("[用例:重置规则变更] HOUR->DAY 后（同一时刻）第一次判定={}", firstAfterChange);
        assertTrue(firstAfterChange, "重置规则变化必须整体重新计账，旧用量不得泄漏");

        // 时区变化 UTC -> Asia/Shanghai 同样整体作废，且窗口边界按新时区计算
        QuotaPolicy shifted = policy("mut", 2, QuotaResetPeriod.DAY, "Asia/Shanghai");
        quotaManager.reconcile(Map.of("mut", shifted));
        assertTrue(allow(quotaManager.acquire(shifted, "alpha")), "时区变化也必须开新窗口");
    }

    @Test
    void 删除配额策略后旧状态被整体回收() {
        QuotaPolicy p = policy("gone", 2, QuotaResetPeriod.DAY, "UTC");
        quotaManager.reconcile(Map.of("gone", p));
        assertTrue(allow(quotaManager.acquire(p, "alpha")));
        assertTrue(allow(quotaManager.acquire(p, "beta")));
        assertEquals(2, quotaManager.activeWindowCount("gone"));

        quotaManager.reconcile(Map.of());
        log.info("[用例:策略删除] 删除前活跃窗口=2, 删除后活跃窗口={}", quotaManager.activeWindowCount("gone"));
        assertEquals(0, quotaManager.activeWindowCount("gone"), "删除策略后旧额度状态必须全部回收");
    }

    @Test
    void 过期窗口被回收且不拖慢后续新窗口请求() {
        QuotaPolicy p = policy("sweep", 1, QuotaResetPeriod.HOUR, "UTC");
        quotaManager.reconcile(Map.of("sweep", p));

        // 制造 50 个历史调用方，各自在 10 点窗口占用
        for (int i = 0; i < 50; i++) {
            assertTrue(allow(quotaManager.acquire(p, "old-caller-" + i)));
        }
        assertEquals(50, quotaManager.activeWindowCount("sweep"));

        // 进入 11 点窗口后执行回收
        clock.setInstant(Instant.parse("2026-09-30T11:00:00Z"));
        quotaManager.evictExpired(clock.millis());
        log.info("[用例:过期回收] 50个历史调用方窗口跨小时后回收, 剩余活跃窗口={}",
                quotaManager.activeWindowCount("sweep"));
        assertEquals(0, quotaManager.activeWindowCount("sweep"), "过期窗口必须被回收，不能无限占内存");

        // 新调用方在新窗口立即可用（热路径 O(1)，历史调用方不影响新请求）
        assertTrue(allow(quotaManager.acquire(p, "new-caller")));
        assertEquals(1, quotaManager.activeWindowCount("sweep"));
    }

    @Test
    void 并发热更新配额期间每个请求都得到一致结果且总量不超() throws InterruptedException {
        // 80 个请求在「limit=5/10/20」三套合法快照反复原子切换时竞争；
        // 无论观察到哪一版配置，已计数总数不得超过当时上限，且不允许抛异常/新旧额度混用。
        QuotaPolicy l5 = policy("live", 5, QuotaResetPeriod.DAY, "UTC");
        QuotaPolicy l10 = policy("live", 10, QuotaResetPeriod.DAY, "UTC");
        QuotaPolicy l20 = policy("live", 20, QuotaResetPeriod.DAY, "UTC");
        quotaManager.reconcile(Map.of("live", l5));

        int threads = 100;
        ExecutorService pool = Executors.newFixedThreadPool(20);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger errors = new AtomicInteger();
        AtomicInteger allowed = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            final boolean swapper = i % 10 == 0;
            pool.submit(() -> {
                try {
                    start.await();
                    if (swapper) {
                        QuotaPolicy picked = (allowed.get() % 3 == 0) ? l5 : (allowed.get() % 3 == 1 ? l10 : l20);
                        quotaManager.reconcile(Map.of("live", picked));
                    } else {
                        // 所有请求都以各自进入时拿到的策略对象尝试；管理器以对账后的当前上限为准
                        if (allow(quotaManager.acquire(l10, "alpha"))) {
                            allowed.incrementAndGet();
                        }
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                    log.warn("[用例:并发对账] 异常: {}", e.toString());
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        // 收敛到最小上限 5 后：已用计数可能来自 limit 较大阶段，但最终上限 5 下后续必须全部拒绝；
        quotaManager.reconcile(Map.of("live", l5));
        QuotaDecision afterStable = quotaManager.acquire(l5, "alpha");
        log.info("[用例:并发对账] 并发100(含10个热更新) 异常={}, 累计放行={}, 收敛limit=5后判定={}",
                errors.get(), allowed.get(), afterStable.scope());
        assertEquals(0, errors.get(), "热更新与请求并发期间不允许出现异常");
        assertTrue(allowed.get() <= 20, "任一时点都受当时上限约束，累计放行绝不可能超过曾配置的最大上限");
        assertEquals("QUOTA_EXHAUSTED", afterStable.scope(), "收敛到 limit=5 后绝不能再放行");
    }

    /** 可手动拨时的固定偏移时钟（线程内测试用）。 */
    private static final class MutableClock extends Clock {
        private volatile Instant instant;
        private final ZoneId zone;

        private MutableClock(Instant instant, ZoneId zone) {
            this.instant = instant;
            this.zone = zone;
        }

        private void setInstant(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new MutableClock(instant, zone);
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
