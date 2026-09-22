package com.github.xingjianluo.edgeapigatewayjava.config;

import com.github.xingjianluo.edgeapigatewayjava.error.GatewayException;
import com.github.xingjianluo.edgeapigatewayjava.model.GatewayConfig;
import com.github.xingjianluo.edgeapigatewayjava.model.RouteConfig;
import com.github.xingjianluo.edgeapigatewayjava.support.FakeUpstreams;
import com.github.xingjianluo.edgeapigatewayjava.support.GatewayClient;
import com.github.xingjianluo.edgeapigatewayjava.support.TestConfigs;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 配置热更新一致性测试：
 * <ul>
 *   <li>非法配置被拒绝，版本号不变，旧配置继续服务（无半生效）；</li>
 *   <li>合法配置原子发布后，新路由立即可用、被删除路由立即 404；</li>
 *   <li>大量并发请求与热更新同时进行：每个请求要么完整走旧配置要么走新配置，
 *       不允许出现 5xx/半生效。</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HotReloadTest {

    private static final Logger log = LoggerFactory.getLogger(HotReloadTest.class);

    @LocalServerPort
    private int port;

    @Autowired
    private ConfigStore configStore;

    private FakeUpstreams upstreamA;
    private FakeUpstreams upstreamB;
    private GatewayClient client;

    @BeforeEach
    void setUp() throws IOException {
        upstreamA = new FakeUpstreams(0, 0);
        upstreamB = new FakeUpstreams(0, 0);
        upstreamA.start();
        upstreamB.start();
        client = new GatewayClient(port);
    }

    @AfterEach
    void tearDown() {
        upstreamA.stop();
        upstreamB.stop();
    }

    @Test
    @DisplayName("非法新配置整体拒绝：版本不变，旧路由仍正常服务")
    void invalidReloadRejectedAtomically() {
        RouteConfig onlyA = TestConfigs.route("only-a", "GET", "/echo", upstreamA.baseUrl());
        onlyA.setStripPrefix("/echo");
        configStore.publishForTest(TestConfigs.config(onlyA));
        long versionBefore = configStore.version();

        // 构造自相矛盾配置：同一匹配签名两条规则
        RouteConfig dup1 = TestConfigs.route("dup", "GET", "/x", upstreamA.baseUrl());
        RouteConfig dup2 = TestConfigs.route("dup", "GET", "/x", upstreamB.baseUrl());
        GatewayConfig bad = TestConfigs.config(dup1, dup2);

        GatewayException ex = assertThrows(GatewayException.class,
                () -> configStore.publishForTest(bad));
        log.info("input=contradictory config basis=validator verdict={} reason='{}'",
                ex.errorCode(), ex.getMessage());

        assertEquals(versionBefore, configStore.version(), "version must not change on rejected config");

        GatewayClient.Response stillOk = client.send("GET", "/echo/echo", null, null);
        log.info("input=GET /echo/echo after rejected reload verdict status={} (expect 200)", stillOk.status());
        assertEquals(200, stillOk.status());
    }

    @Test
    @DisplayName("合法配置原子切换：旧路由下线 404，新路由上线 200，版本号递增")
    void validReloadSwitchesAtomically() {
        RouteConfig old = TestConfigs.withStripPrefix(
                TestConfigs.route("old", "GET", "/old", upstreamA.baseUrl()), "/old");
        configStore.publishForTest(TestConfigs.config(old));
        assertEquals(200, client.send("GET", "/old/echo", null, null).status());

        long v1 = configStore.version();
        configStore.publishForTest(TestConfigs.config(
                TestConfigs.withStripPrefix(
                        TestConfigs.route("new", "GET", "/new", upstreamB.baseUrl()), "/new")));
        long v2 = configStore.version();
        assertTrue(v2 > v1);

        assertEquals(404, client.send("GET", "/old/echo", null, null).status());
        assertEquals(200, client.send("GET", "/new/echo", null, null).status());
        log.info("input=reload swap old->new basis='volatile reference publish' verdict versions {}/{}, old=404 new=200",
                v1, v2);
    }

    @Test
    @DisplayName("热更新与 100 个并发请求同时进行：无任何 5xx，所有结果都可解释（200 或 404）")
    void concurrentRequestsDuringReloadNeverHalfApplied() throws Exception {
        configStore.publishForTest(TestConfigs.config(
                TestConfigs.route("svc", "GET", "/svc", upstreamA.baseUrl())));

        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger ok200 = new AtomicInteger();
        AtomicInteger notFound404 = new AtomicInteger();
        AtomicInteger other = new AtomicInteger();

        int requestCount = 100;
        CountDownLatch done = new CountDownLatch(requestCount);
        for (int i = 0; i < requestCount; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    int status = client.send("GET", "/svc/echo", null, null).status();
                    if (status == 200) {
                        ok200.incrementAndGet();
                    } else if (status == 404) {
                        notFound404.incrementAndGet();
                    } else {
                        other.incrementAndGet();
                    }
                } catch (Exception ignored) {
                    other.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        // 请求飞行途中反复切换配置（新旧配置都只有 /svc 的去留差异）
        for (int i = 0; i < 5; i++) {
            configStore.publishForTest(TestConfigs.config(
                    TestConfigs.withStripPrefix(
                            TestConfigs.route("svc", "GET", "/svc",
                                    (i % 2 == 0) ? upstreamA.baseUrl() : upstreamB.baseUrl()), "/svc")));
        }
        done.await();
        pool.shutdown();

        log.info("input=100 concurrent requests during 5 reloads verdict: 200={} 404={} other={}",
                ok200.get(), notFound404.get(), other.get());
        assertEquals(0, other.get(), "no 5xx / half-applied states allowed during reload");
        assertEquals(requestCount, ok200.get() + notFound404.get());
    }
}
