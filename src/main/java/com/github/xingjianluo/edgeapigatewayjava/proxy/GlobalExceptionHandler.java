package com.github.xingjianluo.edgeapigatewayjava.proxy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 把所有异常归一化为统一结构：
 * { "error": { "code": "...", "message": "...", "httpStatus": 4xx/5xx }, "timestamp": "..." }
 *
 * 不同失败原因对应不同 code 与 HTTP 状态，调用方可据此区分处理。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(GatewayException.class)
    public ResponseEntity<Map<String, Object>> handleGateway(GatewayException ex) {
        int httpStatus = httpStatusOf(ex.code());
        log.info("[error] 归一化响应 code={}, httpStatus={}, message={}", ex.code(), httpStatus, ex.reason());
        return ResponseEntity.status(httpStatus)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body(ex.code().name(), ex.reason(), httpStatus));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadable(HttpMessageNotReadableException ex) {
        log.info("[error] 请求体无法解析: {}", ex.getMessage());
        return ResponseEntity.status(400)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body("BAD_REQUEST_BODY", "请求体格式非法或无法读取", 400));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleOther(Exception ex) {
        log.error("[error] 未处理异常，归一化为 INTERNAL_ERROR", ex);
        return ResponseEntity.status(500)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body(GatewayErrorCode.INTERNAL_ERROR.name(),
                        GatewayErrorCode.INTERNAL_ERROR.description() + ": " + ex.getClass().getSimpleName(), 500));
    }

    private Map<String, Object> body(String code, String message, int httpStatus) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("message", message);
        error.put("httpStatus", httpStatus);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("error", error);
        root.put("timestamp", Instant.now().toString());
        return root;
    }

    private int httpStatusOf(GatewayErrorCode code) {
        return switch (code) {
            case ROUTE_NOT_FOUND -> 404;
            case ROUTE_AMBIGUOUS, CONFIG_INVALID -> 500;
            case CALLER_MISSING -> 401;
            case CALLER_FORBIDDEN -> 403;
            case REQUEST_DUPLICATED -> 409;
            case RATE_LIMITED_GLOBAL, RATE_LIMITED_CALLER -> 429;
            case UPSTREAM_TIMEOUT, UPSTREAM_RETRIES_EXHAUSTED -> 504;
            case UPSTREAM_CIRCUIT_OPEN -> 503;
            case UPSTREAM_CONNECT_FAILED, UPSTREAM_BAD_RESPONSE, UPSTREAM_ERROR -> 502;
            case INTERNAL_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR.value();
        };
    }
}
