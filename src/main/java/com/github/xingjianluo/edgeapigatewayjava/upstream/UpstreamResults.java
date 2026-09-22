package com.github.xingjianluo.edgeapigatewayjava.upstream;

import com.github.xingjianluo.edgeapigatewayjava.proxy.GatewayErrorCode;

import java.util.List;
import java.util.Map;

/**
 * {@link UpstreamResult} 构造辅助。
 */
final class UpstreamResults {

    private UpstreamResults() {
    }

    static UpstreamResult success(int status, Map<String, List<String>> headers, byte[] body, int attempts) {
        return new UpstreamResult(true, status, headers, body == null ? new byte[0] : body,
                null, null, attempts);
    }

    static UpstreamResult failure(GatewayErrorCode code, int httpStatus, String message, int attempts) {
        return new UpstreamResult(false, httpStatus, Map.of(), new byte[0], code, message, attempts);
    }
}
