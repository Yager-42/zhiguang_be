package com.tongji.promotion.bprime.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PromotionAuctionDecisionRecord {
    private long id;
    private String decisionId;
    private String commandId;
    private long auctionWindowId;
    private String decisionType;
    private boolean accepted;
    private String rejectionReason;
    private String payloadJson;
    private Instant createdAt;
}
