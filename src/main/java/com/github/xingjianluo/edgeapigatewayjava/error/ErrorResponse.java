package com.github.xingjianluo.edgeapigatewayjava.error;

/** 统一错误响应体，骨架占位。 */
public record ErrorResponse(
        String errorCode,
        int status,
        String message,
        String requestId,
        String routeId,
        String callerId) {
}
