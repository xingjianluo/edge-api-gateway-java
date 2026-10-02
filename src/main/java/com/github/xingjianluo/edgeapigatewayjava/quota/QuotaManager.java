package com.github.xingjianluo.edgeapigatewayjava.quota;

import com.github.xingjianluo.edgeapigatewayjava.config.ConfigService;
import com.github.xingjianluo.edgeapigatewayjava.config.GatewayConfig;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoField;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 周期配额管理器。
 *
 * 维度：{@code 配额策略 × 调用方} 一份计数器，因此：
 * - 同一调用方命中属于同一配额范围（同 quotaId）的多条路由时，从同一份额度扣减；
 * - 不同调用方互不影响；未被任何配额覆盖的路由/调用方完全不走这里。
 *
 * 一致性保证：
 * - 窗口起点按策略时区归一化（整点/当天 0 点/周一 0 点），窗口切换时旧计数自然失效，
 *   绝不跨窗口重复计数；
 * - 「检查余量 + 扣减」在同一把窗口锁内完成，并发下总额度绝不超发；额度不足时零扣减，
 *   不会出现"已拒绝却仍占用额度"或"只扣一半"；
 * - 配置热更新时按策略身份对账（见 {@link #reconcile(Map)}）：仅改超时/上游启停等与配额
 *   无关的配置时当前窗口已用额度保留；周期/时区变化的策略视为新窗口重新计账，额度/范围
 *   调整对新请求整体生效（沿用同窗口已用量套新上限，改小后绝不能多用）；
 * - 后台守护线程定期回收过期窗口与已删除策略的状态，历史调用方不会无限占用内存，
 *   也不会拖慢新请求（热路径只做一次 O(1) 哈希定位）。
 */
@Component
public class QuotaManager {

    private static final Logger log = LoggerFactory.getLogger(QuotaManager.class);

    private final Clock clock;
    private final ConfigService configService;
    /** key = quotaId；每个策略一张按调用方分桶的表。 */
    private final Map<String, PolicyState> states = new ConcurrentHashMap<>();
    /** 回收间隔：30s 扫描一次，过期窗口/历史调用方不会无限占内存。 */
    private static final long SWEEP_INTERVAL_MILLIS = 30_000L;

    private volatile Thread sweeper;

    public QuotaManager() {
        this(Clock.systemUTC(), null);
    }

    public QuotaManager(Clock clock) {
        this(clock, null);
    }

    /** Spring 注入：订阅配置快照切换，按新快照对账配额状态。 */
    @org.springframework.beans.factory.annotation.Autowired
    public QuotaManager(ConfigService configService) {
        this(Clock.systemUTC(), configService);
    }

    private QuotaManager(Clock clock, ConfigService configService) {
        this.clock = clock;
        this.configService = configService;
        if (configService != null) {
            configService.addSwapListener(() -> reconcile(configService.current()));
        }
    }

    /**
     * 启动初始化：
     * - 先按「初始配置」做一次首次对账，保证服务按初始配置启动后配额立即生效。
     *   必须在这里而不能只依赖 swap 监听器：ConfigService 的初始加载发生在它自己的 @PostConstruct
     *   中、且不发送切换通知（此时部分监听器尚未注册），只靠监听器会导致初始配额从未建账，
     *   直到第一次热更新才生效。
     * - 与运行期热更新走同一条 {@link #reconcile(Map)} 路径，启动语义与热更新语义不分叉；
     *   该方法幂等且与请求热路径并发安全（putIfAbsent/retainAll 均为原子操作），
     *   即使初始对账与首批请求/热更新并发也不会漏计、重复计数。
     */
    @PostConstruct
    void start() {
        if (configService != null) {
            reconcile(configService.current());
            log.info("[quota] 初始配额策略已按启动配置建账: 策略数={}", configService.current().quotas().size());
        }
        Thread t = new Thread(this::sweepLoop, "gateway-quota-sweeper");
        t.setDaemon(true);
        sweeper = t;
        t.start();
        log.info("[quota] 过期窗口回收线程已启动, 间隔={}ms", SWEEP_INTERVAL_MILLIS);
    }

    /** 按新配置快照对账（订阅配置切换时使用）。 */
    public void reconcile(GatewayConfig config) {
        reconcile(config.quotas());
    }

    private void sweepLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                Thread.sleep(SWEEP_INTERVAL_MILLIS);
                evictExpired(clock.millis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.warn("[quota] 回收周期出现异常（不影响请求路径）: {}", e.getMessage());
            }
        }
    }

    /**
     * 配置切换后按新快照对账：
     * - 快照里已不存在的策略：整张表丢弃（旧额度不会与新额度并存）；
     * - 周期/时区发生变化的策略：旧窗口全部丢弃，按新重置规则开新窗口（杜绝边界重复计数）；
     * - 周期/时区不变、仅额度或覆盖路由变化的策略：保留各调用方当前窗口已用量，新上限立即生效；
     * - 新策略：建空表。
     */
    public void reconcile(Map<String, QuotaPolicy> policies) {
        // 先移除快照里已不存在的策略
        states.keySet().retainAll(policies.keySet());
        for (Map.Entry<String, QuotaPolicy> e : policies.entrySet()) {
            QuotaPolicy next = e.getValue();
            PolicyState existing = states.get(e.getKey());
            if (existing == null) {
                // putIfAbsent：与热路径并发时，不覆盖 acquire 刚刚建立的同策略空表（都是空表，语义等价）
                PolicyState created = states.putIfAbsent(e.getKey(), new PolicyState(next));
                if (created == null) {
                    log.info("[quota] 新增配额策略 id={}, limit={}/{}, tz={}",
                            next.id(), next.limit(), next.resetPeriod(), next.timeZone());
                }
            } else if (!existing.sameResetRule(next)) {
                states.put(e.getKey(), new PolicyState(next));
                log.info("[quota] 配额策略 {} 重置规则变化({}/{} -> {}/{})，旧窗口用量作废，整体重新计账",
                        next.id(), existing.policy.resetPeriod(), existing.policy.timeZone(),
                        next.resetPeriod(), next.timeZone());
            } else {
                existing.policy = next;
                log.info("[quota] 配额策略 {} 沿用当前窗口用量，新上限={} 立即生效", next.id(), next.limit());
            }
        }
    }

    /**
     * 原子地「检查 + 扣 1」。
     * 调用方必须已通过身份/越权/幂等去重检查，因此重复提交、越权、缺身份请求不会到这里。
     *
     * @return 放行（已占用 1 次额度）或 QUOTA_EXHAUSTED（零扣减）
     */
    public QuotaDecision acquire(QuotaPolicy policy, String caller) {
        long now = clock.millis();
        long windowStart = windowStartMillis(policy, now);
        PolicyState state = states.get(policy.id());
        if (state == null) {
            // 持有已删除策略旧快照的在途请求：不重建已下线策略的状态，也不阻塞，放行由其余流水线裁决
            log.warn("[quota] 策略 {} 不在当前快照中（可能在途请求持有旧快照），本次不做配额扣减", policy.id());
            return QuotaDecision.allow();
        }
        if (!state.sameResetRule(policy)) {
            // 重置规则已变更：旧快照请求绝不能向新窗口计数器写入，避免新旧额度混用、边界重复计数
            log.warn("[quota] 策略 {} 的重置规则已变更，旧快照在途请求本次不做配额扣减", policy.id());
            return QuotaDecision.allow();
        }
        CallerWindow window = state.windows.computeIfAbsent(
                caller, k -> new CallerWindow(windowStart, 0L));
        synchronized (window) {
            // 窗口边界：归一化起点不同即重置，旧计数绝不带入新窗口
            if (window.windowStartMillis != windowStart) {
                log.info("[quota] 窗口翻转 quotaId={}, caller={}, 旧窗口起点={} -> 新窗口起点={}, 已用量清零",
                        policy.id(), caller, window.windowStartMillis, windowStart);
                window.windowStartMillis = windowStart;
                window.used = 0L;
            }
            // 以当前对账后的策略上限为准（热更新改小额度立即整体生效）
            long effectiveLimit = state.policy.limit();
            if (window.used >= effectiveLimit) {
                log.info("[quota] 拒绝: QUOTA_EXHAUSTED quotaId={}, caller={}, 窗口起点={}, 已用={}, 上限={}",
                        policy.id(), caller, windowStart, window.used, effectiveLimit);
                return QuotaDecision.reject("调用方 " + caller + " 在配额范围 " + policy.id()
                        + " 的当前周期内总额度(" + effectiveLimit + ")已用尽");
            }
            window.used++;
            log.info("[quota] 放行 quotaId={}, caller={}, 窗口起点={}, 已用={}, 上限={}, 剩余={}",
                    policy.id(), caller, windowStart, window.used, effectiveLimit,
                    effectiveLimit - window.used);
            return QuotaDecision.allow();
        }
    }

    /**
     * 计算指定时刻所属周期窗口的起点（epochMillis）：
     * HOUR -> 该时区当前小时整点；DAY -> 当天 00:00；WEEK -> 当周一 00:00（ISO-8601）。
     */
    public long windowStartMillis(QuotaPolicy policy, long nowMillis) {
        ZoneId zone = ZoneId.of(policy.timeZone());
        ZonedDateTime z = Instant.ofEpochMilli(nowMillis).atZone(zone);
        ZonedDateTime start = switch (policy.resetPeriod()) {
            case HOUR -> z.truncatedTo(java.time.temporal.ChronoUnit.HOURS);
            case DAY -> z.toLocalDate().atStartOfDay(zone);
            case WEEK -> z.toLocalDate().with(ChronoField.DAY_OF_WEEK, DayOfWeek.MONDAY.getValue())
                    .atStartOfDay(zone);
        };
        return start.toInstant().toEpochMilli();
    }

    /** 回收过期窗口与已删除策略的状态。 */
    public void evictExpired(long nowMillis) {
        for (Map.Entry<String, PolicyState> entry : states.entrySet()) {
            QuotaPolicy p = entry.getValue().policy;
            entry.getValue().windows.entrySet().removeIf(w -> {
                boolean expired;
                synchronized (w.getValue()) {
                    // 锁内用最新时间重算当前窗口起点：并发请求可能恰好把窗口翻转到新窗口，
                    // 用进入锁前的旧快照判断会误删新计数。
                    long currentStart = windowStartMillis(p, clock.millis());
                    expired = w.getValue().windowStartMillis < currentStart;
                }
                if (expired) {
                    log.info("[quota] 回收过期窗口 quotaId={}, caller={}, 窗口起点={}",
                            p.id(), w.getKey(), w.getValue().windowStartMillis);
                }
                return expired;
            });
        }
    }

    /** 诊断/测试用：某策略下当前活跃调用方窗口数量。 */
    public int activeWindowCount(String policyId) {
        PolicyState state = states.get(policyId);
        return state == null ? 0 : state.windows.size();
    }

    /** 停止后台回收线程（测试或停机时调用）。 */
    @PreDestroy
    public void stop() {
        Thread t = sweeper;
        if (t != null) {
            t.interrupt();
            sweeper = null;
        }
    }

    /** 一份配额策略的全部运行时状态。 */
    private static final class PolicyState {
        private volatile QuotaPolicy policy;
        private final Map<String, CallerWindow> windows = new ConcurrentHashMap<>();

        private PolicyState(QuotaPolicy policy) {
            this.policy = policy;
        }

        private boolean sameResetRule(QuotaPolicy other) {
            return policy.resetPeriod() == other.resetPeriod()
                    && ZoneId.of(policy.timeZone()).equals(ZoneId.of(other.timeZone()));
        }
    }

    /** 单个调用方在单个窗口内的用量；所有变更在自身锁内完成。 */
    private static final class CallerWindow {
        private volatile long windowStartMillis;
        private long used;

        private CallerWindow(long windowStartMillis, long used) {
            this.windowStartMillis = windowStartMillis;
            this.used = used;
        }
    }
}
