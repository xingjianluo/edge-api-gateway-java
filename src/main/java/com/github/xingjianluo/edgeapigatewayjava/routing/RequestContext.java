package com.github.xingjianluo.edgeapigatewayjava.routing;

import java.util.List;
import java.util.Map;

/**
 * 进入网关的一次请求的不可变特征视图，供路由匹配与限流判定使用。
 */
public record RequestContext(
        String method,
        String path,
        Map<String, List<String>> headers,
        String callerId,
        String requestId,
        byte[] body
) {
    public RequestContext {
        headers = headers == null ? Map.of() : Map.copyOf(headers);
        body = body == null ? new byte[0] : body;
    }

    /** 大小写不敏感地获取第一个头值。 */
    public String header(String name) {
        for (Map.Entry<String, List<String>> e : headers.entrySet()) {
            if (e.getKey().equalsIgnoreCase(name) && e.getValue() != null && !e.getValue().isEmpty()) {
                return e.getValue().get(0);
            }
        }
        return null;
    }
}
