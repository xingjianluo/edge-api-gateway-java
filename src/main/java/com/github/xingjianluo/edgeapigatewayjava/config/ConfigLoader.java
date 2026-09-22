package com.github.xingjianluo.edgeapigatewayjava.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.xingjianluo.edgeapigatewayjava.proxy.GatewayErrorCode;
import com.github.xingjianluo.edgeapigatewayjava.proxy.GatewayException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 从 JSON 文本加载 {@link GatewayConfig}。
 *
 * 缺省值：priority=100、timeoutMillis=3000、maxRetries=2、
 * 限流速率=0（不限）、enabled=true、方法/头/名单为空。
 */
@Component
public class ConfigLoader {

    private final ObjectMapper objectMapper = new ObjectMapper();

    public GatewayConfig load(String json) {
        if (json == null || json.isBlank()) {
            throw new GatewayException(GatewayErrorCode.CONFIG_INVALID, "配置内容为空");
        }
        RawConfig raw;
        try {
            raw = objectMapper.readValue(json, RawConfig.class);
        } catch (Exception e) {
            throw new GatewayException(GatewayErrorCode.CONFIG_INVALID, "JSON 解析失败: " + e.getMessage());
        }
        return convert(raw);
    }

    private GatewayConfig convert(RawConfig raw) {
        List<RouteConfig> routes = new ArrayList<>();
        if (raw.routes != null) {
            for (RawRoute r : raw.routes) {
                routes.add(new RouteConfig(
                        r.id,
                        r.pathPrefix,
                        r.methods,
                        r.requiredHeaders,
                        r.upstream,
                        r.prefixRewrite,
                        r.priority == null ? 100 : r.priority,
                        r.timeoutMillis == null ? 3000L : r.timeoutMillis,
                        r.maxRetries == null ? 2 : r.maxRetries,
                        r.globalRatePerSecond == null ? 0d : r.globalRatePerSecond,
                        r.callerRatePerSecond == null ? 0d : r.callerRatePerSecond,
                        r.allowedCallers
                ));
            }
        }
        Map<String, UpstreamConfig> upstreams = new LinkedHashMap<>();
        if (raw.upstreams != null) {
            for (RawUpstream u : raw.upstreams) {
                upstreams.put(u.id, new UpstreamConfig(u.id, u.baseUrl, u.enabled == null || u.enabled));
            }
        }
        return new GatewayConfig(routes, upstreams);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RawConfig {
        public List<RawRoute> routes;
        public List<RawUpstream> upstreams;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RawRoute {
        public String id;
        public String pathPrefix;
        public List<String> methods;
        public Map<String, String> requiredHeaders;
        public String upstream;
        public String prefixRewrite;
        public Integer priority;
        public Long timeoutMillis;
        public Integer maxRetries;
        public Double globalRatePerSecond;
        public Double callerRatePerSecond;
        public List<String> allowedCallers;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RawUpstream {
        public String id;
        public String baseUrl;
        public Boolean enabled;
    }
}
