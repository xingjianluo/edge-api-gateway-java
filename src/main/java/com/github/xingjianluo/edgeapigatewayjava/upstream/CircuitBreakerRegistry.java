package com.github.xingjianluo.edgeapigatewayjava.upstream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 按上游标识维护简易熔断状态机（CLOSED -> OPEN -> HALF_OPEN -> CLOSED/OPEN）。
 *
 * - 连续 {@link #FAILURE_THRESHOLD} 次失败后打开 10 秒，期间快速失败，
 *   新请求不会被继续发往已不可用的目标；
 * - 10 秒后允许一次试探请求（半开），试探成功则关闭，失败则重新打开。
 */
@Component
public class CircuitBreakerRegistry {

    private static final Logger log = LoggerFactory.getLogger(CircuitBreakerRegistry.class);
    public static final int FAILURE_THRESHOLD = 5;
    static final long OPEN_DURATION_MILLIS = 10_000L;

    private final ConcurrentHashMap<String, State> states = new ConcurrentHashMap<>();

    public boolean isOpen(String upstreamId) {
        State s = states.get(upstreamId);
        if (s == null) {
            return false;
        }
        synchronized (s) {
            if (s.status == Status.OPEN && System.currentTimeMillis() - s.openedAt >= OPEN_DURATION_MILLIS) {
                // 进入半开：仅允许这一次试探
                s.status = Status.HALF_OPEN;
                log.info("[circuit] 上游 {} 进入 HALF_OPEN，允许试探请求", upstreamId);
                return false;
            }
            boolean open = s.status == Status.OPEN;
            if (open) {
                log.info("[circuit] 上游 {} 处于 OPEN，快速失败", upstreamId);
            }
            return open;
        }
    }

    public void recordSuccess(String upstreamId) {
        State s = states.computeIfAbsent(upstreamId, k -> new State());
        synchronized (s) {
            if (s.status != Status.CLOSED) {
                log.info("[circuit] 上游 {} 试探成功，熔断器关闭", upstreamId);
            }
            s.status = Status.CLOSED;
            s.failures = 0;
            s.openedAt = 0;
        }
    }

    public void recordFailure(String upstreamId) {
        State s = states.computeIfAbsent(upstreamId, k -> new State());
        synchronized (s) {
            if (s.status == Status.HALF_OPEN) {
                s.status = Status.OPEN;
                s.openedAt = System.currentTimeMillis();
                log.warn("[circuit] 上游 {} 试探失败，重新 OPEN", upstreamId);
                return;
            }
            s.failures++;
            if (s.failures >= FAILURE_THRESHOLD && s.status == Status.CLOSED) {
                s.status = Status.OPEN;
                s.openedAt = System.currentTimeMillis();
                log.warn("[circuit] 上游 {} 连续失败 {} 次，熔断 OPEN {}ms",
                        upstreamId, s.failures, OPEN_DURATION_MILLIS);
            }
        }
    }

    public void reset() {
        states.clear();
    }

    private enum Status { CLOSED, OPEN, HALF_OPEN }

    private static final class State {
        private Status status = Status.CLOSED;
        private int failures = 0;
        private long openedAt = 0;
    }
}
