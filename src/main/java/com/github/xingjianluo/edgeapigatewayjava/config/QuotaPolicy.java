package com.github.xingjianluo.edgeapigatewayjava.config;

import java.util.List;

/**
 * 周期配额策略（不可变）。一份策略覆盖多条路由，按调用方共享同一额度，
 * 在固定窗口（小时/天/周）重置，窗口边界按时区对齐。
 *
 * @param id       策略唯一标识
 * @param routeIds 属于本配额范围的路由 id 列表；同一调用方命中其中任一路由都从同一份额度扣减
 * @param limit    每个周期内每个调用方的总额度（必须为正）
 * @param period   重置周期：HOUR / DAY / WEEK（WEEK 按 ISO 周，每周一 00:00 重置）
 * @param timeZone 窗口对齐时区，例如 Asia/Shanghai
 */
public record QuotaPolicy(String id, List<String> routeIds, long limit, String period, String timeZone) {

    public static final String PERIOD_HOUR = "HOUR";
    public static final String PERIOD_DAY = "DAY";
    public static final String PERIOD_WEEK = "WEEK";

    public QuotaPolicy {
        routeIds = routeIds == null ? List.of() : List.copyOf(routeIds);
    }
}
