package com.github.xingjianluo.edgeapigatewayjava.quota;

/**
 * 周期配额判定结果。
 *
 * @param charged      本次是否真正占用了额度（首次请求=true；幂等命中/重复请求=false）
 * @param scope        拒绝范围码；放行时为 null
 * @param reason       人类可读的判定依据
 * @param used         当前窗口已用额度（诊断）
 * @param limit        当前窗口总额度（诊断）
 * @param windowStart  归一化后的窗口起点 epoch 毫秒（诊断）
 */
public record QuotaResult(boolean allowed, boolean charged, String scope, String reason,
                          long used, long limit, long windowStart) {

    public static QuotaResult charged(long used, long limit, long windowStart) {
        return new QuotaResult(true, true, null, null, used, limit, windowStart);
    }

    public static QuotaResult idempotentHit(long used, long limit, long windowStart, String reason) {
        return new QuotaResult(true, false, null, reason, used, limit, windowStart);
    }

    public static QuotaResult rejected(String reason, long used, long limit, long windowStart) {
        return new QuotaResult(false, false, "QUOTA_EXHAUSTED", reason, used, limit, windowStart);
    }
}
