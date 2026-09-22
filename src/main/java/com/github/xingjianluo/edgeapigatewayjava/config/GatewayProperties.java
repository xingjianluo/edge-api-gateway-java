package com.github.xingjianluo.edgeapigatewayjava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 网关运行参数，骨架占位。 */
@ConfigurationProperties(prefix = "edge.gateway")
public class GatewayProperties {

    private String configLocation = "classpath:gateway-config.json";
    private boolean watchEnabled = false;
    private long watchIntervalMillis = 2000;

    public String getConfigLocation() {
        return configLocation;
    }

    public void setConfigLocation(String configLocation) {
        this.configLocation = configLocation;
    }

    public boolean isWatchEnabled() {
        return watchEnabled;
    }

    public void setWatchEnabled(boolean watchEnabled) {
        this.watchEnabled = watchEnabled;
    }

    public long getWatchIntervalMillis() {
        return watchIntervalMillis;
    }

    public void setWatchIntervalMillis(long watchIntervalMillis) {
        this.watchIntervalMillis = watchIntervalMillis;
    }
}
