package com.github.xingjianluo.edgeapigatewayjava.tests;

import com.github.xingjianluo.edgeapigatewayjava.config.RouteConfig;
import com.github.xingjianluo.edgeapigatewayjava.config.UpstreamConfig;
import com.github.xingjianluo.edgeapigatewayjava.routing.RequestContext;
import com.github.xingjianluo.edgeapigatewayjava.upstream.CircuitBreakerRegistry;
import com.github.xingjianluo.edgeapigatewayjava.upstream.UpstreamDispatcher;
import com.github.xingjianluo.edgeapigatewayjava.upstream.UpstreamResult;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 上游异常链路单测。使用 JDK 内置 HttpServer 在 127.0.0.1 上模拟上游，
 * 完全离线、确定性可复现。
 */
class UpstreamDispatcherTest {

    private static final Logger log = LoggerFactory.getLogger(UpstreamDispatcherTest.class);

    private HttpServer server;
    private int port;
    private UpstreamDispatcher dispatcher;
    private CircuitBreakerRegistry circuits;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        server.start();
        circuits = new CircuitBreakerRegistry();
        dispatcher = new UpstreamDispatcher(circuits);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private UpstreamConfig upstream() {
        return new UpstreamConfig("test-up", "http://127.0.0.1:" + port, true);
    }

    private RouteConfig route(String method, int timeout, int retries) {
        return new RouteConfig("r", "/svc", List.of(), Map.of(), "test-up",
                "/up", 10, timeout, retries, 0, 0, List.of());
    }

    private RequestContext request(String method, String path, byte[] body,
                                   Map<String, List<String>> headers) {
        return new RequestContext(method, path, headers, null, null, body);
    }

    @Test
    void 成功时透传状态与请求体并剔除逐跳头() throws IOException {
        server.createContext("/up", exchange -> {
            byte[] resp = ("seen:" + new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)
                    + ":h=" + exchange.getRequestHeaders().getFirst("X-Custom")).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("X-Up", "yes");
            exchange.getResponseHeaders().add("Connection", "should-be-stripped");
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
        });

        var headers = Map.of(
                "X-Custom", List.of("v1"),
                "Connection", List.of("keep-alive"),
                "Transfer-Encoding", List.of("chunked"));
        UpstreamResult result = dispatcher.dispatch(upstream(),
                route("POST", 3000, 0), request("POST", "/svc/x", "hello".getBytes(), headers));

        log.info("[用例:成功透传] 输入=POST body=hello -> status={}, body={}, 逐跳头残留={}, attempts={}",
                result.statusCode(), new String(result.body()),
                result.headers().containsKey("Connection"), result.attempts());

        assertTrue(result.success());
        assertEquals(200, result.statusCode());
        assertEquals("seen:hello:h=v1", new String(result.body()));
        assertFalse(result.headers().containsKey("Connection"), "逐跳头必须被剔除");
    }

    @Test
    void 幂等GET在上游5xx时重试到成功() {
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/up", exchange -> {
            int n = calls.incrementAndGet();
            exchange.sendResponseHeaders(n <= 2 ? 503 : 200, 2);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write("ok".getBytes());
            }
        });

        UpstreamResult result = dispatcher.dispatch(upstream(),
                route("GET", 3000, 2), request("GET", "/svc", new byte[0], Map.of()));

        log.info("[用例:幂等重试] 输入=GET maxRetries=2 -> success={}, attempts={}",
                result.success(), result.attempts());

        assertTrue(result.success());
        assertEquals(3, result.attempts(), "前两次 503 应触发重试，第三次成功");
    }

    @Test
    void 非幂等POST绝不重试() {
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/up", exchange -> {
            calls.incrementAndGet();
            exchange.sendResponseHeaders(500, 0);
            exchange.close();
        });

        UpstreamResult result = dispatcher.dispatch(upstream(),
                route("POST", 3000, 5), request("POST", "/svc", "payload".getBytes(), Map.of()));

        log.info("[用例:非幂等不重试] 输入=POST maxRetries=5 -> success={}, code={}, 上游实际调用={}, attempts={}",
                result.success(), result.errorCode(), calls.get(), result.attempts());

        assertFalse(result.success());
        assertEquals(1, calls.get(), "非幂等请求即使配置了重试也只能调用上游一次");
        assertEquals(1, result.attempts());
        assertEquals(com.github.xingjianluo.edgeapigatewayjava.proxy.GatewayErrorCode.UPSTREAM_ERROR,
                result.errorCode());
    }

    @Test
    void 超时被归一化且重试耗尽() {
        server.createContext("/up", exchange -> {
            try {
                Thread.sleep(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });

        long start = System.currentTimeMillis();
        UpstreamResult result = dispatcher.dispatch(upstream(),
                route("GET", 300, 2), request("GET", "/svc", new byte[0], Map.of()));
        long elapsed = System.currentTimeMillis() - start;

        log.info("[用例:超时重试] 输入=GET timeout=300ms retries=2 -> code={}, attempts={}, 耗时={}ms",
                result.errorCode(), result.attempts(), elapsed);

        assertFalse(result.success());
        assertEquals(3, result.attempts());
        assertEquals(com.github.xingjianluo.edgeapigatewayjava.proxy.GatewayErrorCode.UPSTREAM_RETRIES_EXHAUSTED,
                result.errorCode());
        assertEquals(504, result.statusCode());
        assertTrue(elapsed < 3000, "3 次 300ms 超时应在约 1 秒内返回，实际 " + elapsed + "ms");
    }

    @Test
    void 连接失败被归一化为CONNECT_FAILED() {
        UpstreamConfig dead = new UpstreamConfig("dead", "http://127.0.0.1:1", true);

        UpstreamResult result = dispatcher.dispatch(dead,
                route("POST", 1000, 0), request("POST", "/svc", new byte[0], Map.of()));

        log.info("[用例:连接失败] 输入=POST -> 127.0.0.1:1 code={}", result.errorCode());

        assertFalse(result.success());
        assertEquals(com.github.xingjianluo.edgeapigatewayjava.proxy.GatewayErrorCode.UPSTREAM_CONNECT_FAILED,
                result.errorCode());
    }

    @Test
    void 连续失败触发熔断后快速失败() {
        server.createContext("/up", exchange -> {
            exchange.sendResponseHeaders(500, 0);
            exchange.close();
        });
        RouteConfig r = route("GET", 1000, 0);
        var req = request("GET", "/svc", new byte[0], Map.of());

        for (int i = 0; i < CircuitBreakerRegistry.FAILURE_THRESHOLD; i++) {
            dispatcher.dispatch(upstream(), r, req);
        }
        UpstreamResult fast = dispatcher.dispatch(upstream(), r, req);

        log.info("[用例:熔断] 连续{}次500后 -> code={}, attempts={}",
                CircuitBreakerRegistry.FAILURE_THRESHOLD, fast.errorCode(), fast.attempts());

        assertFalse(fast.success());
        assertEquals(com.github.xingjianluo.edgeapigatewayjava.proxy.GatewayErrorCode.UPSTREAM_CIRCUIT_OPEN,
                fast.errorCode());
        assertEquals(0, fast.attempts(), "熔断打开时请求绝不能发往上游");
    }

    @Test
    void 前缀重写正确拼接路径() throws IOException {
        server.createContext("/up", exchange -> {
            String uri = exchange.getRequestURI().toString();
            byte[] resp = uri.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
        });

        UpstreamResult result = dispatcher.dispatch(upstream(),
                route("GET", 1000, 0), request("GET", "/svc/orders/42", new byte[0], Map.of()));

        log.info("[用例:前缀重写] 输入=/svc/orders/42 rewrite=/up -> body={}", new String(result.body()));

        assertTrue(result.success());
        assertEquals("/up/orders/42", new String(result.body()));
    }

    @Test
    void 请求体在重试间被完整重放() {
        AtomicInteger bodies = new AtomicInteger();
        server.createContext("/up", exchange -> {
            byte[] b = exchange.getRequestBody().readAllBytes();
            bodies.addAndGet(b.length);
            int n = exchange.getRequestHeaders().getFirst("X-Gateway-Attempt") == null
                    ? 1 : Integer.parseInt(exchange.getRequestHeaders().getFirst("X-Gateway-Attempt"));
            exchange.sendResponseHeaders(n == 1 ? 503 : 200, 0);
            exchange.close();
        });

        byte[] payload = "replay-body".getBytes(StandardCharsets.UTF_8);
        UpstreamResult result = dispatcher.dispatch(upstream(),
                route("PUT", 2000, 1), request("PUT", "/svc/x", payload, Map.of()));

        log.info("[用例:请求体重放] 输入=PUT body={}字节, 两次尝试上游收到字节总数={}",
                payload.length, bodies.get());

        assertTrue(result.success());
        assertEquals(payload.length * 2, bodies.get(), "每次重试都必须完整重放缓冲的请求体");
        assertNull(result.errorCode());
        assertArrayEquals(new byte[0], result.body());
    }
}
