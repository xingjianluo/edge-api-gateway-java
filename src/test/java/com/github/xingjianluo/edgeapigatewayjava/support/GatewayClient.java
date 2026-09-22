package com.github.xingjianluo.edgeapigatewayjava.support;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/** 测试用同步 HTTP 客户端，直连本机网关端口，零外部依赖。 */
public class GatewayClient {

    private final int port;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    public GatewayClient(int port) {
        this.port = port;
    }

    public record Response(int status, String body, Map<String, String> headers) {
    }

    public Response send(String method, String path, Map<String, String> headers, String body) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + port + path))
                    .timeout(Duration.ofSeconds(10));
            if (headers != null) {
                headers.forEach(builder::header);
            }
            HttpRequest.BodyPublisher publisher = body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body);
            builder.method(method, publisher);
            HttpResponse<String> response = httpClient.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString());
            java.util.Map<String, String> hs = new java.util.LinkedHashMap<>();
            response.headers().map().forEach((k, v) -> {
                if (!v.isEmpty()) {
                    hs.put(k, String.join(",", v));
                }
            });
            return new Response(response.statusCode(), response.body(), hs);
        } catch (Exception e) {
            throw new RuntimeException("request failed: " + method + " " + path, e);
        }
    }
}
