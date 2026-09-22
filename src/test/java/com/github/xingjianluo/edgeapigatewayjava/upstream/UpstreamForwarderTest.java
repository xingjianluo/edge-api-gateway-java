package com.github.xingjianluo.edgeapigatewayjava.upstream;

import com.github.xingjianluo.edgeapigatewayjava.model.RouteConfig;
import com.github.xingjianluo.edgeapigatewayjava.support.FakeUpstreams;
import com.github.xingjianluo.edgeapigatewayjava.support.TestConfigs;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 上游转发与异常归一化单测。 */
class UpstreamForwarderTest {

    private static final Logger log = LoggerFactory.getLogger(UpstreamForwarderTest.class);

    private FakeUpstreams upstream;
    private final UpstreamForwarder forwarder = new UpstreamForwarder();

    @BeforeEach
    void setUp() throws IOException {
        upstream = new FakeUpstreams(2, 800);
        upstream.start();
    }

    @AfterEach
    void tearDown() {
        upstream.stop();
    }

    private RouteConfig route(String prefix, int timeout, int retries) {
        RouteConfig route = TestConfigs.route("rt", "ANY", prefix, upstream.baseUrl());
        return TestConfigs.withTimeoutAndRetries(route, timeout, retries);
    }

    private UpstreamForwarder.ForwardRequest fr(RouteConfig route, String method, String path,
                                                Map<String, String> headers, String body) {
        return new UpstreamForwarder.ForwardRequest(
                route, path, method, headers,
                body == null ? new byte[0] : body.getBytes(),
                "caller", "test-req");
    }

    @Test
    @DisplayName("GET 幂等：前两次 503，第 3 次成功 -> 网关重试后返回 200，上游命中 3 次")
    void idempotentRetrySucceeds() {
        RouteConfig route = route("/flaky", 1000, 3);
        UpstreamResult result = forwarder.forward(fr(route, "GET", "/flaky", Map.of(), null));
        log.info("input=GET /flaky (2x503 then 200) basis='idempotent retries=3' verdict success={} status={} upstreamHits={}",
                result.success(), result.status(), upstream.hitCount("/flaky"));
        assertTrue(result.success());
        assertEquals(200, result.status());
        assertEquals(3, upstream.hitCount("/flaky"));
    }

    @Test
    @DisplayName("POST 非幂等：上游 503 时绝不重试，只打一次，直接透传 503")
    void nonIdempotentNoRetryOn503() {
        RouteConfig route = route("/flaky", 1000, 3);
        UpstreamResult result = forwarder.forward(fr(route, "POST", "/flaky", Map.of(), "payload"));
        log.info("input=POST /flaky basis='non-idempotent: never retry' verdict success={} status={} upstreamHits={}",
                result.success(), result.status(), upstream.hitCount("/flaky"));
        assertTrue(result.success(), "503 is a valid upstream HTTP response and must be proxied, not retried");
        assertEquals(503, result.status());
        assertEquals(1, upstream.hitCount("/flaky"));
    }

    @Test
    @DisplayName("POST 非幂等 + 慢上游：超时后不重试，归一化为 UPSTREAM_TIMEOUT，只打一次")
    void nonIdempotentTimeoutNoRetry() {
        RouteConfig route = route("/slow", 200, 3);
        UpstreamResult result = forwarder.forward(fr(route, "POST", "/slow", Map.of(), "x"));
        log.info("input=POST /slow basis='200ms timeout, non-idempotent no retry' verdict={} upstreamHits={}",
                result.normalizedError(), upstream.hitCount("/slow"));
        assertFalse(result.success());
        assertEquals("UPSTREAM_TIMEOUT", result.normalizedError());
        assertEquals(1, upstream.hitCount("/slow"));
    }

    @Test
    @DisplayName("GET 幂等 + 无监听端口：连接失败归一化为 UPSTREAM_CONNECT_FAILED")
    void connectFailureNormalized() throws IOException {
        int freePort;
        try (ServerSocket socket = new ServerSocket(0)) {
            freePort = socket.getLocalPort();
        }
        RouteConfig route = TestConfigs.withTimeoutAndRetries(
                TestConfigs.route("dead", "GET", "/d", "http://127.0.0.1:" + freePort), 500, 0);
        UpstreamResult result = forwarder.forward(fr(route, "GET", "/d", Map.of(), null));
        log.info("input=GET dead port basis='ConnectException' verdict={} detail='{}'",
                result.normalizedError(), result.detail());
        assertFalse(result.success());
        assertEquals("UPSTREAM_CONNECT_FAILED", result.normalizedError());
    }

    @Test
    @DisplayName("重试次数耗尽仍 503：幂等请求最终透传最后一次的 503（有响应即透传）")
    void retriesExhaustedStillProxiesResponse() {
        // 新建一个会一直失败的上游：失败次数设很大
        upstream.stop();
        try {
            upstream = new FakeUpstreams(100, 800);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        upstream.start();
        RouteConfig route = route("/flaky", 1000, 2);
        UpstreamResult result = forwarder.forward(fr(route, "GET", "/flaky", Map.of(), null));
        log.info("input=GET /flaky always-503 basis='3 attempts then proxy last response' verdict success={} status={} hits={}",
                result.success(), result.status(), upstream.hitCount("/flaky"));
        assertTrue(result.success());
        assertEquals(503, result.status());
        assertEquals(3, upstream.hitCount("/flaky"));
    }

    @Test
    @DisplayName("逐跳头与 connection 扩展头不转发；业务头与请求体原样透传")
    void hopByHopHeadersStrippedAndBodyForwarded() {
        RouteConfig route = route("/echo", 1000, 0);
        Map<String, String> headers = Map.of(
                "X-Business", "keep-me",
                "Connection", "X-To-Be-Stripped",
                "X-To-Be-Stripped", "should-not-arrive",
                "Transfer-Encoding", "chunked",
                "X-Caller-Id", "caller");
        UpstreamResult result = forwarder.forward(
                fr(route, "POST", "/echo/sub", headers, "hello-body"));
        String received = new String(result.body());
        log.info("input=POST /echo/sub with hop-by-hop headers basis='RFC7230 strip + connection tokens' verdict status={} body={}",
                result.status(), received);
        assertEquals(200, result.status());
        assertTrue(received.contains("hello-body"), "body must be forwarded intact");
        assertTrue(received.contains("keep-me"), "business header must survive");
        assertFalse(received.toLowerCase().contains("should-not-arrive"),
                "connection-token header must be stripped");
        assertFalse(received.contains("chunked"),
                "transfer-encoding hop-by-hop header must be stripped");
        // 响应方向：上游的逐跳/分帧头不得出现在转发结果里
        assertFalse(result.headers().keySet().stream().anyMatch(h -> h.equalsIgnoreCase("transfer-encoding")));
        assertTrue(result.headers().keySet().stream().anyMatch(h -> h.equalsIgnoreCase("X-Upstream-Test")));
    }

    @Test
    @DisplayName("4xx 不重试：GET /status/404 只打一次并透传 404")
    void clientErrorNotRetried() {
        RouteConfig route = route("/status/", 1000, 3);
        UpstreamResult result = forwarder.forward(fr(route, "GET", "/status/404", Map.of(), null));
        log.info("input=GET /status/404 basis='4xx never retried' verdict status={} hits={}",
                result.status(), upstream.hitCount("/status/404"));
        assertEquals(404, result.status());
        assertEquals(1, upstream.hitCount("/status/404"));
    }
}
