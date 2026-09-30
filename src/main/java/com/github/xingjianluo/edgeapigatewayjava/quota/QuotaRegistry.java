package com.github.xingjianluo.edgeapigatewayjava.quota;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

import com.github.xingjianluo.edgeapigatewayjava.config.ConfigService;
import com.github.xingjianluo.edgeapigatewayjava.config.GatewayConfig;
import com.github.xingjianluo.edgeapigatewayjava.config.QuotaPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 周期配额的有状态注册表：按「策略 × 调用方 × 窗口」维护已用额度计数器。
 *
 * 语义保证：
 * - 同一策略覆盖多条路由，同一调用方在这些路由上的请求从同一份额度扣减；
 * - 检查+扣减在单个 synchronized 临界区内完成，超额拒绝不产生任何扣除，绝不只扣一半；
 * - 固定窗口按策略时区对齐，窗口切换自动换计数器，不会跨边界重复计数；
 * - 同一 X-Request-Id 在同一策略范围内只占用一次额度（网关内部重试由入口幂等保证，
 *   客户端重复提交在此再兜一层，均不重复计数）；
 * - 热更新只对账：策略定义（limit/period/timeZone/routeIds）未变则保留当前周期用量；
 *   策略被修改或删除则其计数器与幂等记录整体丢弃，杜绝新旧额度并存或改小后还能多用；
 * - 过期窗口计数器与陈旧幂等记录会被限额回收（每次最多扫描 boundedGcScan 个条目），
 *   历史调用方不会拖慢新请求或无限占用内存。
 */
@Component
public class QuotaRegistry {

    private static final Logger log = LoggerFactory.getLogger(QuotaRegistry.class);

    /** 触发一次 GC 的成功申请间隔；GC 每次最多扫描的条目数（保证单次开销有界）。 */
    private static final int GC_EVERY_N_ACQUIRES = 256;
    private static final int GC_SCAN_BUDGET = 2048;

    private final LongSupplier clock;
    private final Object lock = new Object();

    /** key = policyId + '|' + callerId。 */
    private final Map<String, CounterState> counters = new ConcurrentHashMap<>();
    /** key = policyId + '|' + callerId + '|' + requestId -> 该幂等键占用额度时所属窗口起点。 */
    private final Map<String, Long> chargedRequests = new ConcurrentHashMap<>();

    private volatile GatewayConfig lastSnapshot;
    private long acquireCounter;

    @Autowired
    public QuotaRegistry(ConfigService configService) {
        this(configService, System::currentTimeMillis);
    }

    /** 可注入时钟的构造器，主要用于精确测试窗口边界。 */
    public QuotaRegistry(ConfigService configService, LongSupplier clock) {
        this.clock = clock;
        this.lastSnapshot = configService.current();
        // 监听器顺序无依赖：RateLimiter 重建按秒桶，本注册表按新旧差异对账配额状态
        configService.addSwapListener(this::reconcile);
    }

    /**
     * 判断（策略 × 调用方 × X-Request-Id）是否已在当前窗口占用过额度。
     * 供入口在按秒桶扣减之前识别"跨覆盖路由的重复提交"，使其与路由级幂等去重一样得到 409，
     * 且完全不触碰任何额度。陈旧（上一窗口）记录视为未占用。
     */
    public boolean isAlreadyCharged(QuotaPolicy policy, String caller, String requestId) {
        if (requestId == null || requestId.isBlank()) {
            return false;
        }
        long windowStart = QuotaWindow.startEpochMillis(policy, clock.getAsLong());
        synchronized (lock) {
            Long chargedWindow = chargedRequests.get(policy.id() + "|" + caller + "|" + requestId);
            return chargedWindow != null && chargedWindow == windowStart;
        }
    }

    /**
     * 在策略内为调用方申请一个额度。调用方已保证非空且通过鉴权。
     * 本方法只使用自身内部锁，不与 RateLimiter 的锁嵌套（锁序单向：RateLimiter.lock -> 本锁）。
     */
    public QuotaResult acquire(QuotaPolicy policy, String caller, String requestId) {        long now = clock.getAsLong();
        long windowStart = QuotaWindow.startEpochMillis(policy, now);
        String counterKey = policy.id() + "|" + caller;
        boolean hasRequestId = requestId != null && !requestId.isBlank();
        String dedupeKey = hasRequestId ? counterKey + "|" + requestId : null;

        synchronized (lock) {
            CounterState state = counters.computeIfAbsent(counterKey, k -> new CounterState());
            long used = state.rollWindow(windowStart);
            if (hasRequestId) {
                Long chargedWindow = chargedRequests.get(dedupeKey);
                if (chargedWindow != null) {
                    if (chargedWindow == windowStart) {
                        // 同一请求（同策略+调用方+X-Request-Id）在同一窗口重复：不重复计数，直接放行
                        log.info("[quota] 幂等命中不重复扣减 policy={}, caller={}, requestId={}, window={}, used={}/{}",
                                policy.id(), caller, requestId, windowStart, used, policy.limit());
                        return QuotaResult.idempotentHit(used, policy.limit(), windowStart,
                                "相同 X-Request-Id 在本周期已占用过额度，重复请求不再计数");
                    }
                    // 来自上一窗口的陈旧记录：窗口已重置，旧记录失效，按新窗口的新请求处理（后续会被覆盖）
                    chargedRequests.remove(dedupeKey);
                }
            }

            if (used >= policy.limit()) {
                log.info("[quota] 拒绝: 周期额度用尽 policy={}, caller={}, window={}, used={}/{}",
                        policy.id(), caller, windowStart, used, policy.limit());
                return QuotaResult.rejected(
                        "调用方 " + caller + " 在配额 " + policy.id() + " 的当前周期内总额度 "
                                + policy.limit() + " 已用尽", used, policy.limit(), windowStart);
            }

            used = state.increment(windowStart);
            if (hasRequestId) {
                chargedRequests.put(dedupeKey, windowStart);
            }
            log.info("[quota] 扣减成功 policy={}, caller={}, requestId={}, window={}, used={}/{}",
                    policy.id(), caller, requestId, windowStart, used, policy.limit());
            maybeGc(now);
            return QuotaResult.charged(used, policy.limit(), windowStart);
        }
    }

    /** 仅供测试：清空全部状态。 */
    public void reset() {
        synchronized (lock) {
            int c = counters.size();
            int d = chargedRequests.size();
            counters.clear();
            chargedRequests.clear();
            log.info("[quota] 状态已重置: 计数器={}, 幂等记录={}", c, d);
        }
    }

    /** 配置快照切换后的对账。 */
    private void onConfigSwapped() {
        // ConfigService 的监听器无法直接拿到新快照，这里通过调用方传入；见 reconcile
    }

    /**
     * 按新快照对账配额状态：
     * - 新快照中不存在的策略：删除其全部计数器与幂等记录；
     * - 新快照中新增的策略，或仍存在但定义（limit/period/timeZone/routeIds）发生变化的策略：
     *   删除其全部状态，新窗口/新额度立即整体生效，绝不新旧并存；
     * - 定义完全不变的策略：保留当前周期已用额度（仅改超时/上游启停等无关配置时不清零）。
     */
    void reconcile(GatewayConfig snapshot) {
        synchronized (lock) {
            Map<String, QuotaPolicy> next = snapshot.quotaIndex();
            Map<String, QuotaPolicy> prev = lastSnapshot == null ? Map.of() : lastSnapshot.quotaIndex();
            List<String> dropped = new ArrayList<>();
            // 被删除的策略：清理其全部现存状态
            for (String policyId : prev.keySet()) {
                if (!next.containsKey(policyId)) {
                    dropPolicyState(policyId);
                    dropped.add(policyId + "(removed)");
                }
            }
            // 新增或定义发生变化的策略：旧状态（可能来自更早的同名策略或初始空快照前的操作）整体丢弃
            for (Map.Entry<String, QuotaPolicy> e : next.entrySet()) {
                QuotaPolicy oldPolicy = prev.get(e.getKey());
                if (oldPolicy == null) {
                    dropPolicyState(e.getKey());
                    dropped.add(e.getKey() + "(added)");
                } else if (!oldPolicy.equals(e.getValue())) {
                    dropPolicyState(e.getKey());
                    dropped.add(e.getKey() + "(changed)");
                }
            }
            lastSnapshot = snapshot;
            log.info("[quota] 配置已对账: 策略数={}, 丢弃状态={}", next.size(), dropped);
        }
    }

    private void dropPolicyState(String policyId) {
        counters.keySet().removeIf(k -> policyIdOf(k).equals(policyId));
        chargedRequests.keySet().removeIf(k -> policyIdOf(k).equals(policyId));
    }

    /** 计数器/幂等记录的 key 首段即策略 id。 */
    private static String policyIdOf(String key) {
        int i = key.indexOf('|');
        return i < 0 ? key : key.substring(0, i);
    }

    /** 周期性垃圾回收：删除不属于当前窗口的计数器与上一窗口及更早的幂等记录。有界扫描。 */
    private void maybeGc(long now) {
        if ((++acquireCounter % GC_EVERY_N_ACQUIRES) != 0) {
            return;
        }
        int scanned = 0;
        int removedCounters = 0;
        int removedDedup = 0;
        Iterator<Map.Entry<String, CounterState>> cit = counters.entrySet().iterator();
        while (cit.hasNext() && scanned < GC_SCAN_BUDGET) {
            Map.Entry<String, CounterState> e = cit.next();
            scanned++;
            QuotaPolicy p = currentPolicy(e.getKey());
            if (p == null) {
                continue;
            }
            long currentWindow = QuotaWindow.startEpochMillis(p, now);
            if (e.getValue().windowStart != currentWindow) {
                cit.remove();
                removedCounters++;
            }
        }
        int scanned2 = 0;
        Iterator<Map.Entry<String, Long>> dit = chargedRequests.entrySet().iterator();
        while (dit.hasNext() && scanned2 < GC_SCAN_BUDGET) {
            Map.Entry<String, Long> e = dit.next();
            scanned2++;
            QuotaPolicy p = currentPolicy(e.getKey());
            if (p == null) {
                continue;
            }
            long currentWindow = QuotaWindow.startEpochMillis(p, now);
            if (e.getValue() < currentWindow) {
                dit.remove();
                removedDedup++;
            }
        }
        if (removedCounters + removedDedup > 0) {
            log.info("[quota] 过期窗口回收: 计数器={}, 幂等记录={}, 剩余计数器={}, 剩余幂等={}",
                    removedCounters, removedDedup, counters.size(), chargedRequests.size());
        }
    }

    private QuotaPolicy currentPolicy(String compositeKey) {
        if (lastSnapshot == null) {
            return null;
        }
        return lastSnapshot.quotaIndex().get(policyIdOf(compositeKey));
    }

    /** 单调用方窗口计数器；所有字段只在 QuotaRegistry 锁内访问。 */
    private static final class CounterState {
        private long windowStart = Long.MIN_VALUE;
        private long used;

        /** 若窗口已滚动则归零并返回 0，否则返回当前已用量。 */
        long rollWindow(long currentWindowStart) {
            if (windowStart != currentWindowStart) {
                windowStart = currentWindowStart;
                used = 0;
            }
            return used;
        }

        long increment(long currentWindowStart) {
            rollWindow(currentWindowStart);
            return ++used;
        }
    }
}
