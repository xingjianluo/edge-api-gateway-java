package com.github.xingjianluo.edgeapigatewayjava.config;

import java.util.List;
import java.util.Map;

/**
 * 单条路由规则的不可变定义。
 *
 * @param id              规则唯一标识
 * @param pathPrefix      路径前缀匹配条件
 * @param methods         允许的 HTTP 方法；空表示任意方法
 * @param requiredHeaders 请求必须全部携带的头（名称 -> 期望值，null 表示只要求存在）
 * @param upstream        目标上游标识
 * @param prefixRewrite   转发前对路径做的前缀替换目标（null 表示保留原路径）
 * @param priority        显式优先级，数值越小越优先
 * @param timeoutMillis   上游单次调用超时
 * @param maxRetries      幂等请求的最大重试次数（0 表示不重试）
 * @param globalRatePerSecond   路由维度每秒令牌数（0 表示不限）
 * @param callerRatePerSecond   调用方维度每秒令牌数（0 表示不限）
 * @param allowedCallers  允许的调用方名单；空表示任意已认证调用方
 * @param quotaId         归属的周期配额范围 id（见 {@code GatewayConfig.quotas}）；
 *                        null 表示该路由不参与任何周期配额
 */
public record RouteConfig(
        String id,
        String pathPrefix,
        List<String> methods,
        Map<String, String> requiredHeaders,
        String upstream,
        String prefixRewrite,
        int priority,
        long timeoutMillis,
        int maxRetries,
        double globalRatePerSecond,
        double callerRatePerSecond,
        List<String> allowedCallers,
        String quotaId
) {
    public RouteConfig {
        methods = methods == null ? List.of() : List.copyOf(methods);
        requiredHeaders = requiredHeaders == null ? Map.of() : Map.copyOf(requiredHeaders);
        allowedCallers = allowedCallers == null ? List.of() : List.copyOf(allowedCallers);
    }

    /** 不归属任何周期配额的兼容构造器（quotaId=null）。 */
    public RouteConfig(String id, String pathPrefix, List<String> methods, Map<String, String> requiredHeaders,
                       String upstream, String prefixRewrite, int priority, long timeoutMillis, int maxRetries,
                       double globalRatePerSecond, double callerRatePerSecond, List<String> allowedCallers) {
        this(id, pathPrefix, methods, requiredHeaders, upstream, prefixRewrite, priority, timeoutMillis,
                maxRetries, globalRatePerSecond, callerRatePerSecond, allowedCallers, null);
    }
}
