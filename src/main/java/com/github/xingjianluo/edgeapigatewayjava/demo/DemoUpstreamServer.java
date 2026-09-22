package com.github.xingjianluo.edgeapigatewayjava.demo;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * demo profile 下的本机假上游服务（两个实例：稳定服务 + 故障服务）。
 * 使用 JDK 内置 HttpServer，零外部依赖、零外部网络，便于本机独立验收：
 * <ul>
 *   <li>{code /echo**} 回显方法/路径/头部/请求体；</li>
 *   <li>{code /flaky} 前两次返回 503，第三次起返回 200，用于验证幂等重试；</li>
 *   <li>{code /slow} 延迟 800ms，用于验证超时；</li>
 *   <li>{code /boom} 直接关闭连接，用于验证连接失败归一化。</li>
 * </ul>
 */
public class DemoUpstreamServer {

    private static final Logger log = LoggerFactory.getLogger(DemoUpstreamServer.class);

    private final int port;
    private final String name;
    private HttpServer server;
    private volatile int flakyHits = 0;

    public DemoUpstreamServer(int port, String name) {
        this.port = port;
        this.name = name;
    }

    public void start() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
            server.createContext("/echo", this::handleEcho);
            server.createContext("/flaky", this::handleFlaky);
            server.createContext("/slow", this::handleSlow);
            server.createContext("/boom", this::handleBoom);
            server.start();
            log.info("demo upstream '{}' listening on 127.0.0.1:{}", name, port);
        } catch (IOException e) {
            throw new IllegalStateException("failed to start demo upstream on port " + port, e);
        }
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    public int port() {
        return server == null ? port : server.getAddress().getPort();
    }

    private void handleEcho(HttpExchange exchange) throws IOException {
        byte[] requestBody = exchange.getRequestBody().readAllBytes();
        String seenCaller = exchange.getRequestHeaders().getFirst("X-Caller-Id");
        String payload = "{"
                + "\"upstream\":\"" + name + "\","
                + "\"method\":\"" + exchange.getRequestMethod() + "\","
                + "\"path\":\"" + exchange.getRequestURI().getPath() + "\","
                + "\"callerSeenByUpstream\":\"" + String.valueOf(seenCaller) + "\","
                + "\"bodyBytes\":" + requestBody.length
                + "}";
        writeJson(exchange, 200, payload);
    }

    private void handleFlaky(HttpExchange exchange) throws IOException {
        int hit = ++flakyHits;
        if (hit <= 2) {
            writeJson(exchange, 503, "{\"error\":\"temporarily unavailable\",\"hit\":" + hit + "}");
        } else {
            writeJson(exchange, 200, "{\"ok\":true,\"hit\":" + hit + "}");
        }
    }

    private void handleSlow(HttpExchange exchange) throws IOException {
        try {
            Thread.sleep(800);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        writeJson(exchange, 200, "{\"ok\":true}");
    }

    private void handleBoom(HttpExchange exchange) {
        // 直接关闭底层连接，模拟连接被上游重置
        exchange.close();
    }

    private void writeJson(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.getResponseHeaders().add("X-Upstream", name);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
