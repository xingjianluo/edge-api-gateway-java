package com.github.xingjianluo.edgeapigatewayjava.routing;

import com.github.xingjianluo.edgeapigatewayjava.common.GatewayConstants;
import com.github.xingjianluo.edgeapigatewayjava.config.ConfigStore;
import com.github.xingjianluo.edgeapigatewayjava.error.ErrorCode;
import com.github.xingjianluo.edgeapigatewayjava.error.GatewayException;
import com.github.xingjianluo.edgeapigatewayjava.error.GatewayExceptionHandler;
import com.github.xingjianluo.edgeapigatewayjava.model.GatewayConfig;
import com.github.xingjianluo.edgeapigatewayjava.model.RouteConfig;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 入口解析器：在请求开始时一次性快照当前配置（后续热更新不影响本请求的路由与目标），
 * 提取请求特征 -> 确定性路由 -> 调用方身份鉴权，并把 requestId/routeId/callerId
 * 写入 request 属性，供限流、转发与异常归一化共用，保证全链路可解释。
 */
@Component
public class RouteResolver {

    private static final Logger log = LoggerFactory.getLogger(RouteResolver.class);

    private final ConfigStore configStore;
    private final Router router;

    public RouteResolver(ConfigStore configStore, Router router) {
        this.configStore = configStore;
        this.router = router;
    }

    public record Resolution(
            GatewayConfig config,
            RouteConfig route,
            String upstreamPath,
            String callerId,
            String requestId) {
    }

    public Resolution resolve(HttpServletRequest request) {
        GatewayConfig snapshot = configStore.current();

        String requestId = request.getHeader(GatewayConstants.REQUEST_ID_HEADER);
        if (requestId == null || requestId.isBlank()) {
            requestId = request.getHeader(GatewayConstants.IDEMPOTENCY_KEY_HEADER);
        }
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString().replace("-", "");
        }
        request.setAttribute(GatewayExceptionHandler.ATTR_REQUEST_ID, requestId);

        Map<String, String> headers = readHeaders(request);
        RequestFeatures features = new RequestFeatures(
                request.getMethod(), request.getRequestURI(), headers);

        Router.MatchResult matched = router.match(snapshot, features);
        if (matched.failure() != Router.Failure.NONE) {
            ErrorCode code = switch (matched.failure()) {
                case ROUTE_NOT_FOUND -> ErrorCode.ROUTE_NOT_FOUND;
                case METHOD_NOT_ALLOWED -> ErrorCode.METHOD_NOT_ALLOWED;
                case HEADER_CONDITION_FAILED -> ErrorCode.HEADER_CONDITION_FAILED;
                default -> ErrorCode.ROUTE_NOT_FOUND;
            };
            log.info("requestId={} input='{} {}' verdict={} basis='{}'",
                    requestId, request.getMethod(), request.getRequestURI(),
                    code, matched.reason());
            throw new GatewayException(code, matched.reason());
        }

        RouteConfig route = matched.match().route();
        request.setAttribute(GatewayExceptionHandler.ATTR_ROUTE_ID, route.getId());

        String callerHeader = snapshot.getCallerHeader();
        String callerId = request.getHeader(callerHeader);
        if (callerId != null && callerId.isBlank()) {
            callerId = null;
        }

        if (route.isRequireCaller()) {
            if (callerId == null) {
                log.info("requestId={} route={} verdict=MISSING_CALLER basis='header {} absent'",
                        requestId, route.getId(), callerHeader);
                throw new GatewayException(ErrorCode.MISSING_CALLER,
                        "missing caller identity header: " + callerHeader);
            }
            List<String> allowed = route.getAllowedCallers();
            if (allowed != null && !allowed.isEmpty() && !allowed.contains(callerId)) {
                request.setAttribute(GatewayExceptionHandler.ATTR_CALLER_ID, callerId);
                log.info("requestId={} route={} caller={} verdict=CALLER_FORBIDDEN basis='not in allowlist'",
                        requestId, route.getId(), callerId);
                throw new GatewayException(ErrorCode.CALLER_FORBIDDEN,
                        "caller '" + callerId + "' is not allowed on route '" + route.getId() + "'");
            }
        }
        if (callerId != null) {
            request.setAttribute(GatewayExceptionHandler.ATTR_CALLER_ID, callerId);
        }

        log.info("requestId={} input='{} {}' caller={} verdict=ROUTE_MATCHED basis='route {} prefix={} headers={}'",
                requestId, request.getMethod(), request.getRequestURI(), callerId,
                route.getId(), route.getPathPrefix(), route.getRequiredHeaders().size());
        return new Resolution(snapshot, route, matched.match().upstreamPath(), callerId, requestId);
    }

    private Map<String, String> readHeaders(HttpServletRequest request) {
        Map<String, String> headers = new LinkedHashMap<>();
        List<String> names = Collections.list(request.getHeaderNames());
        for (String name : names) {
            headers.put(name.toLowerCase(Locale.ROOT), request.getHeader(name));
        }
        return headers;
    }
}
