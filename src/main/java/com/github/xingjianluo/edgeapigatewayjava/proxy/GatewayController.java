package com.github.xingjianluo.edgeapigatewayjava.proxy;

import com.github.xingjianluo.edgeapigatewayjava.config.ConfigService;
import com.github.xingjianluo.edgeapigatewayjava.config.GatewayConfig;
import com.github.xingjianluo.edgeapigatewayjava.config.QuotaPolicy;
import com.github.xingjianluo.edgeapigatewayjava.config.RouteConfig;
import com.github.xingjianluo.edgeapigatewayjava.config.UpstreamConfig;
import com.github.xingjianluo.edgeapigatewayjava.ratelimit.RateLimitDecision;
import com.github.xingjianluo.edgeapigatewayjava.ratelimit.RateLimiter;
import com.github.xingjianluo.edgeapigatewayjava.routing.RequestContext;
import com.github.xingjianluo.edgeapigatewayjava.routing.RouteMatchResult;
import com.github.xingjianluo.edgeapigatewayjava.routing.RouteMatcher;
import com.github.xingjianluo.edgeapigatewayjava.upstream.UpstreamDispatcher;
import com.github.xingjianluo.edgeapigatewayjava.upstream.UpstreamResult;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 网关统一入口。管理接口（/__admin、/actuator 等）不进入此控制器。
 *
 * 固定流水线，保证所有入口的判定顺序与结果一致：
 * 读取特征 -> 路由匹配 -> 调用方/限流/去重 -> 上游转发 -> 结果归一化。
 */
@RestController
public class GatewayController {

    private static final Logger log = LoggerFactory.getLogger(GatewayController.class);

    private final ConfigService configService;
    private final RouteMatcher routeMatcher;
    private final RateLimiter rateLimiter;
    private final UpstreamDispatcher dispatcher;

    public GatewayController(ConfigService configService, RouteMatcher routeMatcher,
                             RateLimiter rateLimiter, UpstreamDispatcher dispatcher) {
        this.configService = configService;
        this.routeMatcher = routeMatcher;
        this.rateLimiter = rateLimiter;
        this.dispatcher = dispatcher;
    }

    @RequestMapping(path = "/**")
    public ResponseEntity<byte[]> proxy(HttpServletRequest request,
                                        @RequestBody(required = false) byte[] body) throws Exception {
        String path = request.getRequestURI();
        if (path.startsWith("/__admin") || path.startsWith("/actuator")) {
            return ResponseEntity.status(404).build();
        }

        Map<String, List<String>> headers = collectHeaders(request);
        RequestContext context = new RequestContext(
                request.getMethod(),
                path,
                headers,
                header(headers, "X-Caller-Id"),
                header(headers, "X-Request-Id"),
                body == null ? new byte[0] : body
        );

        log.info("[gateway] 收到请求: {} {} caller={} requestId={} bodyBytes={}",
                context.method(), context.path(), context.callerId(), context.requestId(), context.body().length);

        // 1. 路由：取当前不可变快照，整次请求都基于同一快照
        GatewayConfig snapshot = configService.current();
        RouteMatchResult match = routeMatcher.match(snapshot, context);
        if (!match.hit()) {
            GatewayErrorCode code = match.failureReason() != null && match.failureReason().startsWith("AMBIGUOUS:")
                    ? GatewayErrorCode.ROUTE_AMBIGUOUS
                    : GatewayErrorCode.ROUTE_NOT_FOUND;
            int httpStatus = code == GatewayErrorCode.ROUTE_AMBIGUOUS ? 500 : 404;
            throw new GatewayException(code, match.failureReason());
        }
        RouteConfig route = match.route();

        // 2. 限流 / 调用方鉴权 / 幂等去重 / 周期配额（同一快照内解析配额策略）
        QuotaPolicy quota = route.quotaPolicyId() == null || route.quotaPolicyId().isBlank()
                ? null
                : snapshot.quotaIndex().get(route.quotaPolicyId());
        RateLimitDecision decision = rateLimiter.check(route, context, quota);
        if (!decision.allowed()) {
            throw rejection(decision.scope(), decision.reason(), route);
        }

        // 3. 上游状态再次确认（快照与最新配置一致时不会发生，属防御性检查）
        UpstreamConfig upstream = snapshot.upstream(route.upstream());
        if (upstream == null || !upstream.enabled()) {
            throw new GatewayException(GatewayErrorCode.ROUTE_NOT_FOUND,
                    "路由 " + route.id() + " 的目标上游 " + route.upstream() + " 不存在或已下线");
        }

        // 4. 转发
        UpstreamResult result = dispatcher.dispatch(upstream, route, context);

        // 5. 归一化输出
        if (result.failed()) {
            log.warn("[gateway] 归一化失败结果: code={}, httpStatus={}, message={}, attempts={}",
                    result.errorCode(), result.statusCode(), result.errorMessage(), result.attempts());
            throw new GatewayException(result.errorCode(), result.errorMessage());
        }

        HttpHeaders responseHeaders = new HttpHeaders();
        if (result.headers() != null) {
            result.headers().forEach((name, values) -> {
                if (!UpstreamDispatcher.isHopByHop(name)) {
                    responseHeaders.addAll(name, values);
                }
            });
        }
        responseHeaders.add("X-Gateway-Route", route.id());
        log.info("[gateway] 返回成功 status={} route={} attempts={}",
                result.statusCode(), route.id(), result.attempts());
        return ResponseEntity.status(result.statusCode())
                .headers(responseHeaders)
                .body(result.body());
    }

    private GatewayException rejection(String scope, String reason, RouteConfig route) {
        GatewayErrorCode code = switch (scope) {
            case "CALLER_MISSING" -> GatewayErrorCode.CALLER_MISSING;
            case "CALLER_FORBIDDEN" -> GatewayErrorCode.CALLER_FORBIDDEN;
            case "REQUEST_DUPLICATED" -> GatewayErrorCode.REQUEST_DUPLICATED;
            case "RATE_LIMITED_CALLER" -> GatewayErrorCode.RATE_LIMITED_CALLER;
            case "RATE_LIMITED_GLOBAL" -> GatewayErrorCode.RATE_LIMITED_GLOBAL;
            case "QUOTA_EXHAUSTED" -> GatewayErrorCode.QUOTA_EXHAUSTED;
            default -> GatewayErrorCode.INTERNAL_ERROR;
        };
        return new GatewayException(code, reason);
    }

    private static Map<String, List<String>> collectHeaders(HttpServletRequest request) {
        Map<String, List<String>> map = new LinkedHashMap<>();
        List<String> names = Collections.list(request.getHeaderNames());
        for (String name : names) {
            map.put(name, new ArrayList<>(Collections.list(request.getHeaders(name))));
        }
        return map;
    }

    private static String header(Map<String, List<String>> headers, String name) {
        for (Map.Entry<String, List<String>> e : headers.entrySet()) {
            if (e.getKey().equalsIgnoreCase(name) && !e.getValue().isEmpty()) {
                return e.getValue().get(0);
            }
        }
        return null;
    }
}
