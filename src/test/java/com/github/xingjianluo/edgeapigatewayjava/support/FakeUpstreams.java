package com.github.xingjianluo.edgeapigatewayjava.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 测试用本机假上游集群：JDK 内置 HttpServer，随机端口，全程不依赖外部网络。
 *
 * <ul>
 *   <li>/echo 回显方法、路径、收到的请求头与请求体（用于验证逐跳头剥离与请求体透传）；</li>
 *   <li>/flaky 前 N 次返回 503，之后返回 200（验证幂等重试 / 非幂等不重试）；</li>
 *   <li>/slow 固定延迟（验证超时归一化）；</li>
 *   <li>/status/{code} 返回指定状态码；</li>
 *   <li>/count 统计每个路径被调用次数（验证非幂等请求只打一次）。</li>
 * </ul>
 */
public class FakeUpstreams {

    private final HttpServer server;
    private final AtomicInteger flakyFailuresRemaining;
    private final long slowDelayMillis;
    private final ConcurrentHashMap<String, AtomicInteger> hitCounters = new ConcurrentHashMap<>();
    private final java.util.List<String> receivedRequestHeaders = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    public FakeUpstreams(int flakyFailures, long slowDelayMillis) throws IOException {
        this.flakyFailuresRemaining = new AtomicInteger(flakyFailures);
        this.slowDelayMillis = slowDelayMillis;
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.server.createContext("/echo", this::echo);
        this.server.createContext("/flaky", this::flaky);
        this.server.createContext("/slow", this::slow);
        this.server.createContext("/status/", this::status);
        this.server.createContext("/count", this::count);
    }

    public void start() {
        server.start();
    }

    public void stop() {
        server.stop(0);
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public int hitCount(String path) {
        AtomicInteger counter = hitCounters.get(path);
        return counter == null ? 0 : counter.get();
    }

    public java.util.List<String> receivedHeadersSnapshot() {
        synchronized (receivedRequestHeaders) {
            return new java.util.ArrayList<>(receivedRequestHeaders);
        }
    }

    private void countHit(HttpExchange exchange) {
        String key = exchange.getRequestURI().getPath();
        hitCounters.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
    }

    private void echo(HttpExchange exchange) throws IOException {
        countHit(exchange);
        byte[] body = exchange.getRequestBody().readAllBytes();
        exchange.getRequestHeaders().forEach((name, values) ->
                values.forEach(v -> receivedRequestHeaders.add(name.toLowerCase() + "=" + v)));
        String headersJson = new java.util.ArrayList<>(exchange.getRequestHeaders().keySet()).stream()
                .map(h -> "\"" + h.replace("\"", "") + "\":\""
                        + exchange.getRequestHeaders().getFirst(h).replace("\"", "") + "\"")
                .reduce((a, b) -> a + "," + b).orElse("");
        String payload = "{\"method\":\"" + exchange.getRequestMethod()
                + "\",\"path\":\"" + exchange.getRequestURI().getPath()
                + "\",\"body\":\"" + new String(body, StandardCharsets.UTF_8).replace("\"", "")
                + "\",\"headers\":{" + headersJson + "}}";
        write(exchange, 200, payload);
    }

    private void flaky(HttpExchange exchange) throws IOException {
        countHit(exchange);
        exchange.getRequestBody().readAllBytes();
        if (flakyFailuresRemaining.getAndDecrement() > 0) {
            write(exchange, 503, "{\"error\":\"busy\"}");
        } else {
            write(exchange, 200, "{\"ok\":true}");
        }
    }

    private void slow(HttpExchange exchange) throws IOException {
        countHit(exchange);
        exchange.getRequestBody().readAllBytes();
        try {
            Thread.sleep(slowDelayMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        write(exchange, 200, "{\"ok\":true}");
    }

    private void status(HttpExchange exchange) throws IOException {
        countHit(exchange);
        exchange.getRequestBody().readAllBytes();
        String tail = exchange.getRequestURI().getPath().substring("/status/".length());
        int code;
        try {
            code = Integer.parseInt(tail);
        } catch (NumberFormatException e) {
            code = 200;
        }
        write(exchange, code, "{\"status\":" + code + "}");
    }

    private void count(HttpExchange exchange) throws IOException {
        countHit(exchange);
        write(exchange, 200, "{\"hits\":" + hitCounters.getOrDefault("/count", new AtomicInteger()).get() + "}");
    }

    private void write(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.getResponseHeaders().add("X-Upstream-Test", "fake");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
