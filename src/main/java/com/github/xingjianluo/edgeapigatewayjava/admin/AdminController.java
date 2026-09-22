package com.github.xingjianluo.edgeapigatewayjava.admin;

import com.github.xingjianluo.edgeapigatewayjava.config.ConfigStore;
import com.github.xingjianluo.edgeapigatewayjava.error.ErrorCode;
import com.github.xingjianluo.edgeapigatewayjava.error.GatewayException;
import com.github.xingjianluo.edgeapigatewayjava.model.GatewayConfig;
import com.github.xingjianluo.edgeapigatewayjava.model.RouteConfig;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理端：
 * <ul>
 *   <li>GET  /__gateway/admin/config —— 当前生效配置的版本与路由摘要（只暴露非敏感元数据）；</li>
 *   <li>POST /__gateway/admin/reload —— 手动触发原子热更新；校验失败整体拒绝并保留旧配置，
 *       失败原因以 {@code CONFIG_INVALID} 归一化返回。</li>
 * </ul>
 */
@RestController
@RequestMapping("/__gateway/admin")
public class AdminController {

    private final ConfigStore configStore;

    public AdminController(ConfigStore configStore) {
        this.configStore = configStore;
    }

    @GetMapping("/config")
    public Map<String, Object> config() {
        GatewayConfig cfg = configStore.current();
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("version", configStore.version());
        view.put("callerHeader", cfg.getCallerHeader());
        view.put("defaultTimeoutMillis", cfg.getDefaultTimeoutMillis());
        view.put("routeCount", cfg.getRoutes().size());
        List<Map<String, Object>> routes = cfg.getRoutes().stream()
                .map(this::routeView)
                .toList();
        view.put("routes", routes);
        return view;
    }

    @PostMapping("/reload")
    public Map<String, Object> reload() {
        try {
            configStore.reload();
        } catch (GatewayException e) {
            // 校验/解析失败：旧配置继续生效，原样抛出便于统一错误信封
            throw e;
        } catch (RuntimeException e) {
            throw new GatewayException(ErrorCode.CONFIG_INVALID,
                    "reload failed, previous config remains active: " + e.getMessage(), e);
        }
        return Map.of("version", configStore.version(), "status", "reloaded");
    }

    private Map<String, Object> routeView(RouteConfig route) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", route.getId());
        view.put("method", route.getMethod());
        view.put("pathPrefix", route.getPathPrefix());
        view.put("requiredHeaders", route.getRequiredHeaders());
        view.put("upstreamBaseUrl", route.getUpstreamBaseUrl());
        view.put("timeoutMillis", route.getTimeoutMillis());
        view.put("retries", route.getRetries());
        view.put("requireCaller", route.isRequireCaller());
        view.put("allowedCallers", route.getAllowedCallers());
        view.put("rateLimited", route.getRateLimit() != null);
        return view;
    }
}
