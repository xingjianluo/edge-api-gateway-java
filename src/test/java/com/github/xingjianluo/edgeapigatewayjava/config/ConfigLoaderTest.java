package com.github.xingjianluo.edgeapigatewayjava.config;

import com.github.xingjianluo.edgeapigatewayjava.error.ErrorCode;
import com.github.xingjianluo.edgeapigatewayjava.error.GatewayException;
import com.github.xingjianluo.edgeapigatewayjava.model.GatewayConfig;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConfigLoaderTest {

    private static final Logger log = LoggerFactory.getLogger(ConfigLoaderTest.class);

    private final ConfigLoader loader = new ConfigLoader();

    @Test
    void loadsClasspathJson() {
        GatewayConfig config = loader.load("classpath:gateway-config.json");
        log.info("input=classpath:gateway-config.json verdict=LOADED routes={}",
                config.getRoutes().size());
        assertEquals(1, config.getRoutes().size());
        assertEquals("health-open", config.getRoutes().get(0).getId());
    }

    @Test
    void missingLocationNormalizedToConfigInvalid() {
        GatewayException ex = assertThrows(GatewayException.class,
                () -> loader.load("classpath:does-not-exist.json"));
        log.info("input=missing location verdict={} reason='{}'", ex.errorCode(), ex.getMessage());
        assertEquals(ErrorCode.CONFIG_INVALID, ex.errorCode());
    }

    @Test
    void malformedJsonNormalizedToConfigInvalid() {
        GatewayException ex = assertThrows(GatewayException.class,
                () -> loader.load("classpath:malformed-config.json"));
        log.info("input=malformed json verdict={} reason='{}'", ex.errorCode(), ex.getMessage());
        assertEquals(ErrorCode.CONFIG_INVALID, ex.errorCode());
    }
}
