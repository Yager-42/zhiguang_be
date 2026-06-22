package com.tongji.promotion.bprime.service;

import com.tongji.common.exception.BusinessException;
import com.tongji.common.exception.ErrorCode;
import com.tongji.common.id.IdNamespace;
import com.tongji.common.id.IdService;
import com.tongji.promotion.api.dto.SubmitPromotionBidCommandResponse;
import com.tongji.promotion.bprime.config.PromotionBPrimeProperties;
import com.tongji.promotion.bprime.mapper.PromotionAuctionCommandMapper;
import com.tongji.promotion.bprime.model.PromotionAuctionCommandRecord;
import com.tongji.promotion.bprime.mq.PromotionCommandMessagePort;
import com.tongji.promotion.mapper.PromotionAuctionWindowMapper;
import com.tongji.promotion.mapper.PromotionCampaignMapper;
import com.tongji.promotion.model.PromotionAuctionWindow;
import com.tongji.promotion.model.PromotionCampaign;
import com.tongji.promotion.model.PromotionCampaignStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;

@Service
public class PromotionCommandSubmissionService {

    private final PromotionCampaignMapper campaignMapper;
    private final PromotionAuctionWindowMapper windowMapper;
    private final PromotionAuctionCommandMapper commandMapper;
    private final PromotionCommandMessagePort messagePort;
    private final PromotionBPrimeProperties properties;
    private final IdService idService;

    public PromotionCommandSubmissionService(PromotionCampaignMapper campaignMapper,
                                             PromotionAuctionWindowMapper windowMapper,
                                             PromotionAuctionCommandMapper commandMapper,
                                             PromotionCommandMessagePort messagePort,
                                             PromotionBPrimeProperties properties,
                                             IdService idService) {
        this.campaignMapper = campaignMapper;
        this.windowMapper = windowMapper;
        this.commandMapper = commandMapper;
        this.messagePort = messagePort;
        this.properties = properties;
        this.idService = idService;
    }

    @Transactional
    public SubmitPromotionBidCommandResponse submit(long userId, long campaignId, long bidAmount,
                                                    String idempotencyKey, Instant now) {
        if (bidAmount <= 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "bidAmount must be greater than 0");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "idempotencyKey must not be blank");
        }
        PromotionCampaign campaign = requireOwnedCampaign(campaignId, userId);
        PromotionAuctionWindow window = windowMapper.findOpenWindow(campaign.getResourceType(), now);
        if (window == null) {
            throw new BusinessException(ErrorCode.PROMOTION_BID_WINDOW_CLOSED);
        }
        requireCampaignEligibleForWindow(campaign, window);

        String normalizedIdempotencyKey = idempotencyKey.trim();
        String requestHash = requestHash(campaignId, userId, window.getId(), bidAmount, normalizedIdempotencyKey);
        PromotionAuctionCommandRecord existing = commandMapper.findByIdempotency(
                window.getId(), userId, normalizedIdempotencyKey);
        if (existing != null) {
            if (!existing.getRequestHash().equals(requestHash)) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "idempotency key reused with different request");
            }
            return response(existing);
        }

        Instant submittedAt = now == null ? Instant.now() : now;
        String commandId = "promotion-bprime-" + idService.nextId(IdNamespace.ADMIN_OPERATION);
        PromotionAuctionCommandRecord record = PromotionAuctionCommandRecord.builder()
                .id(idService.nextId(IdNamespace.ADMIN_OPERATION))
                .commandId(commandId)
                .idempotencyKey(normalizedIdempotencyKey)
                .requestHash(requestHash)
                .auctionWindowId(window.getId())
                .campaignId(campaignId)
                .bidderUserId(userId)
                .postId(campaign.getPostId())
                .resourceType(campaign.getResourceType().name())
                .bidAmount(bidAmount)
                .status("SUBMITTED")
                .createdAt(submittedAt)
                .updatedAt(submittedAt)
                .build();
        commandMapper.insert(record);
        if (properties.isEnabled()) {
            publishAfterCommit(record);
        }
        return response(record);
    }

    private void publishAfterCommit(PromotionAuctionCommandRecord record) {
        Runnable publish = () -> {
            messagePort.send(record.toCommand());
            commandMapper.updateStatus(record.getCommandId(), "PUBLISHED");
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publish.run();
                }
            });
        } else {
            publish.run();
            record.setStatus("PUBLISHED");
        }
    }

    private SubmitPromotionBidCommandResponse response(PromotionAuctionCommandRecord record) {
        return new SubmitPromotionBidCommandResponse(record.getCommandId(), String.valueOf(record.getAuctionWindowId()),
                record.getStatus(), false);
    }

    private PromotionCampaign requireOwnedCampaign(long campaignId, long userId) {
        PromotionCampaign campaign = campaignMapper.findById(campaignId);
        if (campaign == null || campaign.getCreatorUserId() != userId) {
            throw new BusinessException(ErrorCode.PROMOTION_CAMPAIGN_NOT_FOUND);
        }
        return campaign;
    }

    private void requireCampaignEligibleForWindow(PromotionCampaign campaign, PromotionAuctionWindow window) {
        Instant allocationStartAt = window.getWindowEndAt();
        long spanSeconds = window.getWindowEndAt().getEpochSecond() - window.getWindowStartAt().getEpochSecond();
        Instant allocationEndAt = allocationStartAt.plusSeconds(spanSeconds);
        if (campaign.getStatus() != PromotionCampaignStatus.ACTIVE
                || campaign.getStartAt() == null
                || campaign.getEndAt() == null
                || campaign.getStartAt().isAfter(allocationStartAt)
                || campaign.getEndAt().isBefore(allocationEndAt)) {
            throw new BusinessException(ErrorCode.PROMOTION_BID_WINDOW_CLOSED);
        }
    }

    String requestHash(long campaignId, long userId, long auctionWindowId, long bidAmount, String idempotencyKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String raw = campaignId + ":" + userId + ":" + auctionWindowId + ":" + bidAmount + ":" + idempotencyKey;
            return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
