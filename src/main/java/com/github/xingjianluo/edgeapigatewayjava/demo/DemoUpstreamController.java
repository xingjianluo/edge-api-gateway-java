package com.github.xingjianluo.edgeapigatewayjava.demo;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 内置本机上游服务（仅在 demo profile 下启用），路径挂在 /__upstream 下，
 * 与网关入口隔离。配合 gateway-routes-demo.json 可在单进程内完成端到端验证，
 * 不依赖外部网络或真实凭据。
 *
 * 端点：
 * - /__upstream/ok        始终 200，回显方法与请求体长度
 * - /__upstream/slow      睡眠 5 秒，用于验证超时与重试
 * - /__upstream/flaky     前两次 503，第三次起 200（通过 X-Flaky-Attempt 计数）
 * - /__upstream/error     始终 500
 * - 其余 /__upstream/**   200 回显
 */
@RestController
@Profile("demo")
public class DemoUpstreamController {

    private static final Logger log = LoggerFactory.getLogger(DemoUpstreamController.class);

    @RequestMapping("/__upstream/ok")
    public Map<String, Object> ok(HttpServletRequest request,
                                  @RequestBody(required = false) byte[] body,
                                  @RequestHeader(value = "X-Gateway-Attempt", required = false) String attempt) {
        log.info("[demo-upstream] /ok method={} attempt={} bodyBytes={}",
                request.getMethod(), attempt, body == null ? 0 : body.length);
        return resultBody("OK", request, body);
    }

    @RequestMapping("/__upstream/slow")
    public Map<String, Object> slow(HttpServletRequest request) throws InterruptedException {
        log.info("[demo-upstream] /slow 开始睡眠, method={}", request.getMethod());
        Thread.sleep(5000);
        return resultBody("SLOW_DONE", request, new byte[0]);
    }

    @RequestMapping("/__upstream/flaky")
    public ResponseEntity<Map<String, Object>> flaky(HttpServletRequest request,
                                                     @RequestHeader(value = "X-Gateway-Attempt", required = false) String attempt) {
        int n = 1;
        if (attempt != null) {
            try {
                n = Integer.parseInt(attempt.trim());
            } catch (NumberFormatException ignored) {
                n = 1;
            }
        }
        log.info("[demo-upstream] /flaky attempt={}", n);
        if (n <= 2) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(resultBody("FLAKY_FAIL_" + n, request, new byte[0]));
        }
        return ResponseEntity.ok(resultBody("FLAKY_OK_ON_" + n, request, new byte[0]));
    }

    @RequestMapping("/__upstream/error")
    public ResponseEntity<Map<String, Object>> error(HttpServletRequest request) {
        log.warn("[demo-upstream] /error 返回 500, method={}", request.getMethod());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(resultBody("UPSTREAM_500", request, new byte[0]));
    }

    @RequestMapping("/__upstream/**")
    public Map<String, Object> any(HttpServletRequest request,
                                   @RequestBody(required = false) byte[] body) {
        log.info("[demo-upstream] 回显 {} method={}", request.getRequestURI(), request.getMethod());
        return resultBody("ECHO", request, body);
    }

    private Map<String, Object> resultBody(String status, HttpServletRequest request, byte[] body) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("upstream", "demo");
        map.put("status", status);
        map.put("method", request.getMethod());
        map.put("uri", request.getRequestURI());
        map.put("bodyBytes", body == null ? 0 : body.length);
        map.put("timestamp", Instant.now().toString());
        return map;
    }
}
