package com.github.xingjianluo.edgeapigatewayjava.common;

import java.util.Locale;
import java.util.Set;

/**
 * 逐跳（hop-by-hop）头处理：RFC 7230 §6.1 规定的逐跳头以及网关自身消费的头，
 * 在转发到上游以及回传客户端时都必须剥离，避免把连接级语义错误透传。
 */
public final class HopByHop {

    private HopByHop() {
    }

    private static final Set<String> HOP_BY_HOP = Set.of(
            "connection",
            "keep-alive",
            "proxy-authenticate",
            "proxy-authorization",
            "te",
            "trailer",
            "transfer-encoding",
            "upgrade",
            // 网关本地消费的头，不应转发给上游，避免伪造/重复语义
            "x-forwarded-for",
            "x-forwarded-proto",
            "x-forwarded-host",
            "x-forwarded-port",
            "x-real-ip");

    public static Set<String> names() {
        return HOP_BY_HOP;
    }

    public static boolean shouldStrip(String headerName) {
        return headerName != null && HOP_BY_HOP.contains(headerName.toLowerCase(Locale.ROOT));
    }
}
