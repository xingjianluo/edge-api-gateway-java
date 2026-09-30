package com.github.xingjianluo.edgeapigatewayjava.quota;

/**
 * 周期配额判定结果。allowed=true 时表示已从对应窗口成功占用一次额度。
 */
public record QuotaDecision(boolean allowed, String scope, String reason) {

    public static QuotaDecision allow() {
        return new QuotaDecision(true, null, null);
    }

    public static QuotaDecision reject(String reason) {
        return new QuotaDecision(false, "QUOTA_EXHAUSTED", reason);
    }
}
