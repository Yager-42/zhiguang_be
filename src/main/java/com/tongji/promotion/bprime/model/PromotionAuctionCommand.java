package com.tongji.promotion.bprime.model;

import java.time.Instant;

public record PromotionAuctionCommand(
        String commandId,
        String idempotencyKey,
        String requestHash,
        long auctionWindowId,
        long campaignId,
        long bidderUserId,
        long postId,
        String resourceType,
        long bidAmount,
        String type,
        Instant submittedAt
) {}
