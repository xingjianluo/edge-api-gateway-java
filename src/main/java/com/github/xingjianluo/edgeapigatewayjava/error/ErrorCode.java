package com.github.xingjianluo.edgeapigatewayjava.error;

/**
 * 归一化后的对外错误码。每一种失败原因都对应唯一且稳定的 errorCode 与 HTTP 状态，
 * 调用方可据此区分：未匹配路由 / 方法不支持 / 头部条件不满足 / 越权 / 限流 / 配额 /
 * 上游连接失败 / 上游超时 / 上游不可用 / 上游非法响应 / 请求体超限 / 配置非法。
 */
public enum ErrorCode {

    ROUTE_NOT_FOUND(404),
    METHOD_NOT_ALLOWED(405),
    HEADER_CONDITION_FAILED(428),
    MISSING_CALLER(401),
    CALLER_FORBIDDEN(403),
    RATE_LIMITED(429),
    QUOTA_EXCEEDED(429),
    IDEMPOTENCY_CONFLICT(409),
    UPSTREAM_CONNECT_FAILED(502),
    UPSTREAM_TIMEOUT(504),
    UPSTREAM_UNAVAILABLE(502),
    UPSTREAM_BAD_GATEWAY(502),
    PAYLOAD_TOO_LARGE(413),
    CONFIG_INVALID(500),
    INTERNAL_ERROR(500);

    private final int httpStatus;

    ErrorCode(int httpStatus) {
        this.httpStatus = httpStatus;
    }

    public int httpStatus() {
        return httpStatus;
    }
}
