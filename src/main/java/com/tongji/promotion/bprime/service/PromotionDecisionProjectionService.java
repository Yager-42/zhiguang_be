package com.tongji.promotion.bprime.service;

import com.tongji.common.id.IdNamespace;
import com.tongji.common.id.IdService;
import com.tongji.promotion.bprime.mapper.PromotionProjectionCheckpointMapper;
import com.tongji.promotion.bprime.model.PromotionAuctionDecision;
import com.tongji.promotion.bprime.model.PromotionWalletEffect;
import com.tongji.promotion.mapper.PromotionAuctionWindowMapper;
import com.tongji.promotion.mapper.PromotionBidMapper;
import com.tongji.promotion.mapper.PromotionSlotAllocationMapper;
import com.tongji.promotion.model.PromotionAuctionWindow;
import com.tongji.promotion.model.PromotionBid;
import com.tongji.promotion.model.PromotionBidStatus;
import com.tongji.promotion.model.PromotionResourceType;
import com.tongji.promotion.model.PromotionSlotAllocation;
import com.tongji.promotion.service.PromotionAllocationCacheService;
import com.tongji.wallet.model.WalletBusinessType;
import com.tongji.wallet.model.WalletLedgerReason;
import com.tongji.wallet.service.WalletService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Service
public class PromotionDecisionProjectionService {

    private final PromotionProjectionCheckpointMapper checkpointMapper;
    private final PromotionBidMapper bidMapper;
    private final PromotionAuctionWindowMapper windowMapper;
    private final PromotionSlotAllocationMapper allocationMapper;
    private final PromotionAllocationCacheService cacheService;
    private final IdService idService;
    private final WalletService walletService;
    private final StringRedisTemplate redisTemplate;

    public PromotionDecisionProjectionService(PromotionProjectionCheckpointMapper checkpointMapper,
                                              PromotionBidMapper bidMapper,
                                              PromotionAuctionWindowMapper windowMapper,
                                              PromotionSlotAllocationMapper allocationMapper,
                                              WalletService walletService,
                                              PromotionAllocationCacheService cacheService,
                                              IdService idService,
                                              StringRedisTemplate redisTemplate) {
        this.checkpointMapper = checkpointMapper;
        this.bidMapper = bidMapper;
        this.windowMapper = windowMapper;
        this.allocationMapper = allocationMapper;
        this.walletService = walletService;
        this.cacheService = cacheService;
        this.idService = idService;
        this.redisTemplate = redisTemplate;
    }

    @Transactional
    public void project(PromotionAuctionDecision decision) {
        project(decision, null, null, null);
    }

    @Transactional
    public void project(PromotionAuctionDecision decision, String kafkaTopic, Integer kafkaPartition, Long kafkaOffset) {
        if (isDuplicate(decision, kafkaTopic, kafkaPartition, kafkaOffset)) {
            return;
        }
        requireNextVersion(decision);
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
                decision.decisionVersion(), kafkaTopic, kafkaPartition, kafkaOffset);
    }

    private void requireNextVersion(PromotionAuctionDecision decision) {
        long lastVersion = java.util.Optional.ofNullable(
                checkpointMapper.findLastDecisionVersion(decision.auctionWindowId())).orElse(0L);
        if (decision.previousVersion() != lastVersion || decision.decisionVersion() != lastVersion + 1) {
            throw new IllegalStateException("promotion decision version gap: auctionWindowId="
                    + decision.auctionWindowId() + ", last=" + lastVersion
                    + ", previous=" + decision.previousVersion()
                    + ", current=" + decision.decisionVersion());
        }
    }

    private boolean isDuplicate(PromotionAuctionDecision decision, String kafkaTopic,
                                Integer kafkaPartition, Long kafkaOffset) {
        Long lastVersion = checkpointMapper.findLastDecisionVersion(decision.auctionWindowId());
        if (lastVersion == null || decision.decisionVersion() != lastVersion) {
            return false;
        }
        if (!java.util.Objects.equals(
                checkpointMapper.findLastDecisionId(decision.auctionWindowId()), decision.decisionId())) {
            return false;
        }
        checkpointMapper.upsert(decision.auctionWindowId(), decision.decisionId(),
                decision.decisionVersion(), kafkaTopic, kafkaPartition, kafkaOffset);
        return true;
    }

    private void settleWindow(PromotionAuctionDecision decision) {
        if (allocationMapper.countByAuctionWindowId(decision.auctionWindowId()) > 0) {
            return;
        }
        Map<String, Object> payload = decision.payload();
        Instant allocationStartAt = Instant.parse(requiredString(payload, "allocationStartAt"));
        Instant allocationEndAt = Instant.parse(requiredString(payload, "allocationEndAt"));
        PromotionResourceType resourceType = PromotionResourceType.valueOf(decision.resourceType());
        winners(payload).forEach(winner -> allocationMapper.insert(PromotionSlotAllocation.builder()
                .id(idService.nextId(IdNamespace.ADMIN_OPERATION))
                .auctionWindowId(decision.auctionWindowId())
                .resourceType(resourceType)
                .slotIndex(requiredInt(winner, "slotIndex"))
                .campaignId(requiredLong(winner, "campaignId"))
                .postId(requiredLong(winner, "postId"))
                .bidderUserId(requiredLong(winner, "bidderUserId"))
                .clearingPrice(requiredLong(winner, "clearingPrice"))
                .allocationStartAt(allocationStartAt)
                .allocationEndAt(allocationEndAt)
                .createdAt(decision.decidedAt())
                .build()));
        walletEffects(payload).forEach(this::applyWalletEffect);
        windowMapper.markSettled(decision.auctionWindowId(), decision.decidedAt());
        redisTemplate.delete("promotion:auction:" + decision.auctionWindowId() + ":close_decision_version");
        cacheService.refreshActiveAllocations(resourceType, decision.decidedAt());
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> winners(Map<String, Object> payload) {
        Object winners = payload.get("winners");
        if (winners instanceof List<?> list) {
            return (List<Map<String, Object>>) list;
        }
        throw new IllegalArgumentException("WINDOW_CLOSED payload requires winners");
    }

    @SuppressWarnings("unchecked")
    private List<PromotionWalletEffect> walletEffects(Map<String, Object> payload) {
        Object walletEffects = payload.get("walletEffects");
        if (walletEffects instanceof List<?> list) {
            return list.stream()
                    .map(effect -> walletEffect((Map<String, Object>) effect))
                    .toList();
        }
        throw new IllegalArgumentException("WINDOW_CLOSED payload requires walletEffects");
    }

    private PromotionWalletEffect walletEffect(Map<String, Object> payload) {
        return new PromotionWalletEffect(
                requiredLong(payload, "ownerUserId"),
                requiredLong(payload, "amount"),
                requiredString(payload, "effectType"),
                requiredString(payload, "businessRef"));
    }

    private void applyWalletEffect(PromotionWalletEffect effect) {
        if ("CAPTURE".equals(effect.effectType())) {
            walletService.captureHoldToPlatform(effect.ownerUserId(), effect.amount(),
                    WalletLedgerReason.PROMOTION_BPRIME_CAPTURE, WalletBusinessType.PROMOTION,
                    effect.businessRef());
        } else if ("RELEASE".equals(effect.effectType())) {
            walletService.releaseHold(effect.ownerUserId(), effect.amount(),
                    WalletLedgerReason.PROMOTION_BPRIME_RELEASE, WalletBusinessType.PROMOTION,
                    effect.businessRef());
        } else {
            throw new IllegalArgumentException("unsupported promotion wallet effect: " + effect.effectType());
        }
    }

    private String requiredString(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (value instanceof String string && !string.isBlank()) {
            return string;
        }
        throw new IllegalArgumentException("WINDOW_CLOSED payload requires " + key);
    }

    private long requiredLong(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        throw new IllegalArgumentException("WINDOW_CLOSED winner requires " + key);
    }

    private int requiredInt(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        throw new IllegalArgumentException("WINDOW_CLOSED winner requires " + key);
    }

    private String holdBusinessRef(PromotionAuctionDecision decision) {
        return decision.walletEffects().stream()
                .filter(effect -> "HOLD".equals(effect.effectType()))
                .map(com.tongji.promotion.bprime.model.PromotionWalletEffect::businessRef)
                .findFirst()
                .orElse("promotion-bprime:" + decision.commandId() + ":hold");
    }
}
