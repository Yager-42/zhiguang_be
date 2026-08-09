package com.tongji.promotion.bprime.model;

/**
 * 网关可在不访问权威决策器时证明成立的推广竞价拒绝原因。
 *
 * <p>枚举中的条件都必须随竞价推进保持单调；新增原因前必须证明缓存落后不会误拒绝。</p>
 *
 * @since 2026-08-09
 */
public enum PromotionBidFastRejectionReason {
    BID_NOT_HIGHER
}
