package com.tongji.promotion.service;

import com.tongji.common.exception.BusinessException;
import com.tongji.common.exception.ErrorCode;
import com.tongji.common.id.IdNamespace;
import com.tongji.common.id.IdService;
import com.tongji.promotion.mapper.PromotionAuctionWindowMapper;
import com.tongji.promotion.mapper.PromotionBidMapper;
import com.tongji.promotion.mapper.PromotionCampaignMapper;
import com.tongji.promotion.model.PromotionAuctionWindow;
import com.tongji.promotion.model.PromotionBid;
import com.tongji.promotion.model.PromotionBidStatus;
import com.tongji.promotion.model.PromotionCampaign;
import com.tongji.promotion.model.PromotionCampaignStatus;
import com.tongji.promotion.model.PromotionResourceType;
import com.tongji.wallet.model.WalletBusinessType;
import com.tongji.wallet.model.WalletLedgerReason;
import com.tongji.wallet.service.WalletService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * 推广活动与出价写路径。
 * <p>{@code submitBid} 只读当前 OPEN 窗口（不隐式建窗，建窗由调度器负责），
 * 接单即冻结申报价；重复出价或窗口关闭直接拒绝。</p>
 */
@Service
public class PromotionCampaignService {

    private static final String BID_REF_PREFIX = "promotion-bid:";

    private final PromotionCampaignMapper campaignMapper;
    private final PromotionAuctionWindowMapper windowMapper;
    private final PromotionBidMapper bidMapper;
    private final WalletService walletService;
    private final IdService idService;

    public PromotionCampaignService(PromotionCampaignMapper campaignMapper,
                                    PromotionAuctionWindowMapper windowMapper,
                                    PromotionBidMapper bidMapper,
                                    WalletService walletService,
                                    IdService idService) {
        this.campaignMapper = campaignMapper;
        this.windowMapper = windowMapper;
        this.bidMapper = bidMapper;
        this.walletService = walletService;
        this.idService = idService;
    }

    @Transactional
    public PromotionCampaign createCampaign(long creatorUserId, long postId, PromotionResourceType resourceType,
                                            Instant startAt, Instant endAt) {
        Instant now = Instant.now();
        PromotionCampaign campaign = PromotionCampaign.builder()
                .id(idService.nextId(IdNamespace.ADMIN_OPERATION))
                .creatorUserId(creatorUserId)
                .postId(postId)
                .resourceType(resourceType)
                .status(PromotionCampaignStatus.ACTIVE)
                .startAt(startAt)
                .endAt(endAt)
                .createdAt(now)
                .updatedAt(now)
                .build();
        campaignMapper.insert(campaign);
        return campaign;
    }

    public PromotionCampaign getCampaign(long campaignId) {
        PromotionCampaign campaign = campaignMapper.findById(campaignId);
        if (campaign == null) {
            throw new BusinessException(ErrorCode.PROMOTION_CAMPAIGN_NOT_FOUND);
        }
        return campaign;
    }

    /**
     * 提交出价：校验归属与窗口，冻结申报价，写 ACTIVE 出价。
     */
    @Transactional
    public PromotionBid submitBid(long userId, long campaignId, long bidAmount, Instant now) {
        if (bidAmount <= 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "出价必须大于 0");
        }
        PromotionCampaign campaign = requireOwnedCampaign(campaignId, userId);
        PromotionAuctionWindow window = windowMapper.findOpenWindow(campaign.getResourceType(), now);
        if (window == null) {
            throw new BusinessException(ErrorCode.PROMOTION_BID_WINDOW_CLOSED);
        }
        if (bidMapper.findByCampaignIdAndAuctionWindowId(campaignId, window.getId()) != null) {
            throw new BusinessException(ErrorCode.PROMOTION_BID_DUPLICATE);
        }
        long bidId = idService.nextId(IdNamespace.ADMIN_OPERATION);
        String businessRef = BID_REF_PREFIX + bidId;
        walletService.hold(userId, bidAmount, WalletLedgerReason.HOLD_RESERVE,
                WalletBusinessType.PROMOTION, businessRef);
        PromotionBid bid = PromotionBid.builder()
                .id(bidId)
                .campaignId(campaignId)
                .auctionWindowId(window.getId())
                .bidderUserId(userId)
                .bidAmount(bidAmount)
                .walletBusinessRef(businessRef)
                .status(PromotionBidStatus.ACTIVE)
                .createdAt(now)
                .updatedAt(now)
                .build();
        bidMapper.insert(bid);
        return bid;
    }

    private PromotionCampaign requireOwnedCampaign(long campaignId, long userId) {
        PromotionCampaign campaign = campaignMapper.findById(campaignId);
        if (campaign == null || campaign.getCreatorUserId() != userId) {
            throw new BusinessException(ErrorCode.PROMOTION_CAMPAIGN_NOT_FOUND);
        }
        return campaign;
    }
}
