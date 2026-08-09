package com.tongji.promotion.bprime.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/** MySQL 中的推广竞价预授权事实，窗口运行时占用量由 Redis 决策后异步投影。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PromotionBidEscrowRecord {
    private long id;
    private long auctionWindowId;
    private long campaignId;
    private long bidderUserId;
    private long authorizedAmount;
    private long currentHold;
    private String status;
    private Instant expiresAt;
    private Instant createdAt;
    private Instant updatedAt;
}
