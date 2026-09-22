package com.github.xingjianluo.edgeapigatewayjava.config;

/**
 * 上游服务定义。
 *
 * @param id      上游标识
 * @param baseUrl 本机可达地址，例如 http://127.0.0.1:18081
 * @param enabled 是否启用；配置热更新为 false 后新请求不再路由到该目标
 */
public record UpstreamConfig(String id, String baseUrl, boolean enabled) {
}
