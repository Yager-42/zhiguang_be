package com.tongji.promotion.service;

import com.tongji.common.exception.BusinessException;
import com.tongji.common.exception.ErrorCode;
import com.tongji.common.id.IdNamespace;
import com.tongji.common.id.IdService;
import com.tongji.knowpost.mapper.KnowPostMapper;
import com.tongji.knowpost.model.KnowPost;
import com.tongji.promotion.bprime.mapper.PromotionBidEscrowMapper;
import com.tongji.promotion.bprime.model.PromotionBidEscrowRecord;
import com.tongji.promotion.mapper.PromotionAuctionWindowMapper;
import com.tongji.promotion.mapper.PromotionCampaignMapper;
import com.tongji.promotion.mapper.PromotionSlotAllocationMapper;
import com.tongji.promotion.model.PromotionAuctionWindow;
import com.tongji.promotion.model.PromotionAuctionWindowStatus;
import com.tongji.promotion.model.PromotionCampaign;
import com.tongji.promotion.model.PromotionCampaignDetails;
import com.tongji.promotion.model.PromotionCampaignStatus;
import com.tongji.promotion.model.PromotionParticipationOutcome;
import com.tongji.promotion.model.PromotionResourceType;
import com.tongji.promotion.model.PromotionSlotAllocation;
import com.tongji.profile.service.ProfileService;
import com.tongji.user.domain.User;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class PromotionCampaignService {

    private final PromotionCampaignMapper campaignMapper;
    private final KnowPostMapper knowPostMapper;
    private final IdService idService;
    private final ProfileService profileService;
    private final PromotionSlotAllocationMapper allocationMapper;
    private final PromotionBidEscrowMapper escrowMapper;
    private final PromotionAuctionWindowMapper windowMapper;
    private final Clock clock;

    @Autowired
    public PromotionCampaignService(PromotionCampaignMapper campaignMapper,
                                    KnowPostMapper knowPostMapper,
                                    IdService idService,
                                    ProfileService profileService,
                                    PromotionSlotAllocationMapper allocationMapper,
                                    PromotionBidEscrowMapper escrowMapper,
                                    PromotionAuctionWindowMapper windowMapper) {
        this(campaignMapper, knowPostMapper, idService, profileService, allocationMapper, escrowMapper,
                windowMapper, Clock.systemUTC());
    }

    PromotionCampaignService(PromotionCampaignMapper campaignMapper,
                             KnowPostMapper knowPostMapper,
                             IdService idService,
                             ProfileService profileService,
                             PromotionSlotAllocationMapper allocationMapper,
                             PromotionBidEscrowMapper escrowMapper,
                             PromotionAuctionWindowMapper windowMapper,
                             Clock clock) {
        this.campaignMapper = campaignMapper;
        this.knowPostMapper = knowPostMapper;
        this.idService = idService;
        this.profileService = profileService;
        this.allocationMapper = allocationMapper;
        this.escrowMapper = escrowMapper;
        this.windowMapper = windowMapper;
        this.clock = clock;
    }

    /**
     * 为系统生成的竞价场次登记参赛知文。同一用户、知文、资源位与展示时段重复报名时复用原记录。
     */
    @Transactional
    public PromotionCampaign getOrCreateParticipation(long creatorUserId, long postId,
                                                       PromotionResourceType resourceType,
                                                       PromotionAuctionWindow window) {
        if (window == null
                || window.getStatus() != PromotionAuctionWindowStatus.OPEN
                || window.getResourceType() != resourceType
                || window.getWindowStartAt() == null
                || window.getWindowEndAt() == null
                || !window.getWindowStartAt().isBefore(window.getWindowEndAt())) {
            throw new BusinessException(ErrorCode.PROMOTION_BID_WINDOW_CLOSED);
        }
        KnowPost post = requireOwnedEligiblePost(creatorUserId, postId);
        Duration roundDuration = Duration.between(window.getWindowStartAt(), window.getWindowEndAt());
        Instant placementStartAt = window.getWindowEndAt();
        Instant placementEndAt = placementStartAt.plus(roundDuration);
        PromotionCampaign existing = campaignMapper.findExactParticipation(
                creatorUserId, post.getId(), resourceType, placementStartAt, placementEndAt);
        if (existing != null) {
            return existing;
        }

        Instant now = Instant.now();
        PromotionCampaign participation = PromotionCampaign.builder()
                .id(idService.nextId(IdNamespace.ADMIN_OPERATION))
                .creatorUserId(creatorUserId)
                .postId(post.getId())
                .resourceType(resourceType)
                .status(PromotionCampaignStatus.ACTIVE)
                .startAt(placementStartAt)
                .endAt(placementEndAt)
                .createdAt(now)
                .updatedAt(now)
                .build();
        campaignMapper.insertParticipation(participation);
        PromotionCampaign persisted = campaignMapper.findExactParticipation(
                creatorUserId, post.getId(), resourceType, placementStartAt, placementEndAt);
        if (persisted == null) {
            throw new IllegalStateException("Failed to register promotion auction participation");
        }
        return persisted;
    }

    public PromotionCampaign getCampaign(long campaignId) {
        PromotionCampaign campaign = campaignMapper.findById(campaignId);
        if (campaign == null) {
            throw new BusinessException(ErrorCode.PROMOTION_CAMPAIGN_NOT_FOUND);
        }
        return campaign;
    }

    /** 查询单条推广参赛记录并补齐投稿人展示信息。 */
    public PromotionCampaignDetails getCampaignDetails(long campaignId) {
        return describeCampaigns(List.of(getCampaign(campaignId))).getFirst();
    }

    /**
     * 倒序查询指定投稿人的推广参赛记录。
     *
     * @param creatorUserId 已认证投稿人用户 ID
     * @param limit 本次最多返回数量，范围为 1 到 101
     * @param offset 从零开始的偏移量
     * @return 推广参赛记录详情列表，不返回 {@code null}
     */
    public List<PromotionCampaignDetails> listCampaigns(long creatorUserId, int limit, int offset) {
        if (limit < 1 || limit > 101 || offset < 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "promotion campaign pagination is invalid");
        }
        return describeCampaigns(campaignMapper.listByCreatorUserId(creatorUserId, limit, offset));
    }

    /** 将活动事实补齐为可展示详情。 */
    public PromotionCampaignDetails describe(PromotionCampaign campaign) {
        return describeCampaigns(List.of(campaign)).getFirst();
    }

    /**
     * 批量聚合参赛记录的结算事实，避免列表读取时逐条查询。
     *
     * <p>推荐周期只取 {@link PromotionSlotAllocation} 的实际起止时间；报名记录的起止时间仅用于
     * 关联系统场次，不得被当作已获得推荐位的展示周期。</p>
     */
    private List<PromotionCampaignDetails> describeCampaigns(List<PromotionCampaign> campaigns) {
        if (campaigns.isEmpty()) {
            return List.of();
        }
        List<Long> campaignIds = campaigns.stream().map(PromotionCampaign::getId).toList();
        Map<Long, PromotionSlotAllocation> allocations = allocationMapper.listByCampaignIds(campaignIds).stream()
                .collect(Collectors.toMap(PromotionSlotAllocation::getCampaignId, Function.identity(),
                        this::newerAllocation));
        Map<Long, PromotionAuctionWindowStatus> windowStatuses = activeWindowStatuses(campaignIds);
        Instant now = clock.instant();
        Map<Long, String> nicknames = campaigns.stream()
                .map(PromotionCampaign::getCreatorUserId)
                .distinct()
                .collect(Collectors.toMap(Function.identity(), this::creatorNickname));
        return campaigns.stream()
                .map(campaign -> detailsOf(campaign, nicknames.get(campaign.getCreatorUserId()),
                        allocations.get(campaign.getId()), windowStatuses.get(campaign.getId()), now))
                .toList();
    }

    private Map<Long, PromotionAuctionWindowStatus> activeWindowStatuses(List<Long> campaignIds) {
        Map<Long, PromotionBidEscrowRecord> escrows = escrowMapper.listByCampaignIds(campaignIds).stream()
                .collect(Collectors.toMap(PromotionBidEscrowRecord::getCampaignId, Function.identity(),
                        (first, ignored) -> first));
        List<Long> windowIds = escrows.values().stream()
                .map(PromotionBidEscrowRecord::getAuctionWindowId)
                .distinct()
                .toList();
        if (windowIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, PromotionAuctionWindowStatus> statusesByWindowId = windowMapper.listByIds(windowIds).stream()
                .collect(Collectors.toMap(PromotionAuctionWindow::getId, PromotionAuctionWindow::getStatus));
        return escrows.entrySet().stream()
                .filter(entry -> statusesByWindowId.containsKey(entry.getValue().getAuctionWindowId()))
                .collect(Collectors.toMap(Map.Entry::getKey,
                        entry -> statusesByWindowId.get(entry.getValue().getAuctionWindowId())));
    }

    private PromotionCampaignDetails detailsOf(PromotionCampaign campaign, String nickname,
                                                PromotionSlotAllocation allocation,
                                                PromotionAuctionWindowStatus windowStatus, Instant now) {
        return new PromotionCampaignDetails(campaign, nickname, outcomeOf(campaign, allocation, windowStatus, now),
                allocation);
    }

    private PromotionParticipationOutcome outcomeOf(PromotionCampaign campaign, PromotionSlotAllocation allocation,
                                                    PromotionAuctionWindowStatus windowStatus, Instant now) {
        if (allocation != null) {
            return PromotionParticipationOutcome.WON;
        }
        if (windowStatus == PromotionAuctionWindowStatus.SETTLED
                || (windowStatus == null && campaign.getStartAt() != null && !now.isBefore(campaign.getStartAt()))) {
            return PromotionParticipationOutcome.NOT_WON;
        }
        return PromotionParticipationOutcome.PENDING;
    }

    private PromotionSlotAllocation newerAllocation(PromotionSlotAllocation first, PromotionSlotAllocation second) {
        if (first.getAllocationStartAt() == null || second.getAllocationStartAt() == null) {
            return first;
        }
        return first.getAllocationStartAt().isAfter(second.getAllocationStartAt()) ? first : second;
    }

    private String creatorNickname(long creatorUserId) {
        return profileService.getById(creatorUserId)
                .map(User::getNickname)
                .filter(nickname -> !nickname.isBlank())
                .orElse("未知用户");
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
