package com.github.xingjianluo.edgeapigatewayjava.quota;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;

import com.github.xingjianluo.edgeapigatewayjava.config.QuotaPolicy;

/**
 * 固定周期窗口工具：把某个时刻归一对齐到所属窗口的起点（按策略时区）。
 * 同一窗口内的所有请求共用一份计数器；窗口切换即使用新计数器，天然不会跨边界重复计数。
 *
 * 对齐规则（均在策略时区的本地时间上）：
 * - HOUR：每小时整点（minute=second=nanos=0）；
 * - DAY ：每天 00:00；
 * - WEEK：ISO 周，每周一 00:00。
 */
public final class QuotaWindow {

    private QuotaWindow() {
    }

    /** 返回 nowMillis 时刻所属窗口的起点（epoch 毫秒）。 */
    public static long startEpochMillis(QuotaPolicy policy, long nowMillis) {
        ZoneId zone = ZoneId.of(policy.timeZone());
        ZonedDateTime zdt = Instant.ofEpochMilli(nowMillis).atZone(zone);
        ZonedDateTime start = switch (policy.period()) {
            case QuotaPolicy.PERIOD_HOUR -> zdt.truncatedTo(java.time.temporal.ChronoUnit.HOURS);
            case QuotaPolicy.PERIOD_DAY -> zdt.toLocalDate().atStartOfDay(zone);
            case QuotaPolicy.PERIOD_WEEK -> zdt.toLocalDate()
                    .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                    .atStartOfDay(zone);
            default -> throw new IllegalArgumentException("未知配额周期: " + policy.period());
        };
        return start.toInstant().toEpochMilli();
    }

    /** 返回当前窗口之后下一个窗口的起点（诊断/回收判断用）。 */
    public static long nextStartEpochMillis(QuotaPolicy policy, long windowStartMillis) {
        ZoneId zone = ZoneId.of(policy.timeZone());
        ZonedDateTime start = Instant.ofEpochMilli(windowStartMillis).atZone(zone);
        ZonedDateTime next = switch (policy.period()) {
            case QuotaPolicy.PERIOD_HOUR -> start.plusHours(1);
            case QuotaPolicy.PERIOD_DAY -> start.plusDays(1);
            case QuotaPolicy.PERIOD_WEEK -> start.plusWeeks(1);
            default -> throw new IllegalArgumentException("未知配额周期: " + policy.period());
        };
        return next.toInstant().toEpochMilli();
    }
}
