package com.github.xingjianluo.edgeapigatewayjava.config;

import com.github.xingjianluo.edgeapigatewayjava.common.GatewayConstants;
import com.github.xingjianluo.edgeapigatewayjava.model.GatewayConfig;
import com.github.xingjianluo.edgeapigatewayjava.model.RouteConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 配置持有者。
 *
 * <p>一致性保证：新配置必须先完整加载并通过 {@link ConfigValidator}，再通过对 volatile
 * 字段的单次写原子发布；发布要么成功（整体生效），要么抛异常（旧配置原样保留），
 * 不存在半生效状态。进行中的请求在入口处一次性快照当前配置，配置切换后也不会在
 * 转发中途被改路由到已下线目标。</p>
 */
@Component
public class ConfigStore {

    private static final Logger log = LoggerFactory.getLogger(ConfigStore.class);

    private final GatewayProperties properties;
    private final ConfigLoader loader;
    private final ConfigValidator validator;

    private volatile GatewayConfig config;
    private volatile long version = 0;

    @Autowired
    public ConfigStore(GatewayProperties properties, ConfigLoader loader, ConfigValidator validator) {
        this.properties = properties;
        this.loader = loader;
        this.validator = validator;
    }

    /** 首次启动加载，失败直接抛出阻止应用以非法配置对外服务。 */
    public void init() {
        GatewayConfig loaded = loader.load(properties.getConfigLocation());
        normalize(loaded);
        validator.validate(loaded);
        this.config = loaded;
        this.version = 1;
        log.info("gateway config loaded from {} with {} routes, version=1",
                properties.getConfigLocation(), loaded.getRoutes().size());
    }

    public GatewayConfig current() {
        GatewayConfig snapshot = config;
        if (snapshot == null) {
            init();
            snapshot = config;
        }
        return snapshot;
    }

    public long version() {
        return version;
    }

    /**
     * 热更新：重新加载 + 校验，全部成功后才原子替换。
     *
     * @return true 表示发布了新版本；false 表示加载结果未变化（仍校验通过）
     */
    public synchronized boolean reload() {
        GatewayConfig candidate = loader.load(properties.getConfigLocation());
        normalize(candidate);
        validator.validate(candidate);
        this.config = candidate;
        this.version += 1;
        log.info("gateway config hot-reloaded, version={} routes={}",
                version, candidate.getRoutes().size());
        return true;
    }

    /** 仅用于测试：直接发布一份内存配置（同样经过校验）。 */
    public synchronized void publishForTest(GatewayConfig newConfig) {
        normalize(newConfig);
        validator.validate(newConfig);
        this.config = newConfig;
        this.version += 1;
    }

    /** 填充配置内的默认值（不修改用户显式给出的值）。 */
    private void normalize(GatewayConfig cfg) {
        if (cfg.getCallerHeader() == null || cfg.getCallerHeader().isBlank()) {
            cfg.setCallerHeader(GatewayConstants.DEFAULT_CALLER_HEADER);
        }
        for (RouteConfig route : cfg.getRoutes()) {
            if (route.getTimeoutMillis() == 0) {
                route.setTimeoutMillis(cfg.getDefaultTimeoutMillis());
            }
        }
    }
}
