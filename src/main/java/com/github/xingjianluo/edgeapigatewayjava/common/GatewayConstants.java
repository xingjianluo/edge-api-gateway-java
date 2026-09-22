package com.github.xingjianluo.edgeapigatewayjava.common;

/** 网关共享常量（请求头名称、管理端路径前缀等）。 */
public final class GatewayConstants {

    private GatewayConstants() {
    }

    public static final String DEFAULT_CALLER_HEADER = "X-Caller-Id";
    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    public static final String ADMIN_BASE = "/__gateway/admin";
}
