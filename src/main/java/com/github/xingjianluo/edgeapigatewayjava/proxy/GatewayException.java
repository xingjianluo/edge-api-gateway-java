package com.github.xingjianluo.edgeapigatewayjava.proxy;

/**
 * 网关内部使用的可解释异常，携带错误码与判定依据。
 */
public class GatewayException extends RuntimeException {

    private final GatewayErrorCode code;
    private final String reason;

    public GatewayException(GatewayErrorCode code, String reason) {
        super(code.name() + ": " + reason);
        this.code = code;
        this.reason = reason;
    }

    public GatewayErrorCode code() {
        return code;
    }

    public String reason() {
        return reason;
    }
}
