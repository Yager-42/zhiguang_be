package com.tongji.promotion.service;

import com.tongji.common.exception.BusinessException;
import com.tongji.common.exception.ErrorCode;
import com.tongji.common.id.IdNamespace;
import com.tongji.common.id.IdService;
import com.tongji.knowpost.mapper.KnowPostMapper;
import com.tongji.knowpost.model.KnowPost;
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

@Service
public class PromotionCampaignService {

    private static final String BID_REF_PREFIX = "promotion-bid:";

    private final PromotionCampaignMapper campaignMapper;
    private final PromotionAuctionWindowMapper windowMapper;
    private final PromotionBidMapper bidMapper;
    private final WalletService walletService;
    private final KnowPostMapper knowPostMapper;
    private final IdService idService;

    public PromotionCampaignService(PromotionCampaignMapper campaignMapper,
                                    PromotionAuctionWindowMapper windowMapper,
                                    PromotionBidMapper bidMapper,
                                    WalletService walletService,
                                    KnowPostMapper knowPostMapper,
                                    IdService idService) {
        this.campaignMapper = campaignMapper;
        this.windowMapper = windowMapper;
        this.bidMapper = bidMapper;
        this.walletService = walletService;
        this.knowPostMapper = knowPostMapper;
        this.idService = idService;
    }

    @Transactional
    public PromotionCampaign createCampaign(long creatorUserId, long postId, PromotionResourceType resourceType,
                                            Instant startAt, Instant endAt) {
        validateCampaignWindow(startAt, endAt);
        KnowPost post = requireOwnedEligiblePost(creatorUserId, postId);
        Instant now = Instant.now();
        PromotionCampaign campaign = PromotionCampaign.builder()
                .id(idService.nextId(IdNamespace.ADMIN_OPERATION))
                .creatorUserId(creatorUserId)
                .postId(post.getId())
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

    @Transactional
    public PromotionBid submitBid(long userId, long campaignId, long bidAmount, Instant now) {
        if (bidAmount <= 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "bidAmount must be greater than 0");
        }
        PromotionCampaign campaign = requireOwnedCampaign(campaignId, userId);
        PromotionAuctionWindow window = windowMapper.findOpenWindow(campaign.getResourceType(), now);
        if (window == null) {
            throw new BusinessException(ErrorCode.PROMOTION_BID_WINDOW_CLOSED);
        }
        requireCampaignEligibleForWindow(campaign, window);
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

    private void validateCampaignWindow(Instant startAt, Instant endAt) {
        if (startAt == null || endAt == null || !startAt.isBefore(endAt)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "promotion campaign window is invalid");
        }
    }

    private KnowPost requireOwnedEligiblePost(long creatorUserId, long postId) {
        KnowPost post = knowPostMapper.findById(postId);
        if (post == null || post.getCreatorId() == null || post.getCreatorId() != creatorUserId) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "promotion post is not eligible");
        }
        if (!"published".equals(post.getStatus()) || !"public".equals(post.getVisible())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "promotion post is not eligible");
        }
        return post;
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

    private PromotionCampaign requireOwnedCampaign(long campaignId, long userId) {
        PromotionCampaign campaign = campaignMapper.findById(campaignId);
        if (campaign == null || campaign.getCreatorUserId() != userId) {
            throw new BusinessException(ErrorCode.PROMOTION_CAMPAIGN_NOT_FOUND);
        }
        return campaign;
    }
}
