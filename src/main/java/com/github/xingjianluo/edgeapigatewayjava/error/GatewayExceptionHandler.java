package com.github.xingjianluo.edgeapigatewayjava.error;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常归一化：
 * <ul>
 *   <li>{@link GatewayException} —— 已归类的网关错误，按其 errorCode/status 输出；</li>
 *   <li>其它未预期异常 —— 统一归为 INTERNAL_ERROR，不向调用方泄漏内部细节。</li>
 * </ul>
 * 日志打印输入请求（方法/路径/requestId）、判定依据（错误码）与归一化结果（HTTP 状态）。
 */
@RestControllerAdvice
public class GatewayExceptionHandler {

    public static final String ATTR_REQUEST_ID = "edge.requestId";
    public static final String ATTR_ROUTE_ID = "edge.routeId";
    public static final String ATTR_CALLER_ID = "edge.callerId";

    private static final Logger log = LoggerFactory.getLogger(GatewayExceptionHandler.class);

    @ExceptionHandler(GatewayException.class)
    public ResponseEntity<ErrorResponse> handleGateway(GatewayException ex, HttpServletRequest request) {
        String requestId = str(request.getAttribute(ATTR_REQUEST_ID));
        String routeId = str(request.getAttribute(ATTR_ROUTE_ID));
        String callerId = str(request.getAttribute(ATTR_CALLER_ID));
        ErrorResponse body = new ErrorResponse(
                ex.errorCode().name(),
                ex.httpStatus(),
                ex.getMessage(),
                requestId,
                routeId,
                callerId);
        log.warn("request={} {} requestId={} routeId={} callerId={} -> verdict={} normalizedStatus={} reason='{}'",
                request.getMethod(), request.getRequestURI(), requestId, routeId, callerId,
                ex.errorCode().name(), ex.httpStatus(), ex.getMessage());
        return ResponseEntity.status(ex.httpStatus()).body(body);
    }

    @ExceptionHandler(Throwable.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Throwable ex, HttpServletRequest request) {
        String requestId = str(request.getAttribute(ATTR_REQUEST_ID));
        String routeId = str(request.getAttribute(ATTR_ROUTE_ID));
        String callerId = str(request.getAttribute(ATTR_CALLER_ID));
        log.error("request={} {} requestId={} routeId={} callerId={} -> verdict=INTERNAL_ERROR",
                request.getMethod(), request.getRequestURI(), requestId, routeId, callerId, ex);
        ErrorResponse body = new ErrorResponse(
                ErrorCode.INTERNAL_ERROR.name(),
                ErrorCode.INTERNAL_ERROR.httpStatus(),
                "internal gateway error",
                requestId,
                routeId,
                callerId);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }

    private static String str(Object value) {
        return value == null ? null : value.toString();
    }
}
