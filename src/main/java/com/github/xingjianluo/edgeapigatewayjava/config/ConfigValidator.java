package com.github.xingjianluo.edgeapigatewayjava.config;

import com.github.xingjianluo.edgeapigatewayjava.error.ErrorCode;
import com.github.xingjianluo.edgeapigatewayjava.error.GatewayException;
import com.github.xingjianluo.edgeapigatewayjava.model.GatewayConfig;
import com.github.xingjianluo.edgeapigatewayjava.model.RateLimitConfig;
import com.github.xingjianluo.edgeapigatewayjava.model.RouteConfig;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 配置语义校验。发布新配置前必须通过全部校验，否则整体拒绝（不会出现半生效）：
 * <ul>
 *   <li>路由 id 必填且唯一；</li>
 *   <li>路径前缀必须以 "/" 开头；方法名必须合法（空表示 ANY）；</li>
 *   <li>匹配签名（方法 + 路径前缀 + 必需头集合）相同的两条规则视为自相矛盾，拒绝；</li>
 *   <li>上游地址必须是本机 loopback（不依赖外部网络）；</li>
 *   <li>超时/重试/限流参数必须为正数；头部正则必须可编译；</li>
 *   <li>不要求调用方身份却又配置白名单，属于语义矛盾，拒绝。</li>
 * </ul>
 */
@Component
public class ConfigValidator {

    private static final Set<String> LOOPBACK_HOSTS = Set.of(
            "localhost", "127.0.0.1", "0:0:0:0:0:0:0:1", "::1");

    private static final Set<String> VALID_METHODS = Set.of(
            "ANY", "GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS");

    public void validate(GatewayConfig config) {
        if (config == null) {
            throw invalid("config is null");
        }
        if (config.getDefaultTimeoutMillis() <= 0) {
            throw invalid("defaultTimeoutMillis must be positive");
        }
        if (config.getMaxBodyBytes() <= 0) {
            throw invalid("maxBodyBytes must be positive");
        }
        if (config.getDedupTtlMillis() <= 0) {
            throw invalid("dedupTtlMillis must be positive");
        }
        List<RouteConfig> routes = config.getRoutes();
        if (routes == null || routes.isEmpty()) {
            throw invalid("at least one route is required");
        }

        Set<String> ids = new HashSet<>();
        Set<String> signatures = new HashSet<>();
        for (int i = 0; i < routes.size(); i++) {
            RouteConfig route = routes.get(i);
            String where = "route[" + i + "]";
            String id = route.getId() == null ? "" : route.getId().trim();
            if (id.isEmpty()) {
                throw invalid(where + ": id is required");
            }
            if (!ids.add(id)) {
                throw invalid(where + ": duplicate route id '" + id + "'");
            }
            route.setId(id);

            String prefix = route.getPathPrefix();
            if (prefix == null || !prefix.startsWith("/")) {
                throw invalid(where + "('" + id + "'): pathPrefix must start with '/'");
            }
            String method = route.getMethod() == null || route.getMethod().isBlank()
                    ? "ANY" : route.getMethod().trim().toUpperCase(Locale.ROOT);
            if (!VALID_METHODS.contains(method)) {
                throw invalid(where + "('" + id + "'): unsupported method '" + route.getMethod() + "'");
            }
            route.setMethod(method);

            Map<String, String> headers = canonicalHeaders(route.getRequiredHeaders(), id);
            route.setRequiredHeaders(headers);
            for (Map.Entry<String, String> e : headers.entrySet()) {
                if (e.getValue().startsWith("regex:")) {
                    try {
                        Pattern.compile(e.getValue().substring("regex:".length()));
                    } catch (PatternSyntaxException pse) {
                        throw invalid(where + "('" + id + "'): bad regex for header '"
                                + e.getKey() + "': " + pse.getMessage());
                    }
                }
            }

            validateUpstream(route, where, id);
            if (route.getTimeoutMillis() < 0) {
                throw invalid(where + "('" + id + "'): timeoutMillis must be >= 0");
            }
            if (route.getRetries() < 0) {
                throw invalid(where + "('" + id + "'): retries must be >= 0");
            }
            if (!route.isRequireCaller() && route.getAllowedCallers() != null
                    && !route.getAllowedCallers().isEmpty()) {
                throw invalid(where + "('" + id + "'): allowedCallers is set but requireCaller=false");
            }
            validateRateLimit(route.getRateLimit(), id);

            String signature = method + " " + prefix + " " + headers.keySet();
            if (!signatures.add(signature)) {
                throw invalid(where + "('" + id + "'): contradictory/duplicate match signature: "
                        + signature + " — two routes match the exact same request features");
            }
        }
    }

    private Map<String, String> canonicalHeaders(Map<String, String> raw, String id) {
        Map<String, String> canonical = new LinkedHashMap<>();
        if (raw == null) {
            return canonical;
        }
        for (Map.Entry<String, String> e : raw.entrySet()) {
            if (e.getKey() == null || e.getKey().isBlank()) {
                throw invalid("route('" + id + "'): required header name is blank");
            }
            String name = e.getKey().trim().toLowerCase(Locale.ROOT);
            String value = e.getValue() == null ? "" : e.getValue().trim();
            canonical.put(name, value);
        }
        return canonical;
    }

    private void validateUpstream(RouteConfig route, String where, String id) {
        String url = route.getUpstreamBaseUrl();
        if (url == null || url.isBlank()) {
            throw invalid(where + "('" + id + "'): upstreamBaseUrl is required");
        }
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (IllegalArgumentException iae) {
            throw invalid(where + "('" + id + "'): malformed upstreamBaseUrl '" + url + "'");
        }
        if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) {
            throw invalid(where + "('" + id + "'): upstreamBaseUrl must be http(s)");
        }
        if (uri.getHost() == null || !LOOPBACK_HOSTS.contains(uri.getHost().toLowerCase(Locale.ROOT))) {
            throw invalid(where + "('" + id + "'): upstream host must be loopback (localhost/127.0.0.1/[::1])"
                    + ", got: " + uri.getHost());
        }
        route.setUpstreamBaseUrl(trimTrailingSlash(url.trim()));
        String strip = route.getStripPrefix();
        if (strip != null) {
            if (strip.isBlank()) {
                // 空白串等价于"不裁剪"
                route.setStripPrefix(null);
            } else {
                if (!strip.startsWith("/")) {
                    throw invalid(where + "('" + id + "'): stripPrefix must start with '/'");
                }
                route.setStripPrefix(trimTrailingSlash(strip.trim()));
            }
        }
    }

    private void validateRateLimit(RateLimitConfig rl, String id) {
        if (rl == null) {
            return;
        }
        if (rl.getPermitsPerWindow() < 0) {
            throw invalid("route('" + id + "'): rateLimit.permitsPerWindow must be >= 0 (-1 means disabled)");
        }
        if (rl.getPermitsPerWindow() == 0) {
            throw invalid("route('" + id + "'): rateLimit.permitsPerWindow must be >= 1 (or -1 to disable)");
        }
        if (rl.getWindowMillis() <= 0) {
            throw invalid("route('" + id + "'): rateLimit.windowMillis must be positive");
        }
        if (rl.getDailyQuota() < -1 || rl.getDailyQuota() == 0) {
            throw invalid("route('" + id + "'): dailyQuota must be >= 1 (or -1 to disable)");
        }
    }

    private static String trimTrailingSlash(String s) {
        return s.endsWith("/") && s.length() > 1 ? s.substring(0, s.length() - 1) : s;
    }

    private static GatewayException invalid(String message) {
        return new GatewayException(ErrorCode.CONFIG_INVALID, message);
    }
}
