package com.github.xingjianluo.edgeapigatewayjava.config;

import com.github.xingjianluo.edgeapigatewayjava.proxy.GatewayErrorCode;
import com.github.xingjianluo.edgeapigatewayjava.proxy.GatewayException;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 持有当前生效的不可变 {@link GatewayConfig} 快照。
 *
 * 一致性保证：
 * - 任何替换都先解析+校验，全部通过后才原子切换引用，杜绝半生效；
 * - 校验失败时旧配置继续生效并抛出 CONFIG_INVALID；
 * - 进行中的请求持有旧快照引用，可以执行完成；新请求只会看到完整的新配置；
 * - 可选地轮询配置文件（gateway.config.path），检测到变更后热更新。
 */
@Component
public class ConfigService {

    private static final Logger log = LoggerFactory.getLogger(ConfigService.class);
    private static final String CLASSPATH_DEFAULT = "gateway-routes.json";

    @Value("${gateway.config.path:}")
    private String configPath;

    private final ConfigLoader loader;
    private final ConfigValidator validator;
    private final AtomicReference<GatewayConfig> current = new AtomicReference<>();
    private final List<Runnable> swapListeners = new java.util.concurrent.CopyOnWriteArrayList<>();

    private volatile Path watchedFile;
    private volatile long watchedLastModified = -1;
    private volatile Thread watcher;

    public ConfigService(ConfigLoader loader, ConfigValidator validator) {
        this.loader = loader;
        this.validator = validator;
        this.current.set(new GatewayConfig(List.of(), java.util.Map.of()));
    }

    @PostConstruct
    public void init() {
        try {
            InitialSource source = resolveInitialSource();
            GatewayConfig cfg = parseAndValidate(source.json());
            current.set(cfg);
            log.info("[config] 初始配置已加载: routes={}, upstreams={}, 来源={}",
                    cfg.routes().size(), cfg.upstreams().size(), source.description());
            if (watchedFile != null) {
                startFileWatcher();
            }
        } catch (GatewayException e) {
            throw e;
        } catch (Exception e) {
            throw new GatewayException(GatewayErrorCode.CONFIG_INVALID, "初始配置加载失败: " + e.getMessage());
        }
    }

    public GatewayConfig current() {
        return current.get();
    }

    /** 用 JSON 文本原子替换配置；解析或校验失败则旧配置保持不变。 */
    public GatewayConfig replaceFromJson(String json) {
        GatewayConfig next = parseAndValidate(json);
        current.set(next);
        notifySwapped();
        log.info("[config] 配置已原子热更新: routes={}, upstreams={}", next.routes().size(), next.upstreams().size());
        return next;
    }

    /** 仅供测试/内部使用：直接放入已构造的配置（仍会校验）。 */
    public void replace(GatewayConfig next) {
        ensureValid(next);
        current.set(next);
        notifySwapped();
    }

    /**
     * 注册"配置快照已成功切换"监听器。监听器在引用原子切换之后执行；
     * 校验失败的更新不会触发监听器。
     */
    public void addSwapListener(Runnable listener) {
        swapListeners.add(listener);
    }

    private void notifySwapped() {
        for (Runnable l : swapListeners) {
            try {
                l.run();
            } catch (Exception e) {
                log.warn("[config] 配置切换监听器执行失败: {}", e.getMessage());
            }
        }
    }

    public GatewayConfig parseAndValidate(String json) {
        GatewayConfig next = loader.load(json);
        ensureValid(next);
        return next;
    }

    private void ensureValid(GatewayConfig next) {
        List<String> errors = validator.validate(next);
        if (!errors.isEmpty()) {
            log.warn("[config] 配置校验失败，拒绝更新: {}", errors);
            throw new GatewayException(GatewayErrorCode.CONFIG_INVALID, String.join("; ", errors));
        }
    }

    private InitialSource resolveInitialSource() throws IOException {
        if (configPath != null && !configPath.isBlank()) {
            Path p = Path.of(configPath);
            if (Files.isRegularFile(p)) {
                watchedFile = p;
                watchedLastModified = Files.getLastModifiedTime(p).toMillis();
                return new InitialSource(Files.readString(p, StandardCharsets.UTF_8), "file:" + p);
            }
            // 允许通过 classpath 指定
            ClassPathResource cp = new ClassPathResource(configPath);
            if (cp.exists()) {
                return new InitialSource(readClasspath(cp), "classpath:" + configPath);
            }
            throw new GatewayException(GatewayErrorCode.CONFIG_INVALID, "找不到配置文件: " + configPath);
        }
        ClassPathResource cp = new ClassPathResource(CLASSPATH_DEFAULT);
        if (cp.exists()) {
            return new InitialSource(readClasspath(cp), "classpath:" + CLASSPATH_DEFAULT);
        }
        return new InitialSource("{\"routes\":[],\"upstreams\":{}}", "empty-default");
    }

    private static String readClasspath(ClassPathResource cp) throws IOException {
        try (InputStream in = cp.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private void startFileWatcher() {
        Thread t = new Thread(this::watchLoop, "gateway-config-watcher");
        t.setDaemon(true);
        watcher = t;
        t.start();
        log.info("[config] 已监听配置文件变更: {}", watchedFile);
    }

    private void watchLoop() {
        Path file = watchedFile;
        while (!Thread.currentThread().isInterrupted()) {
            try {
                Thread.sleep(1000);
                if (!Files.isRegularFile(file)) {
                    continue;
                }
                long mtime = Files.getLastModifiedTime(file).toMillis();
                if (mtime != watchedLastModified) {
                    watchedLastModified = mtime;
                    String json = Files.readString(file, StandardCharsets.UTF_8);
                    try {
                        replaceFromJson(json);
                    } catch (Exception e) {
                        // 坏配置被拒绝，继续沿用旧配置，等待下一次写入
                        log.warn("[config] 文件变更但校验失败，保留旧配置: {}", e.getMessage());
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException e) {
                log.warn("[config] 读取配置文件失败: {}", e.getMessage());
            }
        }
    }

    @PreDestroy
    public void stop() {
        if (watcher != null) {
            watcher.interrupt();
        }
    }

    private record InitialSource(String json, String description) {
    }
}
