package com.tongji.promotion.bprime.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.common.id.IdNamespace;
import com.tongji.common.id.IdService;
import com.tongji.promotion.bprime.mapper.PromotionAuctionDecisionMapper;
import com.tongji.promotion.bprime.mapper.PromotionProjectionCheckpointMapper;
import com.tongji.promotion.bprime.model.PromotionAuctionDecision;
import com.tongji.promotion.bprime.model.PromotionAuctionDecisionRecord;
import com.tongji.promotion.mapper.PromotionAuctionWindowMapper;
import com.tongji.promotion.mapper.PromotionBidMapper;
import com.tongji.promotion.model.PromotionAuctionWindow;
import com.tongji.promotion.model.PromotionBid;
import com.tongji.promotion.model.PromotionBidStatus;
import com.tongji.promotion.service.PromotionAllocationCacheService;
import com.tongji.promotion.service.PromotionAuctionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
public class PromotionDecisionProjectionService {

    private final PromotionAuctionDecisionMapper decisionMapper;
    private final PromotionProjectionCheckpointMapper checkpointMapper;
    private final PromotionBidMapper bidMapper;
    private final PromotionAuctionWindowMapper windowMapper;
    private final PromotionAuctionService auctionService;
    private final PromotionAllocationCacheService cacheService;
    private final ObjectMapper objectMapper;
    private final IdService idService;

    public PromotionDecisionProjectionService(PromotionAuctionDecisionMapper decisionMapper,
                                              PromotionProjectionCheckpointMapper checkpointMapper,
                                              PromotionBidMapper bidMapper,
                                              PromotionAuctionWindowMapper windowMapper,
                                              PromotionAuctionService auctionService,
                                              PromotionAllocationCacheService cacheService,
                                              ObjectMapper objectMapper,
                                              IdService idService) {
        this.decisionMapper = decisionMapper;
        this.checkpointMapper = checkpointMapper;
        this.bidMapper = bidMapper;
        this.windowMapper = windowMapper;
        this.auctionService = auctionService;
        this.cacheService = cacheService;
        this.objectMapper = objectMapper;
        this.idService = idService;
    }

    @Transactional
    public void project(PromotionAuctionDecision decision) {
        project(decision, null, null, null);
    }

    @Transactional
    public void project(PromotionAuctionDecision decision, String kafkaTopic, Integer kafkaPartition, Long kafkaOffset) {
        int inserted;
        try {
            inserted = decisionMapper.insertIgnore(PromotionAuctionDecisionRecord.builder()
                    .id(idService.nextId(IdNamespace.ADMIN_OPERATION))
                    .decisionId(decision.decisionId())
                    .commandId(decision.commandId())
                    .auctionWindowId(decision.auctionWindowId())
                    .decisionType(decision.decisionType())
                    .accepted(decision.accepted())
                    .rejectionReason(decision.rejectionReason())
                    .payloadJson(objectMapper.writeValueAsString(decision))
                    .createdAt(decision.decidedAt())
                    .build());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to persist promotion decision projection", e);
        }
        if (inserted == 0) {
            checkpointMapper.upsert(decision.auctionWindowId(), decision.decisionId(),
                    kafkaTopic, kafkaPartition, kafkaOffset);
            return;
        }
        if ("WINDOW_CLOSED".equals(decision.decisionType())) {
            settleWindow(decision);
        } else if (decision.accepted()) {
            bidMapper.upsertAccepted(PromotionBid.builder()
                    .id(idService.nextId(IdNamespace.ADMIN_OPERATION))
                    .campaignId(decision.campaignId())
                    .auctionWindowId(decision.auctionWindowId())
                    .bidderUserId(decision.bidderUserId())
                    .bidAmount(decision.bidAmount())
                    .walletBusinessRef(holdBusinessRef(decision))
                    .commandId(decision.commandId())
                    .decisionId(decision.decisionId())
                    .decisionStatus(decision.decisionType())
                    .status(PromotionBidStatus.ACTIVE)
                    .createdAt(decision.decidedAt())
                    .updatedAt(decision.decidedAt())
                    .postId(decision.postId())
                    .build());
        }
        checkpointMapper.upsert(decision.auctionWindowId(), decision.decisionId(),
                kafkaTopic, kafkaPartition, kafkaOffset);
    }

    private void settleWindow(PromotionAuctionDecision decision) {
        PromotionAuctionWindow window = windowMapper.findById(decision.auctionWindowId());
        if (window == null) {
            throw new IllegalStateException("promotion auction window not found: " + decision.auctionWindowId());
        }
        Instant allocationStartAt = window.getWindowEndAt();
        long spanSeconds = window.getWindowEndAt().getEpochSecond() - window.getWindowStartAt().getEpochSecond();
        Instant allocationEndAt = allocationStartAt.plusSeconds(spanSeconds);
        List<PromotionBid> bids = bidMapper.listActiveBidsByWindowId(window.getId(), allocationStartAt, allocationEndAt);
        auctionService.settleWindow(window, bids, decision.decidedAt());
        cacheService.refreshActiveAllocations(window.getResourceType(), decision.decidedAt());
    }

    private String holdBusinessRef(PromotionAuctionDecision decision) {
        return decision.walletEffects().stream()
                .filter(effect -> "HOLD".equals(effect.effectType()))
                .map(com.tongji.promotion.bprime.model.PromotionWalletEffect::businessRef)
                .findFirst()
                .orElse("promotion-bprime:" + decision.commandId() + ":hold");
    }
}
