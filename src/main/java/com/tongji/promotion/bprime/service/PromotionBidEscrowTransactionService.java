package com.tongji.promotion.bprime.service;

import com.tongji.common.exception.BusinessException;
import com.tongji.common.exception.ErrorCode;
import com.tongji.common.id.IdNamespace;
import com.tongji.common.id.IdService;
import com.tongji.promotion.bprime.config.PromotionBPrimeProperties;
import com.tongji.promotion.bprime.mapper.PromotionBidEscrowMapper;
import com.tongji.promotion.bprime.model.PromotionBidEscrowRecord;
import com.tongji.promotion.mapper.PromotionAuctionWindowMapper;
import com.tongji.promotion.mapper.PromotionCampaignMapper;
import com.tongji.promotion.model.PromotionAuctionWindow;
import com.tongji.promotion.model.PromotionCampaign;
import com.tongji.promotion.model.PromotionCampaignStatus;
import com.tongji.reconciliation.model.ReconciliationTask;
import com.tongji.reconciliation.model.ReconciliationTaskType;
import com.tongji.reconciliation.model.ReconciliationTargetType;
import com.tongji.reconciliation.service.ReconciliationService;
import com.tongji.wallet.model.WalletBusinessType;
import com.tongji.wallet.model.WalletLedgerReason;
import com.tongji.wallet.service.WalletService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/** 低频保证金授权事务；同 campaign 行锁串行化首次授权与追加授权。 */
@Service
public class PromotionBidEscrowTransactionService {

    private final PromotionCampaignMapper campaignMapper;
    private final PromotionAuctionWindowMapper windowMapper;
    private final PromotionBidEscrowMapper escrowMapper;
    private final WalletService walletService;
    private final IdService idService;
    private final ReconciliationService reconciliationService;
    private final PromotionBPrimeProperties properties;

    public PromotionBidEscrowTransactionService(PromotionCampaignMapper campaignMapper,
                                                PromotionAuctionWindowMapper windowMapper,
                                                PromotionBidEscrowMapper escrowMapper,
                                                WalletService walletService,
                                                IdService idService,
                                                ReconciliationService reconciliationService,
                                                PromotionBPrimeProperties properties) {
        this.campaignMapper = campaignMapper;
        this.windowMapper = windowMapper;
        this.escrowMapper = escrowMapper;
        this.walletService = walletService;
        this.idService = idService;
        this.reconciliationService = reconciliationService;
        this.properties = properties;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Authorization authorize(long userId, long campaignId, long requestedAmount, Instant now) {
        if (!properties.isEnabled()) {
            throw new BusinessException(ErrorCode.PROMOTION_AUCTION_PAUSED);
        }
        if (requestedAmount <= 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "amount must be greater than 0");
        }
        PromotionCampaign campaign = campaignMapper.findByIdForUpdate(campaignId);
        if (campaign == null || campaign.getCreatorUserId() != userId) {
            throw new BusinessException(ErrorCode.PROMOTION_CAMPAIGN_NOT_FOUND);
        }
        PromotionAuctionWindow window = windowMapper.findOpenWindow(campaign.getResourceType(), now);
        if (window == null) {
            throw new BusinessException(ErrorCode.PROMOTION_BID_WINDOW_CLOSED);
        }
        requireCampaignEligibleForWindow(campaign, window);

        PromotionBidEscrowRecord existing = escrowMapper.findByWindowAndCampaign(window.getId(), campaignId);
        if (existing != null && (existing.getBidderUserId() != userId || !"ACTIVE".equals(existing.getStatus()))) {
            throw new BusinessException(ErrorCode.PROMOTION_BID_ESCROW_REQUIRED,
                    "promotion bid escrow is not active");
        }
        long currentAuthorized = existing == null ? 0L : existing.getAuthorizedAmount();
        long authorizedAmount = Math.max(currentAuthorized, requestedAmount);
        long delta = authorizedAmount - currentAuthorized;
        if (delta > 0) {
            walletService.hold(userId, delta, WalletLedgerReason.PROMOTION_BPRIME_HOLD,
                    WalletBusinessType.PROMOTION, authorizationBusinessRef(window.getId(), campaignId,
                            authorizedAmount));
        }

        if (existing == null) {
            existing = PromotionBidEscrowRecord.builder()
                    .id(idService.nextId(IdNamespace.PROMOTION_ESCROW))
                    .auctionWindowId(window.getId())
                    .campaignId(campaignId)
                    .bidderUserId(userId)
                    .authorizedAmount(authorizedAmount)
                    .currentHold(0L)
                    .status("ACTIVE")
                    .expiresAt(window.getWindowEndAt())
                    .createdAt(now)
                    .updatedAt(now)
                    .build();
            escrowMapper.insert(existing);
        } else if (delta > 0) {
            if (escrowMapper.increaseAuthorization(window.getId(), campaignId, authorizedAmount,
                    window.getWindowEndAt(), now) != 1) {
                throw new IllegalStateException("Failed to increase promotion bid escrow authorization");
            }
            existing.setAuthorizedAmount(authorizedAmount);
            existing.setExpiresAt(window.getWindowEndAt());
            existing.setUpdatedAt(now);
        }
        ReconciliationTask projectionTask = reconciliationService.createTaskIfAbsent(
                ReconciliationTaskType.PROMOTION_ESCROW_REDIS_PROJECTION,
                ReconciliationTargetType.PROMOTION_BID_ESCROW,
                existing.getId());
        return new Authorization(campaign, window, existing, projectionTask);
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

    private String authorizationBusinessRef(long auctionWindowId, long campaignId, long authorizedAmount) {
        return "promotion-bprime:escrow:" + auctionWindowId + ":" + campaignId + ":authorize:"
                + authorizedAmount;
    }

    public record Authorization(PromotionCampaign campaign,
                                PromotionAuctionWindow window,
                                PromotionBidEscrowRecord escrow,
                                ReconciliationTask projectionTask) {
    }
}
