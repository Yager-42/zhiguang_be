package com.tongji.promotion.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * boost 活动：创作者针对推荐排序或关注触达开启的非拍卖推广投放。
 * <p>{@code bidAmount} 是创作者原始出价（货币，仅用于 quote/审计）；
 * {@code boostValue} 是适配层产出的有效加权值（非货币，参与排序/触达优先级）；
 * {@code unitPrice} 是每次有效 deliver 的扣费单价（货币）；
 * {@code budgetConsumed} 是已结算 capture 额，不含仍 PENDING 的 delivery。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaidBoostCampaign {
    private long id;
    private long creatorUserId;
    private long postId;
    private PaidBoostChannel channel;
    private long bidAmount;
    private long boostValue;
    private long unitPrice;
    private long budgetTotal;
    private long budgetConsumed;
    private String reserveBusinessRef;
    private PaidBoostCampaignStatus status;
    private Instant startAt;
    private Instant endAt;
    private Instant closedAt;
    private Instant createdAt;
    private Instant updatedAt;
}
