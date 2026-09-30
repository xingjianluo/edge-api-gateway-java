package com.github.xingjianluo.edgeapigatewayjava.proxy;

/**
 * 网关对外错误码。所有上游异常都会归一化到这里的一个可区分值。
 */
public enum GatewayErrorCode {
    ROUTE_NOT_FOUND("无法匹配到任何路由规则"),
    ROUTE_AMBIGUOUS("多条同优先级路由规则冲突，配置自相矛盾"),
    CONFIG_INVALID("配置非法，热更新被拒绝"),
    CALLER_MISSING("缺少调用方标识头 X-Caller-Id"),
    CALLER_FORBIDDEN("调用方不在该路由的允许名单中"),
    RATE_LIMITED_GLOBAL("超出路由维度限流配额"),
    RATE_LIMITED_CALLER("超出调用方维度限流配额"),
    QUOTA_EXHAUSTED("超出调用方在共享周期配额内的总额度"),
    REQUEST_DUPLICATED("相同幂等键的请求已被处理，拒绝重复计数"),
    UPSTREAM_TIMEOUT("上游处理超时"),
    UPSTREAM_CONNECT_FAILED("上游连接失败"),
    UPSTREAM_BAD_RESPONSE("上游返回了无法解析的响应"),
    UPSTREAM_CIRCUIT_OPEN("上游处于熔断打开状态"),
    UPSTREAM_ERROR("上游返回 5xx 或其他服务端错误"),
    UPSTREAM_RETRIES_EXHAUSTED("幂等请求重试次数耗尽仍失败"),
    INTERNAL_ERROR("网关内部错误");

    private final String description;

    GatewayErrorCode(String description) {
        this.description = description;
    }

    public String description() {
        return description;
    }
}
