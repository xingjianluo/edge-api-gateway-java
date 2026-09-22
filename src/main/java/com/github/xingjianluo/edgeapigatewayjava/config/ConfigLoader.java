package com.github.xingjianluo.edgeapigatewayjava.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.xingjianluo.edgeapigatewayjava.error.ErrorCode;
import com.github.xingjianluo.edgeapigatewayjava.error.GatewayException;
import com.github.xingjianluo.edgeapigatewayjava.model.GatewayConfig;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;

/**
 * 从 {@code classpath:} 或 {@code file:} 位置加载并解析网关 JSON 配置。
 * 解析失败统一抛出 {@link ErrorCode#CONFIG_INVALID}，保证错误原因可区分。
 */
@Component
public class ConfigLoader {

    private final ObjectMapper objectMapper;

    public ConfigLoader() {
        this.objectMapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    public GatewayConfig load(String location) {
        Resource resource = resolve(location);
        if (!resource.exists()) {
            throw new GatewayException(ErrorCode.CONFIG_INVALID,
                    "gateway config not found at: " + location);
        }
        try (InputStream in = resource.getInputStream()) {
            GatewayConfig config = objectMapper.readValue(in, GatewayConfig.class);
            if (config == null) {
                throw new GatewayException(ErrorCode.CONFIG_INVALID,
                        "gateway config is empty: " + location);
            }
            return config;
        } catch (IOException e) {
            throw new GatewayException(ErrorCode.CONFIG_INVALID,
                    "failed to parse gateway config at " + location + ": " + e.getMessage(), e);
        }
    }

    private Resource resolve(String location) {
        if (location.startsWith("classpath:")) {
            return new ClassPathResource(location.substring("classpath:".length()));
        }
        if (location.startsWith("file:")) {
            return new FileSystemResource(location.substring("file:".length()));
        }
        return new FileSystemResource(location);
    }
}
