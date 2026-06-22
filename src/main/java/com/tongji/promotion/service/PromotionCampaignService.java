package com.tongji.promotion.service;

import com.tongji.common.exception.BusinessException;
import com.tongji.common.exception.ErrorCode;
import com.tongji.common.id.IdNamespace;
import com.tongji.common.id.IdService;
import com.tongji.knowpost.mapper.KnowPostMapper;
import com.tongji.knowpost.model.KnowPost;
import com.tongji.promotion.mapper.PromotionCampaignMapper;
import com.tongji.promotion.model.PromotionCampaign;
import com.tongji.promotion.model.PromotionCampaignStatus;
import com.tongji.promotion.model.PromotionResourceType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
public class PromotionCampaignService {

    private final PromotionCampaignMapper campaignMapper;
    private final KnowPostMapper knowPostMapper;
    private final IdService idService;

    public PromotionCampaignService(PromotionCampaignMapper campaignMapper,
                                    KnowPostMapper knowPostMapper,
                                    IdService idService) {
        this.campaignMapper = campaignMapper;
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

}
