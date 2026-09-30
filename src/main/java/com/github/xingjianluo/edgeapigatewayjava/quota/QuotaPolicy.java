package com.github.xingjianluo.edgeapigatewayjava.quota;

/**
 * 一份「按调用方共享、可跨多条路由累计」的周期配额策略（不可变）。
 *
 * @param id            配额范围唯一标识；路由通过 quotaId 引用
 * @param limit         每个调用方每个周期的总额度（&gt;0）
 * @param resetPeriod   重置周期：按小时 / 按天 / 按周
 * @param timeZone      重置边界使用的时区（IANA，如 Asia/Shanghai）
 */
public record QuotaPolicy(String id, long limit, QuotaResetPeriod resetPeriod, String timeZone) {
}
