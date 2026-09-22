package com.github.xingjianluo.edgeapigatewayjava.ratelimit;

import com.github.xingjianluo.edgeapigatewayjava.config.RouteConfig;
import com.github.xingjianluo.edgeapigatewayjava.config.ConfigService;
import com.github.xingjianluo.edgeapigatewayjava.routing.RequestContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按「路由维度」和「调用方维度」做令牌桶限流，并以幂等键去重。
 *
 * 语义保证：
 * - 调用方由 X-Caller-Id 标识；缺失拒绝（CALLER_MISSING），不在路由白名单内拒绝（CALLER_FORBIDDEN），
 *   因此越权调用不会进入任何配额扣减；
 * - X-Request-Id 作为幂等键：同一键 + 同一调用方 + 同一路由只计数一次，
 *   重试/并发重复提交被拒绝（REQUEST_DUPLICATED），不重复扣桶；
 * - 调用方桶与路由桶的"检查+扣减"在同一把锁内顺序完成，要么都成功要么都不发生，
 *   杜绝并发超发与半扣减；
 * - 一个调用方超额只影响其自身的调用方桶，路由桶与其他调用方不受影响；
 * - 配置热更新会重建全部桶与去重表，避免旧配额容量泄漏到新配置。
 */
@Component
public class RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);

    private final Object lock = new Object();
    private final Map<String, TokenBucket> globalBuckets = new ConcurrentHashMap<>();
    private final Map<String, TokenBucket> callerBuckets = new ConcurrentHashMap<>();
    /** key = routeId + '|' + callerId + '|' + requestId，记录已成功计数的请求指纹。 */
    private final Map<String, Fingerprint> seenRequests = new ConcurrentHashMap<>();

    public RateLimiter(ConfigService configService) {
        // 任意成功的配置热更新都重建限流状态，保证新配额立即完整生效
        configService.addSwapListener(this::reset);
    }

    /**
     * 配置快照切换后重建全部桶与幂等记录。
     * 这样热更新不会把旧配置的桶容量带入新配置（配额语义一致、无半生效）。
     */
    public void reset() {
        synchronized (lock) {
            int b = globalBuckets.size() + callerBuckets.size();
            globalBuckets.clear();
            callerBuckets.clear();
            seenRequests.clear();
            log.info("[ratelimit] 配置已切换，重建限流状态，丢弃旧桶 {} 个、幂等记录", b);
        }
    }

    public RateLimitDecision check(RouteConfig route, RequestContext request) {
        boolean callerRequired = !route.allowedCallers().isEmpty() || route.callerRatePerSecond() > 0;
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
                    // 调用方桶已扣，需要回滚，保证两个维度要么都成功要么都不发生
                    if (callerBucket != null) {
                        callerBucket.refillOneForRollback();
                    }
                    log.info("[ratelimit] 拒绝: 路由维度超发 route={}, 剩余令牌={}",
                            route.id(), globalBucket.availableTokens());
                    return RateLimitDecision.reject("RATE_LIMITED_GLOBAL",
                            "路由 " + route.id() + " 超过路由维度每秒配额");
                }
            }

            if (request.requestId() != null && !request.requestId().isBlank()) {
                seenRequests.put(dedupeKey, new Fingerprint(fingerprint, System.currentTimeMillis()));
            }
            log.info("[ratelimit] 放行 route={}, caller={}, requestId={}, callerTokens={}, globalTokens={}",
                    route.id(), caller, request.requestId(),
                    callerBucket == null ? "n/a" : String.format("%.2f", callerBucket.availableTokens()),
                    globalBucket == null ? "n/a" : String.format("%.2f", globalBucket.availableTokens()));
            return RateLimitDecision.allow();
        }
    }

    private static String fingerprint(RequestContext request) {
        return request.method() + " " + request.path() + " len=" + request.body().length;
    }

    private record Fingerprint(String fingerprint, long createdAtMillis) {
    }
}
