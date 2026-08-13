package com.tongji.promotion.bprime.service;

import com.tongji.promotion.api.dto.PromotionBidEscrowAuthorizationResponse;
import com.tongji.common.exception.BusinessException;
import com.tongji.common.exception.ErrorCode;
import com.tongji.promotion.bprime.model.PromotionBidEscrowRecord;
import org.springframework.stereotype.Service;

import java.time.Instant;

/** MySQL 预授权完成后，通过同窗口顺序命令把授权投影到 Redis 热状态。 */
@Service
public class PromotionBidEscrowService {

    private final PromotionBidEscrowTransactionService transactionService;
    private final PromotionAuctionHotStateLifecycle hotStateLifecycle;

    public PromotionBidEscrowService(PromotionBidEscrowTransactionService transactionService,
                                     PromotionAuctionHotStateLifecycle hotStateLifecycle) {
        this.transactionService = transactionService;
        this.hotStateLifecycle = hotStateLifecycle;
    }

    public PromotionBidEscrowAuthorizationResponse authorize(long userId, long campaignId, long amount, Instant now) {
        PromotionBidEscrowTransactionService.Authorization authorization =
                transactionService.authorize(userId, campaignId, amount, now);
        PromotionBidEscrowRecord escrow = authorization.escrow();
        try {
            hotStateLifecycle.makeAuthorizationReady(authorization, now);
        } catch (RuntimeException exception) {
            throw new BusinessException(ErrorCode.PROMOTION_AUCTION_PAUSED,
                    "escrow authorized; Redis projection pending retry");
        }
        return new PromotionBidEscrowAuthorizationResponse(
                String.valueOf(escrow.getAuctionWindowId()),
                String.valueOf(campaignId),
                escrow.getAuthorizedAmount(),
                escrow.getCurrentHold(),
                escrow.getStatus());
    }
}
