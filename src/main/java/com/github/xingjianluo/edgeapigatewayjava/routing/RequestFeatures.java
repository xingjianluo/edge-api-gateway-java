package com.github.xingjianluo.edgeapigatewayjava.routing;

/** 参与路由判定的请求特征，骨架占位。 */
public record RequestFeatures(
        String method,
        String path,
        java.util.Map<String, String> headers) {
}
