package com.github.xingjianluo.edgeapigatewayjava.error;

/** 网关内部统一异常：携带归一化错误码与对外 HTTP 状态。 */
public class GatewayException extends RuntimeException {

    private final ErrorCode errorCode;
    private final int httpStatus;

    public GatewayException(ErrorCode errorCode, int httpStatus, String message) {
        super(message);
        this.errorCode = errorCode;
        this.httpStatus = httpStatus;
    }

    public GatewayException(ErrorCode errorCode, String message) {
        this(errorCode, errorCode.httpStatus(), message);
    }

    public GatewayException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.httpStatus = errorCode.httpStatus();
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    public int httpStatus() {
        return httpStatus;
    }
}
