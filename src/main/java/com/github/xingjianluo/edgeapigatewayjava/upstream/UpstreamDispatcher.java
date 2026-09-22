package com.github.xingjianluo.edgeapigatewayjava.upstream;

import com.github.xingjianluo.edgeapigatewayjava.config.RouteConfig;
import com.github.xingjianluo.edgeapigatewayjava.config.UpstreamConfig;
import com.github.xingjianluo.edgeapigatewayjava.proxy.GatewayErrorCode;
import com.github.xingjianluo.edgeapigatewayjava.proxy.GatewayException;
import com.github.xingjianluo.edgeapigatewayjava.routing.RequestContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 使用 JDK 内置 HttpClient 转发请求到本机上游。
 *
 * 重试语义：
 * - 仅幂等方法（GET/HEAD/OPTIONS/PUT/DELETE）允许重试，次数由路由 maxRetries 控制；
 * - 非幂等方法（POST/PATCH 等）最多尝试一次，失败即归一化返回，绝不错误重试；
 * - 请求体在进入网关时已完整缓冲，重试可以安全重放。
 *
 * 异常归一化（{@link GatewayErrorCode}）：
 * - 熔断打开           -> UPSTREAM_CIRCUIT_OPEN (503)
 * - 超时               -> UPSTREAM_TIMEOUT (504)，耗尽重试 -> UPSTREAM_RETRIES_EXHAUSTED (504)
 * - 连接失败           -> UPSTREAM_CONNECT_FAILED (502)，耗尽重试 -> UPSTREAM_RETRIES_EXHAUSTED
 * - 上游 5xx           -> UPSTREAM_ERROR (502)，耗尽重试 -> UPSTREAM_RETRIES_EXHAUSTED
 * - 响应不可解析       -> UPSTREAM_BAD_RESPONSE (502)
 * 上游 4xx 视为客户端错误，原样透传，不触发重试。
 */
@Component
public class UpstreamDispatcher {

    private static final Logger log = LoggerFactory.getLogger(UpstreamDispatcher.class);

    private static final Set<String> IDEMPOTENT_METHODS =
            Set.of("GET", "HEAD", "OPTIONS", "PUT", "DELETE");

    /** RFC 7230 6.1 逐跳头 + 可能引起内容长度/连接语义混乱的头，转发与回传时都剔除。 */
    private static final Set<String> HOP_BY_HOP = Set.of(
            "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
            "te", "trailer", "transfer-encoding", "upgrade",
            "host", "content-length", "x-application-context");

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    private final CircuitBreakerRegistry circuits;

    public UpstreamDispatcher(CircuitBreakerRegistry circuits) {
        this.circuits = circuits;
    }

    public UpstreamResult dispatch(UpstreamConfig upstream, RouteConfig route, RequestContext request) {
        if (!upstream.enabled()) {
            throw new GatewayException(GatewayErrorCode.INTERNAL_ERROR,
                    "上游 " + upstream.id() + " 已禁用，不应到达转发阶段");
        }
        if (circuits.isOpen(upstream.id())) {
            return UpstreamResults.failure(GatewayErrorCode.UPSTREAM_CIRCUIT_OPEN, 503,
                    "上游 " + upstream.id() + " 熔断打开中", 0);
        }

        boolean idempotent = IDEMPOTENT_METHODS.contains(request.method().toUpperCase(Locale.ROOT));
        int attemptsBudget = idempotent ? route.maxRetries() + 1 : 1;
        GatewayErrorCode lastCode = null;
        String lastMessage = null;
        int attempts = 0;

        while (attempts < attemptsBudget) {
            attempts++;
            String target = buildUrl(upstream, route, request);
            try {
                HttpRequest upstreamRequest = buildRequest(target, route, request, attempts);
                log.info("[upstream] 第{}次尝试 {} {} -> {} (幂等={}, 超时={}ms)",
                        attempts, request.method(), request.path(), target, idempotent, route.timeoutMillis());

                HttpResponse<byte[]> response = httpClient.send(
                        upstreamRequest, HttpResponse.BodyHandlers.ofByteArray());
                int status = response.statusCode();

                if (status >= 500) {
                    circuits.recordFailure(upstream.id());
                    lastCode = GatewayErrorCode.UPSTREAM_ERROR;
                    lastMessage = "上游返回状态码 " + status;
                    log.warn("[upstream] 上游 5xx status={} attempt={}/{}", status, attempts, attemptsBudget);
                    if (attempts < attemptsBudget) {
                        continue;
                    }
                    GatewayErrorCode finalCode = attemptsBudget > 1
                            ? GatewayErrorCode.UPSTREAM_RETRIES_EXHAUSTED : GatewayErrorCode.UPSTREAM_ERROR;
                    return UpstreamResults.failure(finalCode, attemptsBudget > 1 ? 504 : 502,
                            lastMessage + "，已尝试 " + attempts + " 次", attempts);
                }

                circuits.recordSuccess(upstream.id());
                log.info("[upstream] 成功 status={} attempt={}", status, attempts);
                return UpstreamResults.success(status, filterResponseHeaders(response), response.body(), attempts);

            } catch (HttpTimeoutException e) {
                circuits.recordFailure(upstream.id());
                lastCode = GatewayErrorCode.UPSTREAM_TIMEOUT;
                lastMessage = "上游在 " + route.timeoutMillis() + "ms 内未响应: " + target;
                log.warn("[upstream] 超时 attempt={}/{}: {}", attempts, attemptsBudget, lastMessage);
            } catch (java.net.ConnectException e) {
                circuits.recordFailure(upstream.id());
                lastCode = GatewayErrorCode.UPSTREAM_CONNECT_FAILED;
                lastMessage = "无法连接上游: " + target + " (" + e.getMessage() + ")";
                log.warn("[upstream] 连接失败 attempt={}/{}: {}", attempts, attemptsBudget, lastMessage);
            } catch (java.io.IOException e) {
                circuits.recordFailure(upstream.id());
                lastCode = GatewayErrorCode.UPSTREAM_BAD_RESPONSE;
                lastMessage = "上游 IO 异常: " + e.getClass().getSimpleName() + " " + e.getMessage();
                log.warn("[upstream] IO 异常 attempt={}/{}: {}", attempts, attemptsBudget, lastMessage);
            } catch (Exception e) {
                circuits.recordFailure(upstream.id());
                lastCode = GatewayErrorCode.UPSTREAM_BAD_RESPONSE;
                lastMessage = "上游响应无法处理: " + e.getClass().getSimpleName() + " " + e.getMessage();
                log.warn("[upstream] 未预期异常 attempt={}/{}: {}", attempts, attemptsBudget, lastMessage);
            }
            if (attempts < attemptsBudget) {
                continue;
            }
        }

        GatewayErrorCode exhausted = attemptsBudget > 1
                ? GatewayErrorCode.UPSTREAM_RETRIES_EXHAUSTED
                : lastCode;
        int httpStatus = (lastCode == GatewayErrorCode.UPSTREAM_TIMEOUT) ? 504 : 502;
        return UpstreamResults.failure(exhausted, httpStatus,
                (lastMessage == null ? "上游调用失败" : lastMessage) + "（非幂等不重试或重试已耗尽，共尝试 " + attempts + " 次）",
                attempts);
    }

    private String buildUrl(UpstreamConfig upstream, RouteConfig route, RequestContext request) {
        String base = upstream.baseUrl();
        String path = request.path();
        if (route.prefixRewrite() != null && !route.prefixRewrite().isEmpty()) {
            if (!path.startsWith(route.pathPrefix())) {
                throw new GatewayException(GatewayErrorCode.INTERNAL_ERROR,
                        "转发时路径 " + path + " 未以路由前缀 " + route.pathPrefix() + " 开头");
            }
            String suffix = path.substring(route.pathPrefix().length());
            path = route.prefixRewrite().equals("/") && suffix.startsWith("/")
                    ? suffix
                    : joinPath(route.prefixRewrite(), suffix);
        }
        return base.replaceAll("/+$", "") + path;
    }

    private static String joinPath(String prefix, String suffix) {
        if (prefix.endsWith("/") && suffix.startsWith("/")) {
            return prefix + suffix.substring(1);
        }
        if (!prefix.endsWith("/") && !suffix.startsWith("/") && !suffix.isEmpty()) {
            return prefix + "/" + suffix;
        }
        return prefix + suffix;
    }

    private HttpRequest buildRequest(String url, RouteConfig route, RequestContext request, int attempt) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMillis(route.timeoutMillis()));

        boolean bodyPresent = request.body() != null && request.body().length > 0;
        HttpRequest.BodyPublisher publisher = bodyPresent
                ? HttpRequest.BodyPublishers.ofByteArray(request.body())
                : HttpRequest.BodyPublishers.noBody();

        String method = request.method().toUpperCase(Locale.ROOT);
        builder.method(method, publisher);

        for (Map.Entry<String, List<String>> e : request.headers().entrySet()) {
            if (isHopByHop(e.getKey())) {
                continue;
            }
            for (String value : e.getValue()) {
                builder.header(e.getKey(), value);
            }
        }
        builder.header("X-Gateway-Route", route.id());
        builder.header("X-Gateway-Attempt", String.valueOf(attempt));
        return builder.build();
    }

    private Map<String, List<String>> filterResponseHeaders(HttpResponse<byte[]> response) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : response.headers().map().entrySet()) {
            if (isHopByHop(e.getKey())) {
                continue;
            }
            result.put(e.getKey(), new ArrayList<>(e.getValue()));
        }
        return result;
    }

    public static boolean isHopByHop(String headerName) {
        if (headerName == null) {
            return true;
        }
        return HOP_BY_HOP.contains(headerName.toLowerCase(Locale.ROOT));
    }
}
