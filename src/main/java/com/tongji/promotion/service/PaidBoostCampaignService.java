package com.tongji.promotion.service;

import com.tongji.common.exception.BusinessException;
import com.tongji.common.exception.ErrorCode;
import com.tongji.common.id.IdNamespace;
import com.tongji.common.id.IdService;
import com.tongji.knowpost.mapper.KnowPostMapper;
import com.tongji.knowpost.model.KnowPost;
import com.tongji.promotion.mapper.PaidBoostCampaignMapper;
import com.tongji.promotion.model.PaidBoostCampaign;
import com.tongji.promotion.model.PaidBoostCampaignStatus;
import com.tongji.promotion.model.PaidBoostChannel;
import com.tongji.wallet.model.WalletBusinessType;
import com.tongji.wallet.model.WalletLedgerReason;
import com.tongji.wallet.service.WalletService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * boost 活动写路径：单接口创建活动，接收 {@code bidAmount}（非最终 boost 值）。
 * <p>创建即冻结总预算（wallet hold），状态置 ACTIVE；不产 winner / GSP / 槽位分配。
 * 有效 boost 值由 {@link PaidBoostQuoteService} 产出，不直接用请求体出价入库。</p>
 */
@Service
public class PaidBoostCampaignService {

    private static final String RESERVE_REF_PREFIX = "paid-boost:";
    private static final String RESERVE_REF_SUFFIX = ":reserve";

    private final PaidBoostCampaignMapper campaignMapper;
    private final PaidBoostQuoteService quoteService;
    private final WalletService walletService;
    private final KnowPostMapper knowPostMapper;
    private final IdService idService;

    public PaidBoostCampaignService(PaidBoostCampaignMapper campaignMapper,
                                    PaidBoostQuoteService quoteService,
                                    WalletService walletService,
                                    KnowPostMapper knowPostMapper,
                                    IdService idService) {
        this.campaignMapper = campaignMapper;
        this.quoteService = quoteService;
        this.walletService = walletService;
        this.knowPostMapper = knowPostMapper;
        this.idService = idService;
    }

    @Transactional
    public PaidBoostCampaign createCampaign(long creatorUserId, long postId, PaidBoostChannel channel,
                                            long bidAmount, long unitPrice, long budgetTotal,
                                            Instant startAt, Instant endAt) {
        validate(bidAmount, unitPrice, budgetTotal, startAt, endAt);
        if (channel == null) {
            throw new BusinessException(ErrorCode.PAID_BOOST_INVALID_CHANNEL);
        }
        KnowPost post = requireOwnedEligiblePost(creatorUserId, postId, channel);
        Instant now = Instant.now();
        long id = idService.nextId(IdNamespace.ADMIN_OPERATION);
        long effectiveBoostValue = quoteService.quote(channel, bidAmount);
        String reserveRef = reserveRef(id);
        walletService.hold(creatorUserId, budgetTotal, WalletLedgerReason.HOLD_RESERVE,
                WalletBusinessType.PROMOTION, reserveRef);
        PaidBoostCampaign campaign = PaidBoostCampaign.builder()
                .id(id)
                .creatorUserId(creatorUserId)
                .postId(post.getId())
                .channel(channel)
                .bidAmount(bidAmount)
                .boostValue(effectiveBoostValue)
                .unitPrice(unitPrice)
                .budgetTotal(budgetTotal)
                .budgetConsumed(0L)
                .reserveBusinessRef(reserveRef)
                .status(PaidBoostCampaignStatus.ACTIVE)
                .startAt(startAt)
                .endAt(endAt)
                .createdAt(now)
                .updatedAt(now)
                .build();
        campaignMapper.insert(campaign);
        return campaign;
    }

    public PaidBoostCampaign getCampaign(long campaignId) {
        PaidBoostCampaign campaign = campaignMapper.findById(campaignId);
        if (campaign == null) {
            throw new BusinessException(ErrorCode.PAID_BOOST_CAMPAIGN_NOT_FOUND);
        }
        return campaign;
    }

    private void validate(long bidAmount, long unitPrice, long budgetTotal, Instant startAt, Instant endAt) {
        if (bidAmount <= 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "出价必须大于 0");
        }
        if (unitPrice <= 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "单价必须大于 0");
        }
        if (budgetTotal < unitPrice) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "预算必须不小于单价");
        }
        if (startAt == null || endAt == null || !startAt.isBefore(endAt)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "投放窗口非法");
        }
    }

    private KnowPost requireOwnedEligiblePost(long creatorUserId, long postId, PaidBoostChannel channel) {
        KnowPost post = knowPostMapper.findById(postId);
        if (post == null || post.getCreatorId() == null || post.getCreatorId() != creatorUserId) {
            throw new BusinessException(ErrorCode.PAID_BOOST_POST_NOT_ELIGIBLE);
        }
        if (!"published".equals(post.getStatus())) {
            throw new BusinessException(ErrorCode.PAID_BOOST_POST_NOT_ELIGIBLE);
        }
        String visible = post.getVisible();
        if (channel == PaidBoostChannel.HOME_RECOMMENDATION) {
            if (!"public".equals(visible)) {
                throw new BusinessException(ErrorCode.PAID_BOOST_POST_NOT_ELIGIBLE);
            }
        } else {
            // FOLLOW_DELIVERY 允许 public / followers
            if (!"public".equals(visible) && !"followers".equals(visible)) {
                throw new BusinessException(ErrorCode.PAID_BOOST_POST_NOT_ELIGIBLE);
            }
        }
        return post;
    }

    static String reserveRef(long campaignId) {
        return RESERVE_REF_PREFIX + campaignId + RESERVE_REF_SUFFIX;
    }
}
