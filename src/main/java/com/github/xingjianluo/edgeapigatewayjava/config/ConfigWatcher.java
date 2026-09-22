package com.github.xingjianluo.edgeapigatewayjava.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 可选的配置文件轮询热更新。仅对 {@code file:} 位置生效；轮询到文件修改时间变化时，
 * 调用 {@link ConfigStore#reload()}，reload 自身保证校验通过后才整体生效，
 * 校验失败时只记录告警，旧配置继续服务。
 */
@Component
public class ConfigWatcher {

    private static final Logger log = LoggerFactory.getLogger(ConfigWatcher.class);

    private final GatewayProperties properties;
    private final ConfigStore configStore;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private Thread thread;
    private long lastSeenModified = -1;

    @Autowired
    public ConfigWatcher(GatewayProperties properties, ConfigStore configStore) {
        this.properties = properties;
        this.configStore = configStore;
    }

    public void start() {
        if (!properties.isWatchEnabled()) {
            return;
        }
        File file = fileLocation();
        if (file == null) {
            log.info("config watch disabled: location is not a file path");
            return;
        }
        lastSeenModified = file.lastModified();
        if (!running.compareAndSet(false, true)) {
            return;
        }
        thread = new Thread(this::loop, "edge-gateway-config-watcher");
        thread.setDaemon(true);
        thread.start();
        log.info("config watcher started on {} (interval {}ms)",
                file.getAbsolutePath(), properties.getWatchIntervalMillis());
    }

    public void stop() {
        running.set(false);
        if (thread != null) {
            thread.interrupt();
        }
    }

    private void loop() {
        File file = fileLocation();
        while (running.get()) {
            try {
                Thread.sleep(Math.max(200, properties.getWatchIntervalMillis()));
                long modified = file == null ? -1 : file.lastModified();
                if (file != null && modified != lastSeenModified) {
                    lastSeenModified = modified;
                    try {
                        configStore.reload();
                    } catch (RuntimeException ex) {
                        log.warn("hot-reload rejected, keeping previous config: {}", ex.getMessage());
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private File fileLocation() {
        String location = properties.getConfigLocation();
        if (location.startsWith("file:")) {
            return new File(location.substring("file:".length()));
        }
        if (!location.startsWith("classpath:")) {
            return new File(location);
        }
        return null;
    }
}
