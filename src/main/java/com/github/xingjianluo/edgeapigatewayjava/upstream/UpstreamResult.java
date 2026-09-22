package com.github.xingjianluo.edgeapigatewayjava.upstream;

import com.github.xingjianluo.edgeapigatewayjava.proxy.GatewayErrorCode;

import java.util.List;
import java.util.Map;

/**
 * 上游调用结果。成功时携带状态码/头/体；失败时携带归一化错误码与对外 HTTP 状态。
 */
public record UpstreamResult(
        boolean success,
        int statusCode,
        Map<String, List<String>> headers,
        byte[] body,
        GatewayErrorCode errorCode,
        String errorMessage,
        int attempts
) {
    public boolean failed() {
        return !success;
    }
}
