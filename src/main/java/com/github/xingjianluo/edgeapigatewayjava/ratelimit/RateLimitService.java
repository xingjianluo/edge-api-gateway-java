package com.github.xingjianluo.edgeapigatewayjava.ratelimit;

import com.github.xingjianluo.edgeapigatewayjava.model.RateLimitConfig;
import com.github.xingjianluo.edgeapigatewayjava.model.RouteConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 按 路由 × 调用方 维度的限流与配额判定。
 *
 * <p>正确性要点：</p>
 * <ul>
 *   <li>固定窗口计数使用 {@link AtomicInteger#incrementAndGet()}，N 个许可证下最多放行 N 个请求，
 *       并发再高也不会超发；窗口过期后整体替换计数器。</li>
 *   <li>调用方之间使用独立 key，A 超额只拒绝 A，不影响其它调用方。</li>
 *   <li>日配额使用 {@link AtomicLong}，按 UTC 天独立计数。</li>
 *   <li>同一 requestId（X-Request-Id / Idempotency-Key）只判定一次：
 *       判定结果（含指纹 routeId+callerId）存入去重表，重试与并发重复请求直接复用，
 *       绝不重复计数；指纹不一致说明同号请求语义冲突，返回 {@code IDEMPOTENCY_CONFLICT}。</li>
 *   <li>拒绝不消耗任何计数：先判日配额（超限不累加窗口），再判窗口。</li>
 * </ul>
 */
@Component
public class RateLimitService {

    private static final Logger log = LoggerFactory.getLogger(RateLimitService.class);

    public enum Verdict {
        ALLOW, RATE_LIMITED, QUOTA_EXCEEDED, IDEMPOTENCY_CONFLICT
    }

    public record Decision(Verdict verdict, long remainingWindow, long remainingDaily, String reason) {
        public boolean allowed() {
            return verdict == Verdict.ALLOW;
        }
    }

    private record WindowKey(String routeId, String caller) {
    }

    private static final class Window {
        final long startedAtEpochMillis;
        final AtomicInteger used = new AtomicInteger(0);

        Window(long startedAtEpochMillis) {
            this.startedAtEpochMillis = startedAtEpochMillis;
        }
    }

    private static final class DailyCounter {
        final int dayUtc;
        final AtomicLong used = new AtomicLong(0);

        DailyCounter(int dayUtc) {
            this.dayUtc = dayUtc;
        }
    }

    private record DedupEntry(String fingerprint, Decision decision, long expiresAtEpochMillis) {

        boolean inProgress() {
            return decision == null;
        }

        boolean expired(long now) {
            return now > expiresAtEpochMillis;
        }
    }

    private final ConcurrentHashMap<WindowKey, Window> windows = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<WindowKey, DailyCounter> daily = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, DedupEntry> dedup = new ConcurrentHashMap<>();
    private volatile long lastSweepEpochMillis = System.currentTimeMillis();

    public Decision check(RouteConfig route, String callerId, String requestId, int dedupTtlMillis) {
        String caller = callerId == null ? "ANON" : callerId;
        RateLimitConfig rl = route.getRateLimit();
        String fingerprint = route.getId() + "|" + caller;
        long now = System.currentTimeMillis();

        if (requestId != null && !requestId.isBlank()) {
            // 先占位：putIfAbsent 保证同号请求只有一个成为判定者，其余等待复用，绝不重复计数。
            DedupEntry reservation = new DedupEntry(fingerprint, null, now + dedupTtlMillis);
            DedupEntry existing = dedup.putIfAbsent(requestId, reservation);
            if (existing != null) {
                if (!existing.fingerprint().equals(fingerprint)) {
                    // 同号请求落在不同 路由/调用方 上：语义冲突，拒绝。
                    // 仅当占位仍处于 in-progress 且已过期（占位者疑似僵死）时才允许接管
                    if (existing.inProgress() && existing.expired(now)) {
                        dedup.remove(requestId, existing);
                    } else {
                        log.warn("requestId={} route={} caller={} verdict=IDEMPOTENCY_CONFLICT basis='reused id with different route/caller'",
                                requestId, route.getId(), caller);
                        return new Decision(Verdict.IDEMPOTENCY_CONFLICT, -1, -1,
                                "request id '" + requestId + "' reused with different route/caller");
                    }
                } else {
                    Decision cached = awaitDecision(requestId, fingerprint, now + dedupTtlMillis);
                    if (cached != null) {
                        log.info("requestId={} route={} caller={} verdict={} basis='dedup hit, not re-counted'",
                                requestId, route.getId(), caller, cached.verdict());
                        return cached;
                    }
                    // 等待超时（占位者异常）：移除陈旧占位后自行判定
                    dedup.remove(requestId, existing);
                }
            }
            try {
                Decision decision = evaluate(route, caller, rl);
                dedup.put(requestId, new DedupEntry(fingerprint, decision, System.currentTimeMillis() + dedupTtlMillis));
                log.info("requestId={} route={} caller={} verdict={} basis='{}'",
                        requestId, route.getId(), caller, decision.verdict(), decision.reason());
                sweepMaybe();
                return decision;
            } catch (RuntimeException ex) {
                // 判定过程异常必须释放占位，否则同号请求会被永久阻塞
                dedup.remove(requestId, reservation);
                throw ex;
            }
        }

        Decision decision = evaluate(route, caller, rl);
        log.info("requestId=<anon> route={} caller={} verdict={} basis='{}'",
                route.getId(), caller, decision.verdict(), decision.reason());
        sweepMaybe();
        return decision;
    }

    private Decision evaluate(RouteConfig route, String caller, RateLimitConfig rl) {
        if (rl == null) {
            return new Decision(Verdict.ALLOW, Long.MAX_VALUE, Long.MAX_VALUE, "rate limit not configured");
        }
        WindowKey key = new WindowKey(route.getId(), caller);
        long now = System.currentTimeMillis();

        if (rl.getDailyQuota() > 0) {
            int today = Instant.now().atOffset(ZoneOffset.UTC).getDayOfYear();
            DailyCounter counter = daily.compute(key, (k, cur) ->
                    (cur == null || cur.dayUtc != today) ? new DailyCounter(today) : cur);
            long used = counter.used.incrementAndGet();
            if (used > rl.getDailyQuota()) {
                counter.used.decrementAndGet();
                return new Decision(Verdict.QUOTA_EXCEEDED, -1,
                        Math.max(0, rl.getDailyQuota() - (used - 1)),
                        "daily quota " + rl.getDailyQuota() + " exhausted for caller '" + caller
                                + "' on route '" + route.getId() + "'");
            }
        }

        if (rl.getPermitsPerWindow() > 0) {
            Window window = windows.compute(key, (k, cur) ->
                    (cur == null || now - cur.startedAtEpochMillis >= rl.getWindowMillis())
                            ? new Window(now) : cur);
            int used = window.used.incrementAndGet();
            if (used > rl.getPermitsPerWindow()) {
                return new Decision(Verdict.RATE_LIMITED, 0, remainingDaily(key, rl),
                        "rate limit " + rl.getPermitsPerWindow() + "/" + rl.getWindowMillis()
                                + "ms exceeded for caller '" + caller + "' on route '"
                                + route.getId() + "'");
            }
            return new Decision(Verdict.ALLOW,
                    Math.max(0, rl.getPermitsPerWindow() - used),
                    remainingDaily(key, rl),
                    "window usage " + used + "/" + rl.getPermitsPerWindow());
        }
        return new Decision(Verdict.ALLOW, Long.MAX_VALUE, remainingDaily(key, rl), "window disabled");
    }

    private long remainingDaily(WindowKey key, RateLimitConfig rl) {
        if (rl.getDailyQuota() <= 0) {
            return Long.MAX_VALUE;
        }
        DailyCounter counter = daily.get(key);
        long used = counter == null ? 0 : counter.used.get();
        return Math.max(0, rl.getDailyQuota() - used);
    }

    /**
     * 等待占位者完成判定并复用其结论；占位为 in-progress 且已过期（占位者疑似僵死）时返回 null。
     */
    private Decision awaitDecision(String requestId, String fingerprint, long deadline) {
        long waitDeadline = Math.min(deadline, System.currentTimeMillis() + 5000);
        while (System.currentTimeMillis() < waitDeadline) {
            DedupEntry entry = dedup.get(requestId);
            if (entry == null || !entry.fingerprint().equals(fingerprint)) {
                return null;
            }
            if (!entry.inProgress()) {
                return entry.decision();
            }
            Thread.onSpinWait();
        }
        return null;
    }

    private void sweepMaybe() {
        long now = System.currentTimeMillis();
        if (now - lastSweepEpochMillis < 1000) {
            return;
        }
        lastSweepEpochMillis = now;
        // 只回收过期的 in-progress 占位；已完成判定的缓存条目在各自 TTL 内必须保留，
        // 否则重试请求会被当成新请求再次计数。
        dedup.entrySet().removeIf(e -> e.getValue().inProgress() && e.getValue().expired(now));
    }

    /** 测试辅助：清空全部计数。 */
    public void resetForTest() {
        windows.clear();
        daily.clear();
        dedup.clear();
    }
}
