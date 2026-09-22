package com.github.xingjianluo.edgeapigatewayjava.routing;

import com.github.xingjianluo.edgeapigatewayjava.model.GatewayConfig;
import com.github.xingjianluo.edgeapigatewayjava.model.RouteConfig;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 确定性路由匹配器。
 *
 * <p>匹配分三步，每一步的失败原因都可区分：</p>
 * <ol>
 *   <li>路径前缀都不匹配 —— {@code ROUTE_NOT_FOUND}；</li>
 *   <li>路径匹配但方法（ANY 除外）不匹配 —— {@code METHOD_NOT_ALLOWED}；</li>
 *   <li>路径+方法匹配但必需头（值相等或 {@code regex:} 正则）不满足 ——
 *       {@code HEADER_CONDITION_FAILED}。</li>
 * </ol>
 *
 * <p>多个规则同时命中时按确定性优先级选唯一目标：</p>
 * <ul>
 *   <li>基础分：匹配到的必需头数量（每个 +100，条件越具体越优先）；</li>
 *   <li>路径前缀越长越优先（长度差按 1:1 计入，确保多一个头条件永远压过任意长度差）；</li>
 *   <li>显式方法匹配高于 ANY（+1）；</li>
 *   <li>完全同分时按路由 id 字典序，保证与配置书写顺序无关的稳定结果。</li>
 * </ul>
 */
@Component
public class Router {

    public enum Failure {
        NONE, ROUTE_NOT_FOUND, METHOD_NOT_ALLOWED, HEADER_CONDITION_FAILED
    }

    public record MatchResult(Failure failure, RouteMatch match, String reason) {
        static MatchResult ok(RouteMatch match) {
            return new MatchResult(Failure.NONE, match, null);
        }

        static MatchResult fail(Failure failure, String reason) {
            return new MatchResult(failure, null, reason);
        }
    }

    public MatchResult match(GatewayConfig config, RequestFeatures features) {
        boolean anyPathMatch = false;
        boolean anyPathAndMethodMatch = false;
        String headerFailureExample = null;

        RouteConfig winner = null;
        long winnerScore = Long.MIN_VALUE;

        for (RouteConfig route : config.getRoutes()) {
            if (!features.path().startsWith(route.getPathPrefix())) {
                continue;
            }
            anyPathMatch = true;
            boolean methodMatches = "ANY".equals(route.getMethod())
                    || route.getMethod().equals(features.method().toUpperCase(Locale.ROOT));
            if (!methodMatches) {
                continue;
            }
            anyPathAndMethodMatch = true;

            String unmatchedHeader = firstUnmatchedHeader(route, features.headers());
            if (unmatchedHeader != null) {
                if (headerFailureExample == null) {
                    headerFailureExample = "route '" + route.getId()
                            + "' requires header '" + unmatchedHeader + "' which is missing/mismatched";
                }
                continue;
            }

            long score = score(route, features);
            if (score > winnerScore
                    || (score == winnerScore && winner != null && route.getId().compareTo(winner.getId()) < 0)) {
                winner = route;
                winnerScore = score;
            }
        }

        if (winner != null) {
            return MatchResult.ok(new RouteMatch(winner, toUpstreamPath(winner, features.path())));
        }
        if (!anyPathMatch) {
            return MatchResult.fail(Failure.ROUTE_NOT_FOUND,
                    "no route prefix matches path: " + features.path());
        }
        if (!anyPathAndMethodMatch) {
            return MatchResult.fail(Failure.METHOD_NOT_ALLOWED,
                    "path matched but no route allows method: " + features.method());
        }
        return MatchResult.fail(Failure.HEADER_CONDITION_FAILED, headerFailureExample);
    }

    private long score(RouteConfig route, RequestFeatures features) {
        long headerScore = (long) route.getRequiredHeaders().size() * 100L;
        long prefixScore = route.getPathPrefix().length();
        long methodScore = "ANY".equals(route.getMethod()) ? 0L : 1L;
        return headerScore + prefixScore + methodScore;
    }

    private String firstUnmatchedHeader(RouteConfig route, Map<String, String> requestHeaders) {
        for (Map.Entry<String, String> required : route.getRequiredHeaders().entrySet()) {
            String actual = requestHeaders.get(required.getKey());
            String expected = required.getValue();
            if (expected.startsWith("regex:")) {
                Pattern pattern = Pattern.compile(expected.substring("regex:".length()));
                if (actual == null || !pattern.matcher(actual).matches()) {
                    return required.getKey();
                }
            } else {
                if (actual == null || !actual.equals(expected)) {
                    return required.getKey();
                }
            }
        }
        return null;
    }

    private String toUpstreamPath(RouteConfig route, String requestPath) {
        String path = requestPath;
        if (route.getStripPrefix() != null && path.startsWith(route.getStripPrefix())) {
            path = path.substring(route.getStripPrefix().length());
            if (path.isEmpty()) {
                path = "/";
            }
        }
        if (route.getStripPrefix() == null && route.getPathPrefix() != null && !"/".equals(route.getPathPrefix())) {
            // 默认保留完整路径，不做裁剪
            path = requestPath;
        }
        return path.startsWith("/") ? path : "/" + path;
    }
}
