package com.github.xingjianluo.edgeapigatewayjava.proxy;

import com.github.xingjianluo.edgeapigatewayjava.config.ConfigService;
import com.github.xingjianluo.edgeapigatewayjava.config.GatewayConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 管理接口：
 * - GET  /__admin/config         查看当前生效的不可变快照
 * - POST /__admin/config/reload  提交完整 JSON 配置，校验通过后原子切换；
 *                                校验失败返回 500/CONFIG_INVALID，旧配置继续生效。
 */
@RestController
@RequestMapping("/__admin")
public class AdminController {

    private static final Logger log = LoggerFactory.getLogger(AdminController.class);

    private final ConfigService configService;

    public AdminController(ConfigService configService) {
        this.configService = configService;
    }

    @GetMapping("/config")
    public Map<String, Object> getConfig() {
        GatewayConfig cfg = configService.current();
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("routes", cfg.routes());
        view.put("upstreams", cfg.upstreams());
        return view;
    }

    @PostMapping("/config/reload")
    public ResponseEntity<Map<String, Object>> reload(@RequestBody(required = false) String json) {
        log.info("[admin] 收到配置热更新请求, bytes={}", json == null ? 0 : json.length());
        GatewayConfig next = configService.replaceFromJson(json);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("status", "RELOADED");
        resp.put("routes", next.routes().size());
        resp.put("upstreams", next.upstreams().size());
        resp.put("timestamp", Instant.now().toString());
        return ResponseEntity.ok(resp);
    }
}
