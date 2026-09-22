package com.github.xingjianluo.edgeapigatewayjava.upstream;

import com.github.xingjianluo.edgeapigatewayjava.common.HopByHop;
import com.github.xingjianluo.edgeapigatewayjava.model.RouteConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 上游转发器。
 *
 * <p>重试语义（幂等与非幂等严格区分）：</p>
 * <ul>
 *   <li>幂等方法 GET/HEAD/OPTIONS/PUT/DELETE：连接失败、读超时、上游 502/503 时，
 *       按路由 {@code retries} 配置退避重试（默认 1 次额外尝试）；</li>
 *   <li>非幂等方法 POST/PATCH（及其它）：<b>绝不重试</b>，连接失败直接归一化为
 *       UPSTREAM_CONNECT_FAILED，超时归一化为 UPSTREAM_TIMEOUT，避免重复副作用；</li>
 *   <li>上游 4xx 一律不重试（请求本身有问题）；5xx 中仅 502/503 对幂等方法重试；</li>
 *   <li>POST/PATCH 的读超时无法确定上游是否已处理：不重试，返回 UPSTREAM_TIMEOUT，
 *       由调用方凭 Idempotency-Key 安全重试。</li>
 * </ul>
 *
 * <p>请求体一次性读入字节后复用（幂等重试时发送同一份内容）；逐跳头与 connection
 * 头声明的扩展头在请求/响应两个方向都被剥离；响应体有界读取（默认 10MB 上限保护内存）。</p>
 */
@Component
public class UpstreamForwarder {

    private static final Logger log = LoggerFactory.getLogger(UpstreamForwarder.class);

    private static final Set<String> IDEMPOTENT_METHODS =
            Set.of("GET", "HEAD", "OPTIONS", "PUT", "DELETE");
    private static final Set<Integer> RETRYABLE_STATUSES = Set.of(502, 503);
    private static final int MAX_RESPONSE_BYTES = 10 * 1024 * 1024;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NEVER)
            // 网关只做透传转发，不参与协议升级；关闭 h2c 以避免自动生成
            // Connection: Upgrade, HTTP2-Settings 等连接级头
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    public UpstreamResult forward(ForwardRequest fr) {
        boolean idempotent = IDEMPOTENT_METHODS.contains(fr.method().toUpperCase(Locale.ROOT));
        int attempts = idempotent ? Math.max(1, fr.route().getRetries() + 1) : 1;

        UpstreamResult lastFailure = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                HttpRequest request = buildRequest(fr);
                HttpResponse<byte[]> response = httpClient.send(request,
                        HttpResponse.BodyHandlers.ofByteArray());

                if (idempotent && attempt < attempts
                        && RETRYABLE_STATUSES.contains(response.statusCode())) {
                    log.info("requestId={} route={} attempt={} upstreamStatus={} -> retryable upstream status, retrying",
                            fr.requestId(), fr.route().getId(), attempt, response.statusCode());
                    lastFailure = UpstreamResult.failed("UPSTREAM_UNAVAILABLE",
                            "upstream returned " + response.statusCode());
                    backoff(attempt);
                    continue;
                }

                log.info("requestId={} route={} attempt={} -> upstreamStatus={} (proxied)",
                        fr.requestId(), fr.route().getId(), attempt, response.statusCode());
                return UpstreamResult.ok(response.statusCode(),
                        boundedBody(response), filterResponseHeaders(response.headers().map()));
            } catch (HttpTimeoutException e) {
                lastFailure = UpstreamResult.failed("UPSTREAM_TIMEOUT",
                        "upstream did not respond within " + fr.route().getTimeoutMillis() + "ms");
                log.warn("requestId={} route={} attempt={} -> UPSTREAM_TIMEOUT idempotent={}",
                        fr.requestId(), fr.route().getId(), attempt, idempotent);
            } catch (ConnectException e) {
                lastFailure = UpstreamResult.failed("UPSTREAM_CONNECT_FAILED",
                        "cannot connect to upstream " + fr.route().getUpstreamBaseUrl()
                                + ": " + e.getMessage());
                log.warn("requestId={} route={} attempt={} -> UPSTREAM_CONNECT_FAILED idempotent={}",
                        fr.requestId(), fr.route().getId(), attempt, idempotent);
            } catch (IOException e) {
                lastFailure = UpstreamResult.failed("UPSTREAM_UNAVAILABLE",
                        "upstream IO failure: " + e.getClass().getSimpleName() + " " + e.getMessage());
                log.warn("requestId={} route={} attempt={} -> UPSTREAM_UNAVAILABLE ({})",
                        fr.requestId(), fr.route().getId(), attempt, e.getClass().getSimpleName());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return UpstreamResult.failed("UPSTREAM_UNAVAILABLE", "interrupted while forwarding");
            }
            if (attempt < attempts) {
                backoff(attempt);
            }
        }
        log.warn("requestId={} route={} -> final upstream failure: {}",
                fr.requestId(), fr.route().getId(), lastFailure.normalizedError());
        return lastFailure;
    }

    private HttpRequest buildRequest(ForwardRequest fr) {
        URI uri = URI.create(fr.route().getUpstreamBaseUrl() + fr.upstreamPath());
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(Duration.ofMillis(Math.max(1, fr.route().getTimeoutMillis())));

        String method = fr.method().toUpperCase(Locale.ROOT);
        HttpRequest.BodyPublisher publisher = fr.body() == null || fr.body().length == 0
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(fr.body());
        builder.method(method, publisher);

        Set<String> connectionTokens = connectionTokens(fr.headers());
        // 即便客户端没在 Connection 中声明，这些协议升级令牌也属于连接级语义，
        // 网关不支持 Upgrade，一律不向上游透传
        connectionTokens.add("upgrade");
        connectionTokens.add("http2-settings");
        for (Map.Entry<String, String> e : fr.headers().entrySet()) {
            String name = e.getKey();
            String lowerName = name.toLowerCase(Locale.ROOT);
            if (HopByHop.shouldStrip(name) || connectionTokens.contains(lowerName)) {
                continue;
            }
            // Host 由 HttpClient 按目标 URI 重新生成；Content-Length 由发布者维护
            if (lowerName.equals("host") || lowerName.equals("content-length")) {
                continue;
            }
            builder.header(name, e.getValue());
        }
        return builder.build();
    }

    private Set<String> connectionTokens(Map<String, String> headers) {
        String connection = null;
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey().equalsIgnoreCase("connection")) {
                connection = e.getValue();
                break;
            }
        }
        if (connection == null || connection.isBlank()) {
            return new java.util.HashSet<>();
        }
        Set<String> tokens = new java.util.HashSet<>();
        for (String token : connection.split(",")) {
            tokens.add(token.trim().toLowerCase(Locale.ROOT));
        }
        return tokens;
    }

    private Map<String, String> filterResponseHeaders(Map<String, List<String>> raw) {
        Map<String, String> filtered = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : raw.entrySet()) {
            String name = e.getKey();
            if (name == null || HopByHop.shouldStrip(name)) {
                continue;
            }
            List<String> values = e.getValue();
            if (values != null && !values.isEmpty()) {
                filtered.put(name, String.join(",", values));
            }
        }
        return filtered;
    }

    private byte[] boundedBody(HttpResponse<byte[]> response) {
        byte[] body = response.body();
        if (body != null && body.length > MAX_RESPONSE_BYTES) {
            log.warn("upstream response body exceeded {} bytes, truncating", MAX_RESPONSE_BYTES);
            byte[] truncated = new byte[MAX_RESPONSE_BYTES];
            System.arraycopy(body, 0, truncated, 0, MAX_RESPONSE_BYTES);
            return truncated;
        }
        return body == null ? new byte[0] : body;
    }

    private void backoff(int attempt) {
        try {
            Thread.sleep(20L * attempt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public record ForwardRequest(
            RouteConfig route,
            String upstreamPath,
            String method,
            Map<String, String> headers,
            byte[] body,
            String callerId,
            String requestId) {
    }
}
