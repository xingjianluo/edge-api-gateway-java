package com.github.xingjianluo.edgeapigatewayjava.tests;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import com.github.xingjianluo.edgeapigatewayjava.config.QuotaPolicy;
import com.github.xingjianluo.edgeapigatewayjava.quota.QuotaWindow;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 周期窗口归一化测试：同一窗口内任意时刻映射到同一窗口起点；
 * 窗口按策略时区（而非 UTC）对齐，周窗口按 ISO 周一归一对齐。
 */
class QuotaWindowTest {

    private static final Logger log = LoggerFactory.getLogger(QuotaWindowTest.class);

    private QuotaPolicy policy(String period, String tz) {
        return new QuotaPolicy("p", List.of("r"), 10, period, tz);
    }

    private long millis(String utcIso) {
        return Instant.parse(utcIso).toEpochMilli();
    }

    @Test
    void 小时窗口在时区整点对齐() {
        QuotaPolicy p = policy(QuotaPolicy.PERIOD_HOUR, "Asia/Shanghai");
        // UTC 01:23 是上海 09:23 -> 窗口起点为上海 09:00 = UTC 01:00
        long w = QuotaWindow.startEpochMillis(p, millis("2026-09-30T01:23:45Z"));
        long expected = millis("2026-09-30T01:00:00Z");
        log.info("[用例:小时窗口] 输入=2026-09-30T01:23:45Z tz=Asia/Shanghai -> 窗口起点={}",
                Instant.ofEpochMilli(w));
        assertEquals(expected, w);
        // 同小时内另一时刻归一化结果必须一致
        assertEquals(w, QuotaWindow.startEpochMillis(p, millis("2026-09-30T01:59:59Z")));
        // 跨小时即换窗口
        assertEquals(millis("2026-09-30T02:00:00Z"),
                QuotaWindow.startEpochMillis(p, millis("2026-09-30T02:00:00Z")));
    }

    @Test
    void 天窗口按本地零点而非UTC零点对齐() {
        QuotaPolicy p = policy(QuotaPolicy.PERIOD_DAY, "Asia/Shanghai");
        // UTC 17:30 是上海次日 01:30（UTC+8），窗口起点应为上海当天 00:00 = UTC 前一天 16:00
        long w = QuotaWindow.startEpochMillis(p, millis("2026-09-30T17:30:00Z"));
        long expected = millis("2026-09-30T16:00:00Z");
        log.info("[用例:天窗口时区] 输入=2026-09-30T17:30:00Z tz=Asia/Shanghai -> 窗口起点={}",
                Instant.ofEpochMilli(w));
        assertEquals(expected, w);
    }

    @Test
    void 周窗口对齐到ISO周一本地零点() {
        QuotaPolicy p = policy(QuotaPolicy.PERIOD_WEEK, "UTC");
        // 2026-09-30 是周三；ISO 周起点为该周一 2026-09-28
        long wednesday = millis("2026-09-30T12:00:00Z");
        long monday = millis("2026-09-28T00:00:00Z");
        long w = QuotaWindow.startEpochMillis(p, wednesday);
        log.info("[用例:周窗口] 输入=2026-09-30(周三) -> 窗口起点={}", Instant.ofEpochMilli(w));
        assertEquals(monday, w);
        // 周一 00:00 本身即为窗口起点；周日仍属同一窗口
        assertEquals(monday, QuotaWindow.startEpochMillis(p, millis("2026-09-28T00:00:00Z")));
        assertEquals(monday, QuotaWindow.startEpochMillis(p, millis("2026-10-04T23:59:59Z")));
        // 下周一立即换新窗口
        assertEquals(millis("2026-10-05T00:00:00Z"),
                QuotaWindow.startEpochMillis(p, millis("2026-10-05T00:00:00Z")));
    }
}
