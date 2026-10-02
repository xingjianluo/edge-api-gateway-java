package com.github.xingjianluo.edgeapigatewayjava.tests;

import com.github.xingjianluo.edgeapigatewayjava.config.ConfigLoader;
import com.github.xingjianluo.edgeapigatewayjava.config.ConfigService;
import com.github.xingjianluo.edgeapigatewayjava.config.ConfigValidator;
import com.github.xingjianluo.edgeapigatewayjava.quota.QuotaDecision;
import com.github.xingjianluo.edgeapigatewayjava.quota.QuotaManager;
import com.github.xingjianluo.edgeapigatewayjava.quota.QuotaPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 复现并锁定「按初始配置启动、期间不做任何热更新」路径上的周期配额装配。
 *
 * 手工按 Spring 的初始化顺序执行：
 *   ConfigService 构造 -> @PostConstruct init()（加载初始快照）
 *   -> QuotaManager 构造（订阅监听器）-> @PostConstruct start()
 * 全程不调用任何 replace/replaceFromJson（即没有任何热更新）。
 *
 * 修复前：QuotaManager.states 为空，acquire 直接放行，初始配置里的配额形同虚设，
 *        只有发生一次热更新（reconcile）后才正常。
 */
class QuotaStartupWiringTest {

    private static final Logger log = LoggerFactory.getLogger(QuotaStartupWiringTest.class);

    private ConfigService configService;
    private QuotaManager quotaManager;

    @AfterEach
    void tearDown() {
        if (quotaManager != null) {
            quotaManager.stop();
        }
        if (configService != null) {
            ReflectionTestUtils.invokeMethod(configService, "stop");
        }
    }

    /** 严格按 Spring 启动顺序装配：初始 JSON 走 gateway.config.path 文件加载，全程无热更新。 */
    private void bootstrap(String initialJson) throws Exception {
        Path initialFile = Files.createTempFile("gateway-initial-", ".json");
        Files.writeString(initialFile, initialJson);

        configService = new ConfigService(new ConfigLoader(), new ConfigValidator());
        // 等价于 --gateway.config.path=<初始配置文件>，init() 会从该文件加载初始快照
        ReflectionTestUtils.setField(configService, "configPath", initialFile.toString());
        ReflectionTestUtils.invokeMethod(configService, "init");

        quotaManager = new QuotaManager(configService);
        ReflectionTestUtils.invokeMethod(quotaManager, "start");
    }

    private static final String INITIAL_CONFIG = """
            {
              "upstreams":[{"id":"demo","baseUrl":"http://127.0.0.1:18080","enabled":true}],
              "quotas":[{"id":"shared-budget","limit":2,"resetPeriod":"DAY","timeZone":"UTC"}],
              "routes":[
                {"id":"v1-api","pathPrefix":"/api/v1","upstream":"demo",
                 "priority":20,"timeoutMillis":3000,"maxRetries":0,"quotaId":"shared-budget"},
                {"id":"v2-api","pathPrefix":"/api/v2","upstream":"demo",
                 "priority":30,"timeoutMillis":3000,"maxRetries":0,"quotaId":"shared-budget"}
              ]
            }
            """;

    private QuotaPolicy sharedBudget() {
        return configService.current().quotas().get("shared-budget");
    }

    @Test
    void 按初始配置启动后不做任何热更新配额立即生效并跨路由共享() throws Exception {
        bootstrap(INITIAL_CONFIG);

        // 同一调用方在两条共享同一 quotaId 的路由（对管理器即同一策略）上连续请求：
        // 启动即对账后必须 2 次放行、第 3 次起稳定拒绝——修复前这里会全部放行。
        assertTrue(quotaManager.acquire(sharedBudget(), "alpha").allowed(), "第1次（v1 路由）应放行");
        assertTrue(quotaManager.acquire(sharedBudget(), "alpha").allowed(), "第2次（v2 路由）应放行");
        QuotaDecision third = quotaManager.acquire(sharedBudget(), "alpha");
        QuotaDecision fourth = quotaManager.acquire(sharedBudget(), "alpha");

        log.info("[用例:启动即生效-跨路由] 初始配置 limit=2/天, 无热更新, alpha 连续4次 -> 前2次放行, 第3/4次={}/{}",
                third.scope(), fourth.scope());
        assertEquals("QUOTA_EXHAUSTED", third.scope(),
                "按初始配置启动后周期配额必须立即生效，无需任何热更新触发");
        assertEquals("QUOTA_EXHAUSTED", fourth.scope(), "超额后必须稳定拒绝，不能漏过任何一次");
    }

    @Test
    void 按初始配置启动后不同调用方额度相互隔离() throws Exception {
        bootstrap(INITIAL_CONFIG);

        assertTrue(quotaManager.acquire(sharedBudget(), "alpha").allowed());
        assertTrue(quotaManager.acquire(sharedBudget(), "alpha").allowed());
        assertEquals("QUOTA_EXHAUSTED", quotaManager.acquire(sharedBudget(), "alpha").scope());

        // 另一调用方必须拥有独立完整的初始额度
        QuotaDecision beta1 = quotaManager.acquire(sharedBudget(), "beta");
        QuotaDecision beta2 = quotaManager.acquire(sharedBudget(), "beta");
        QuotaDecision beta3 = quotaManager.acquire(sharedBudget(), "beta");
        log.info("[用例:启动即生效-调用方隔离] alpha 用尽后 beta 判定={},{},{}",
                beta1.allowed(), beta2.allowed(), beta3.scope());
        assertTrue(beta1.allowed() && beta2.allowed(), "启动路径上不同调用方额度必须相互隔离");
        assertEquals("QUOTA_EXHAUSTED", beta3.scope());
    }

    @Test
    void 按初始配置启动后的并发请求合计放行不超过总额度() throws Exception {
        bootstrap(INITIAL_CONFIG);

        int threads = 64;
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger allowed = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    if (quotaManager.acquire(sharedBudget(), "alpha").allowed()) {
                        allowed.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS));
        pool.shutdown();

        log.info("[用例:启动即生效-并发不超发] 初始配置 limit=2, 64 并发（无热更新）-> 放行={}, 拒绝={}",
                allowed.get(), threads - allowed.get());
        assertEquals(2, allowed.get(), "启动即生效后并发合计放行绝不能超过总额度");
        assertEquals("QUOTA_EXHAUSTED", quotaManager.acquire(sharedBudget(), "alpha").scope());
    }

    @Test
    void 初始配置无配额时启动对账不影响无配额请求() throws Exception {
        bootstrap("""
                {
                  "upstreams":[{"id":"demo","baseUrl":"http://127.0.0.1:18080","enabled":true}],
                  "routes":[
                    {"id":"open","pathPrefix":"/open","upstream":"demo",
                     "priority":10,"timeoutMillis":3000,"maxRetries":0}
                  ]
                }
                """);
        assertEquals(0, configService.current().quotas().size(), "无配额配置正常装载");
        assertEquals(0, quotaManager.activeWindowCount("shared-budget"));
    }
}
