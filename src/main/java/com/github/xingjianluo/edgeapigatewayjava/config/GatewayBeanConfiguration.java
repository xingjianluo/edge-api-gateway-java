package com.github.xingjianluo.edgeapigatewayjava.config;

import jakarta.annotation.PreDestroy;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 注册配置属性；启动时加载并校验首份配置，失败 fail-fast；按需启动文件监听。 */
@Configuration
@EnableConfigurationProperties(GatewayProperties.class)
public class GatewayBeanConfiguration {

    private final ConfigStore configStore;
    private final ConfigWatcher configWatcher;

    public GatewayBeanConfiguration(ConfigStore configStore, ConfigWatcher configWatcher) {
        this.configStore = configStore;
        this.configWatcher = configWatcher;
        this.configStore.init();
        this.configWatcher.start();
    }

    @PreDestroy
    public void shutdown() {
        configWatcher.stop();
    }
}
