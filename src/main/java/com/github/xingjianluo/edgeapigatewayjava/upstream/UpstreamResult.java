package com.github.xingjianluo.edgeapigatewayjava.upstream;

import java.util.Map;

/**
 * 上游转发结果。
 *
 * @param success         是否拿到了上游的 HTTP 响应（含 4xx/5xx —— 那是上游的业务结果，需透传）
 * @param status          上游 HTTP 状态（成功时有效）
 * @param body            上游响应体
 * @param headers         已剥离逐跳头的上游响应头
 * @param normalizedError 归一化错误码（失败时有效，如 UPSTREAM_CONNECT_FAILED/UPSTREAM_TIMEOUT/...）
 * @param detail          可解释的判定细节
 */
public record UpstreamResult(
        boolean success,
        int status,
        byte[] body,
        Map<String, String> headers,
        String normalizedError,
        String detail) {

    public static UpstreamResult ok(int status, byte[] body, Map<String, String> headers) {
        return new UpstreamResult(true, status, body, headers, null, null);
    }

    public static UpstreamResult failed(String normalizedError, String detail) {
        return new UpstreamResult(false, 0, new byte[0], Map.of(), normalizedError, detail);
    }
}
