package com.github.xingjianluo.edgeapigatewayjava.ratelimit;

import com.github.xingjianluo.edgeapigatewayjava.config.RouteConfig;
import com.github.xingjianluo.edgeapigatewayjava.config.ConfigService;
import com.github.xingjianluo.edgeapigatewayjava.quota.QuotaDecision;
import com.github.xingjianluo.edgeapigatewayjava.quota.QuotaManager;
import com.github.xingjianluo.edgeapigatewayjava.quota.QuotaPolicy;
import com.github.xingjianluo.edgeapigatewayjava.routing.RequestContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按「路由维度」和「调用方维度」做令牌桶限流，以幂等键去重，并叠加按调用方共享的周期配额。
 *
 * 语义保证：
 * - 调用方由 X-Caller-Id 标识；缺失拒绝（CALLER_MISSING），不在路由白名单内拒绝（CALLER_FORBIDDEN），
 *   因此越权调用不会进入任何配额扣减；
 * - X-Request-Id 作为幂等键：同一键 + 同一调用方 + 同一路由只计数一次，
 *   重试/并发重复提交被拒绝（REQUEST_DUPLICATED），不重复扣桶、不重复扣周期配额；
 * - 调用方桶、路由桶、周期配额的"检查+扣减"在同一把锁内顺序完成，任一步失败即回滚此前扣减，
 *   要么全部成功要么全部不发生，杜绝并发超发、半扣减与"已拒绝却占用额度"；
 * - 周期配额按 QuotaPolicy 维度在多条路由间共享同一个调用方计数器，
 *   超额返回独立原因 QUOTA_EXHAUSTED，与按秒限流（RATE_LIMITED_*）可区分；
 * - 一个调用方超额只影响其自身的计数，其他调用方不受影响；
 * - 配置热更新会重建按秒桶与去重表；周期配额窗口由 {@link QuotaManager} 按策略身份对账，
 *   无关变更不清零、策略调整整体生效。
 */
@Component
public class RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);

    private final Object lock = new Object();
    private final Map<String, TokenBucket> globalBuckets = new ConcurrentHashMap<>();
    private final Map<String, TokenBucket> callerBuckets = new ConcurrentHashMap<>();
    /** key = routeId + '|' + callerId + '|' + requestId，记录已成功计数的请求指纹。 */
    private final Map<String, Fingerprint> seenRequests = new ConcurrentHashMap<>();
    private final QuotaManager quotaManager;

    public RateLimiter(ConfigService configService, QuotaManager quotaManager) {
        this.quotaManager = quotaManager;
        // 任意成功的配置热更新都重建按秒限流状态，保证新速率立即完整生效
        configService.addSwapListener(this::reset);
    }

    /**
     * 配置快照切换后重建按秒桶与幂等记录。
     * 按秒桶容量与当前配置强绑定，重建可避免旧速率泄漏到新配置（配额语义一致、无半生效）。
     * 周期配额窗口不在此清空：QuotaManager 另行按策略身份对账，无关热更新保留已用额度。
     */
    public void reset() {
        synchronized (lock) {
            int b = globalBuckets.size() + callerBuckets.size();
            globalBuckets.clear();
            callerBuckets.clear();
            seenRequests.clear();
            log.info("[ratelimit] 配置已切换，重建按秒限流状态，丢弃旧桶 {} 个、幂等记录（周期配额窗口保留）", b);
        }
    }

    public RateLimitDecision check(RouteConfig route, RequestContext request) {
        return check(route, request, null);
    }

    /**
     * @param quotaPolicy 该路由归属的周期配额策略；null 表示不参与周期配额
     */
    public RateLimitDecision check(RouteConfig route, RequestContext request, QuotaPolicy quotaPolicy) {
        boolean callerRequired = !route.allowedCallers().isEmpty()
                || route.callerRatePerSecond() > 0 || quotaPolicy != null;
        String rawCaller = request.callerId();
        if (callerRequired && (rawCaller == null || rawCaller.isBlank())) {
            log.info("[ratelimit] 拒绝: 缺少 X-Caller-Id, route={}, path={}", route.id(), request.path());
            return RateLimitDecision.reject("CALLER_MISSING", "请求缺少 X-Caller-Id 头");
        }
        String caller = (rawCaller == null || rawCaller.isBlank()) ? "anonymous" : rawCaller;
        if (!route.allowedCallers().isEmpty() && route.allowedCallers().stream()
                .noneMatch(c -> c.equalsIgnoreCase(caller))) {
            log.info("[ratelimit] 拒绝: caller={} 不在路由 {} 的白名单 {} 内",
                    caller, route.id(), route.allowedCallers());
            return RateLimitDecision.reject("CALLER_FORBIDDEN",
                    "调用方 " + caller + " 无权访问路由 " + route.id());
        }

        String dedupeKey = route.id() + "|" + caller + "|" + request.requestId();
        String fingerprint = fingerprint(request);

        synchronized (lock) {
            if (request.requestId() != null && !request.requestId().isBlank()) {
                Fingerprint prev = seenRequests.get(dedupeKey);
                if (prev != null) {
                    String dupReason = prev.fingerprint().equals(fingerprint)
                            ? "相同 X-Request-Id 的请求已成功计数，幂等拒绝重复提交"
                            : "X-Request-Id 已被其他内容的请求占用，拒绝歧义重试";
                    log.info("[ratelimit] 拒绝: REQUEST_DUPLICATED key={}, 同内容={}", dedupeKey,
                            prev.fingerprint().equals(fingerprint));
                    return RateLimitDecision.reject("REQUEST_DUPLICATED", dupReason);
                }
            }

            TokenBucket callerBucket = null;
            if (route.callerRatePerSecond() > 0) {
                callerBucket = callerBuckets.computeIfAbsent(
                        route.id() + "|" + caller,
                        k -> new TokenBucket(route.callerRatePerSecond()));
                if (!callerBucket.tryAcquire()) {
                    log.info("[ratelimit] 拒绝: caller 维度超发 route={}, caller={}, 剩余令牌={}",
                            route.id(), caller, callerBucket.availableTokens());
                    return RateLimitDecision.reject("RATE_LIMITED_CALLER",
                            "调用方 " + caller + " 在路由 " + route.id() + " 上超过每秒配额");
                }
            }

            TokenBucket globalBucket = null;
            if (route.globalRatePerSecond() > 0) {
                globalBucket = globalBuckets.computeIfAbsent(
                        route.id(), k -> new TokenBucket(route.globalRatePerSecond()));
                if (!globalBucket.tryAcquire()) {
                    // 调用方桶已扣，需要回滚，保证多个维度要么都成功要么都不发生
                    if (callerBucket != null) {
                        callerBucket.refillOneForRollback();
                    }
                    log.info("[ratelimit] 拒绝: 路由维度超发 route={}, 剩余令牌={}",
                            route.id(), globalBucket.availableTokens());
                    return RateLimitDecision.reject("RATE_LIMITED_GLOBAL",
                            "路由 " + route.id() + " 超过路由维度每秒配额");
                }
            }

            QuotaDecision quotaDecision = null;
            if (quotaPolicy != null) {
                // 周期配额的扣减同样处于本锁保护的原子区间内；
                // 其内部按「策略×调用方×窗口」再加窗口锁，锁顺序固定为本锁->窗口锁，无死锁环。
                quotaDecision = quotaManager.acquire(quotaPolicy, caller);
                if (!quotaDecision.allowed()) {
                    // 周期配额拒绝：此前已扣的按秒令牌必须全部回滚，绝不允许"拒绝了仍占额度"
                    if (callerBucket != null) {
                        callerBucket.refillOneForRollback();
                    }
                    if (globalBucket != null) {
                        globalBucket.refillOneForRollback();
                    }
                    log.info("[ratelimit] 拒绝: QUOTA_EXHAUSTED route={}, quotaId={}, caller={}, 已回滚按秒令牌",
                            route.id(), quotaPolicy.id(), caller);
                    return RateLimitDecision.reject(quotaDecision.scope(), quotaDecision.reason());
                }
            }

            if (request.requestId() != null && !request.requestId().isBlank()) {
                seenRequests.put(dedupeKey, new Fingerprint(fingerprint, System.currentTimeMillis()));
            }
            log.info("[ratelimit] 放行 route={}, caller={}, requestId={}, callerTokens={}, globalTokens={}, quota={}",
                    route.id(), caller, request.requestId(),
                    callerBucket == null ? "n/a" : String.format("%.2f", callerBucket.availableTokens()),
                    globalBucket == null ? "n/a" : String.format("%.2f", globalBucket.availableTokens()),
                    quotaPolicy == null ? "n/a" : quotaPolicy.id());
            return RateLimitDecision.allow();
        }
    }

    private static String fingerprint(RequestContext request) {
        return request.method() + " " + request.path() + " len=" + request.body().length;
    }

    private record Fingerprint(String fingerprint, long createdAtMillis) {
    }
}
