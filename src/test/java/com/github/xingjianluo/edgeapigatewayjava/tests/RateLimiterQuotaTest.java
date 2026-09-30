package com.github.xingjianluo.edgeapigatewayjava.tests;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import com.github.xingjianluo.edgeapigatewayjava.config.ConfigLoader;
import com.github.xingjianluo.edgeapigatewayjava.config.ConfigService;
import com.github.xingjianluo.edgeapigatewayjava.config.ConfigValidator;
import com.github.xingjianluo.edgeapigatewayjava.config.QuotaPolicy;
import com.github.xingjianluo.edgeapigatewayjava.config.RouteConfig;
import com.github.xingjianluo.edgeapigatewayjava.quota.QuotaRegistry;
import com.github.xingjianluo.edgeapigatewayjava.ratelimit.RateLimitDecision;
import com.github.xingjianluo.edgeapigatewayjava.ratelimit.RateLimiter;
import com.github.xingjianluo.edgeapigatewayjava.routing.RequestContext;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 按秒限流与周期配额联动的集成测试：
 * - 缺身份/越权/重复请求一律不进入配额扣减；
 * - 周期配额拒绝时按秒桶必须整体回滚（已拒绝却仍占额度绝不发生）；
 * - 周期配额拒绝原因 QUOTA_EXHAUSTED 可与按秒 RATE_LIMITED_* 区分；
 * - 未挂配额策略的路由行为与原先完全一致。
 */
class RateLimiterQuotaTest {

    private static final Logger log = LoggerFactory.getLogger(RateLimiterQuotaTest.class);

    private final ConfigService configService =
            new ConfigService(new ConfigLoader(), new ConfigValidator());
    private final AtomicLong clock = new AtomicLong(System.currentTimeMillis());
    private final QuotaRegistry quotaRegistry = new QuotaRegistry(configService, clock::get);
    private final RateLimiter rateLimiter = new RateLimiter(configService, quotaRegistry);

    private QuotaPolicy quota(long limit) {
        return new QuotaPolicy("q", List.of("r1", "r2"), limit, QuotaPolicy.PERIOD_HOUR, "UTC");
    }

    private RouteConfig route(String id, double callerRate, double globalRate, List<String> callers) {
        return new RouteConfig(id, "/" + id, List.of(), Map.of(), "u", null,
                10, 1000, 0, globalRate, callerRate, callers, "q");
    }

    private RequestContext ctx(String routePath, String caller, String requestId) {
        return new RequestContext("GET", routePath,
                caller == null ? Map.of() : Map.of("X-Caller-Id", List.of(caller)),
                caller, requestId, new byte[0]);
    }

    @Test
    void 缺身份越权与重复请求不消耗周期额度() {
        QuotaPolicy q = quota(1);
        RouteConfig r1 = route("r1", 0, 0, List.of("alpha"));

        RateLimitDecision missing = rateLimiter.check(r1, ctx("/r1", null, "m-1"), q);
        RateLimitDecision forbidden = rateLimiter.check(r1, ctx("/r1", "hacker", "h-1"), q);
        // alpha 用掉唯一额度
        RateLimitDecision ok = rateLimiter.check(r1, ctx("/r1", "alpha", "ok-1"), q);
        // 同键重复提交：409，且不额外消耗
        RateLimitDecision dup = rateLimiter.check(r1, ctx("/r1", "alpha", "ok-1"), q);
        // 额度已被唯一合法请求用尽
        RateLimitDecision exhausted = rateLimiter.check(r1, ctx("/r1", "alpha", "ok-2"), q);

        log.info("[用例:非法请求不占周期额度] 缺身份={} 越权={} 合法={} 重复={} 超额={}",
                missing.scope(), forbidden.scope(), ok.allowed(), dup.scope(), exhausted.scope());
        assertEquals("CALLER_MISSING", missing.scope());
        assertEquals("CALLER_FORBIDDEN", forbidden.scope());
        assertTrue(ok.allowed());
        assertEquals("REQUEST_DUPLICATED", dup.scope());
        assertEquals("QUOTA_EXHAUSTED", exhausted.scope(),
                "唯一额度必须只被合法请求占用，非法/重复请求都不能消耗它");
    }

    @Test
    void 周期配额拒绝时按秒桶整体回滚() {
        // 按秒速率给足（调用方/路由桶容量各 1000），周期额度仅 1：
        // 第1次成功（周期额度用掉唯一 1）；之后每次都在周期配额层被拒。
        // 关键判定：配额拒绝发生在两个按秒桶预占之后，若预占未精确回滚，连续几百次拒绝会
        // 持续抽空按秒桶（1000 容量），最终出现 RATE_LIMITED_*；而正确回滚时按秒桶始终不动，
        // 所有超额拒绝必须稳定停留在 QUOTA_EXHAUSTED，绝不发到上游。
        QuotaPolicy q = quota(1);
        RouteConfig r1 = route("r1", 1000, 1000, List.of());
        RateLimitDecision first = rateLimiter.check(r1, ctx("/r1", "alpha", "a1"), q);
        assertTrue(first.allowed());

        for (int i = 0; i < 1500; i++) {
            RateLimitDecision d = rateLimiter.check(r1, ctx("/r1", "alpha", "bulk-" + i), q);
            assertEquals("QUOTA_EXHAUSTED", d.scope(),
                    "第 " + i + " 个超额请求必须被周期配额拒绝；若按秒桶未回滚，会先出现按秒限流");
        }
        log.info("[用例:配额拒绝回滚按秒桶] 周期额度用尽后连续1500次请求全部稳定停在 QUOTA_EXHAUSTED，"
                + "证明每次按秒桶预占都被精确回滚");

        // 窗口之外无法在本用例推进时钟（注册表时钟独立），改为摘除配额视角确认按秒桶仍健康：
        RateLimitDecision noQuota = rateLimiter.check(r1, ctx("/r1", "alpha", "free"), null);
        assertTrue(noQuota.allowed(), "配额不再适用时，按秒桶容量应完好（回滚没有偷走令牌）");
    }

    @Test
    void 跨路由共享额度且拒绝原因可区分() {
        QuotaPolicy q = quota(2);
        RouteConfig r1 = route("r1", 1000, 1000, List.of());
        RouteConfig r2 = route("r2", 1000, 1000, List.of());
        RateLimitDecision a = rateLimiter.check(r1, ctx("/r1", "alpha", "x1"), q);
        RateLimitDecision b = rateLimiter.check(r2, ctx("/r2", "alpha", "x2"), q);
        RateLimitDecision c = rateLimiter.check(r1, ctx("/r1", "alpha", "x3"), q);
        log.info("[用例:跨路由共享+原因区分] r1第1次={} r2第2次={} r1第3次={}",
                a.allowed(), b.allowed(), c.scope());
        assertTrue(a.allowed() && b.allowed());
        assertEquals("QUOTA_EXHAUSTED", c.scope());
        assertFalse("RATE_LIMITED_CALLER".equals(c.scope())
                || "RATE_LIMITED_GLOBAL".equals(c.scope()),
                "周期配额超额必须与按秒限流的拒绝原因区分开");
    }

    @Test
    void 未挂配额的路由行为保持不变() {
        RouteConfig plain = new RouteConfig("plain", "/plain", List.of(), Map.of(), "u", null,
                10, 1000, 0, 0, 0, List.of(), null);
        for (int i = 0; i < 50; i++) {
            RateLimitDecision d = rateLimiter.check(plain, ctx("/plain", "anyone", "p-" + i), null);
            assertTrue(d.allowed(), "不参与配额的路由不应被周期配额影响: " + d.scope());
        }
        log.info("[用例:配额外路由不受影响] 50 次无配额路由请求全部放行");
    }
}
