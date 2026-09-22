package com.github.xingjianluo.edgeapigatewayjava.proxy;

import com.github.xingjianluo.edgeapigatewayjava.common.GatewayConstants;
import com.github.xingjianluo.edgeapigatewayjava.config.ConfigStore;
import com.github.xingjianluo.edgeapigatewayjava.error.ErrorCode;
import com.github.xingjianluo.edgeapigatewayjava.error.GatewayException;
import com.github.xingjianluo.edgeapigatewayjava.ratelimit.RateLimitService;
import com.github.xingjianluo.edgeapigatewayjava.routing.RouteResolver;
import com.github.xingjianluo.edgeapigatewayjava.upstream.UpstreamForwarder;
import com.github.xingjianluo.edgeapigatewayjava.upstream.UpstreamResult;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 网关统一入口（除管理端外的所有路径）。
 *
 * <p>整条判定链在一次请求内使用同一份配置快照，处理顺序固定：
 * 读 body（有界）→ 路由 + 鉴权 → 限流/配额/去重 → 上游转发 → 归一化回写。
 * 每个环节都打印 输入 / 判定依据 / 归一化结果 三类日志。</p>
 */
@RestController
public class GatewayProxyController {

    private static final Logger log = LoggerFactory.getLogger(GatewayProxyController.class);

    private final ConfigStore configStore;
    private final RouteResolver routeResolver;
    private final RateLimitService rateLimitService;
    private final UpstreamForwarder upstreamForwarder;

    public GatewayProxyController(ConfigStore configStore,
                                  RouteResolver routeResolver,
                                  RateLimitService rateLimitService,
                                  UpstreamForwarder upstreamForwarder) {
        this.configStore = configStore;
        this.routeResolver = routeResolver;
        this.rateLimitService = rateLimitService;
        this.upstreamForwarder = upstreamForwarder;
    }

    @RequestMapping("/**")
    public ResponseEntity<byte[]> dispatch(HttpServletRequest request) {
        // 管理端路径由 AdminController 精确匹配处理，这里只兜底非管理端流量
        if (request.getRequestURI().startsWith(GatewayConstants.ADMIN_BASE + "/")
                || request.getRequestURI().equals(GatewayConstants.ADMIN_BASE)) {
            throw new GatewayException(ErrorCode.ROUTE_NOT_FOUND, "not a gateway proxy path");
        }

        byte[] body = readBoundedBody(request, configStore.current().getMaxBodyBytes());

        RouteResolver.Resolution resolution = routeResolver.resolve(request);

        RateLimitService.Decision decision = rateLimitService.check(
                resolution.route(), resolution.callerId(),
                resolution.requestId(), resolution.config().getDedupTtlMillis());
        switch (decision.verdict()) {
            case RATE_LIMITED -> throw new GatewayException(ErrorCode.RATE_LIMITED, decision.reason());
            case QUOTA_EXCEEDED -> throw new GatewayException(ErrorCode.QUOTA_EXCEEDED, decision.reason());
            case IDEMPOTENCY_CONFLICT ->
                    throw new GatewayException(ErrorCode.IDEMPOTENCY_CONFLICT, decision.reason());
            default -> {
            }
        }

        Map<String, String> forwardHeaders = collectHeaders(request);
        UpstreamForwarder.ForwardRequest fr = new UpstreamForwarder.ForwardRequest(
                resolution.route(),
                resolution.upstreamPath(),
                request.getMethod(),
                forwardHeaders,
                body,
                resolution.callerId(),
                resolution.requestId());

        UpstreamResult result = upstreamForwarder.forward(fr);
        if (!result.success()) {
            // 传输级失败：归一化为对外错误信封，绝不透传原始堆栈/连接细节
            ErrorCode code = ErrorCode.valueOf(result.normalizedError());
            throw new GatewayException(code, result.detail());
        }

        HttpHeaders responseHeaders = new HttpHeaders();
        result.headers().forEach(responseHeaders::add);
        // 明确标注这是被代理的上游响应，与网关自身错误可区分
        responseHeaders.add("X-Gateway", "edge-api-gateway");
        responseHeaders.add("X-Gateway-Route", resolution.route().getId());
        responseHeaders.add(GatewayConstants.REQUEST_ID_HEADER, resolution.requestId());

        log.info("requestId={} route={} caller={} -> proxied status={} bytes={}",
                resolution.requestId(), resolution.route().getId(), resolution.callerId(),
                result.status(), result.body() == null ? 0 : result.body().length);

        return ResponseEntity.status(result.status())
                .headers(responseHeaders)
                .body(result.body());
    }

    private byte[] readBoundedBody(HttpServletRequest request, int maxBytes) {
        int contentLength = request.getContentLength();
        if (contentLength > maxBytes) {
            throw new GatewayException(ErrorCode.PAYLOAD_TOO_LARGE,
                    "request body of declared " + contentLength + " bytes exceeds limit " + maxBytes);
        }
        try {
            byte[] bytes = request.getInputStream().readNBytes(maxBytes + 1);
            if (bytes.length > maxBytes) {
                throw new GatewayException(ErrorCode.PAYLOAD_TOO_LARGE,
                        "request body exceeds limit " + maxBytes + " bytes");
            }
            return bytes;
        } catch (IOException e) {
            throw new GatewayException(ErrorCode.INTERNAL_ERROR,
                    "failed to read request body: " + e.getMessage(), e);
        }
    }

    private Map<String, String> collectHeaders(HttpServletRequest request) {
        Map<String, String> headers = new LinkedHashMap<>();
        request.getHeaderNames().asIterator().forEachRemaining(name ->
                headers.put(name, request.getHeader(name)));
        return headers;
    }
}
