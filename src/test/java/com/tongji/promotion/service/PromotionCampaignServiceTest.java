package com.tongji.promotion.service;

import com.tongji.common.id.IdNamespace;
import com.tongji.common.id.IdService;
import com.tongji.knowpost.mapper.KnowPostMapper;
import com.tongji.knowpost.model.KnowPost;
import com.tongji.promotion.bprime.mapper.PromotionBidEscrowMapper;
import com.tongji.promotion.mapper.PromotionAuctionWindowMapper;
import com.tongji.promotion.mapper.PromotionCampaignMapper;
import com.tongji.promotion.mapper.PromotionSlotAllocationMapper;
import com.tongji.promotion.model.PromotionAuctionWindow;
import com.tongji.promotion.model.PromotionAuctionWindowStatus;
import com.tongji.promotion.model.PromotionCampaign;
import com.tongji.promotion.model.PromotionCampaignStatus;
import com.tongji.promotion.model.PromotionResourceType;
import com.tongji.profile.service.ProfileService;
import com.tongji.user.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PromotionCampaignServiceTest {

    @Mock
    private PromotionCampaignMapper campaignMapper;

    @Mock
    private KnowPostMapper knowPostMapper;

    @Mock
    private IdService idService;

    @Mock
    private ProfileService profileService;

    @Mock
    private PromotionSlotAllocationMapper allocationMapper;

    @Mock
    private PromotionBidEscrowMapper escrowMapper;

    @Mock
    private PromotionAuctionWindowMapper windowMapper;

    private PromotionCampaignService service;

    @BeforeEach
    void setUp() {
        service = new PromotionCampaignService(campaignMapper, knowPostMapper, idService, profileService,
                allocationMapper, escrowMapper, windowMapper,
                Clock.fixed(Instant.parse("2026-06-20T10:30:00Z"), java.time.ZoneOffset.UTC));
    }

    @Test
    void getOrCreateParticipationAlignsPlacementToSystemRound() {
        PromotionAuctionWindow window = PromotionAuctionWindow.builder()
                .id(301L)
                .resourceType(PromotionResourceType.FEED_TOP_SLOT)
                .windowStartAt(Instant.parse("2026-06-20T10:00:00Z"))
                .windowEndAt(Instant.parse("2026-06-20T11:00:00Z"))
                .status(PromotionAuctionWindowStatus.OPEN)
                .build();
        PromotionCampaign persisted = campaignWithStatus(201L, 42L, 1001L,
                PromotionResourceType.FEED_TOP_SLOT, PromotionCampaignStatus.ACTIVE,
                "2026-06-20T11:00:00Z", "2026-06-20T12:00:00Z");
        when(knowPostMapper.findById(1001L)).thenReturn(post(1001L, 42L, "published", "public"));
        when(campaignMapper.findExactParticipation(42L, 1001L, PromotionResourceType.FEED_TOP_SLOT,
                Instant.parse("2026-06-20T11:00:00Z"), Instant.parse("2026-06-20T12:00:00Z")))
                .thenReturn(null, persisted);
        when(idService.nextId(IdNamespace.ADMIN_OPERATION)).thenReturn(201L);

        PromotionCampaign result = service.getOrCreateParticipation(
                42L, 1001L, PromotionResourceType.FEED_TOP_SLOT, window);

        assertThat(result).isSameAs(persisted);
        verify(campaignMapper).insertParticipation(any(PromotionCampaign.class));
    }

    @Test
    void getOrCreateParticipationReusesExistingRoundEntry() {
        PromotionAuctionWindow window = PromotionAuctionWindow.builder()
                .id(301L)
                .resourceType(PromotionResourceType.FEED_TOP_SLOT)
                .windowStartAt(Instant.parse("2026-06-20T10:00:00Z"))
                .windowEndAt(Instant.parse("2026-06-20T11:00:00Z"))
                .status(PromotionAuctionWindowStatus.OPEN)
                .build();
        PromotionCampaign existing = campaignWithStatus(201L, 42L, 1001L,
                PromotionResourceType.FEED_TOP_SLOT, PromotionCampaignStatus.ACTIVE,
                "2026-06-20T11:00:00Z", "2026-06-20T12:00:00Z");
        when(knowPostMapper.findById(1001L)).thenReturn(post(1001L, 42L, "published", "public"));
        when(campaignMapper.findExactParticipation(42L, 1001L, PromotionResourceType.FEED_TOP_SLOT,
                existing.getStartAt(), existing.getEndAt())).thenReturn(existing);

        PromotionCampaign result = service.getOrCreateParticipation(
                42L, 1001L, PromotionResourceType.FEED_TOP_SLOT, window);

        assertThat(result).isSameAs(existing);
        verify(campaignMapper, never()).insertParticipation(any(PromotionCampaign.class));
        verify(idService, never()).nextId(any());
    }

    @Test
    void listCampaignsAddsCreatorNicknameWithoutPerItemProfileQueries() {
        PromotionCampaign first = campaignWithStatus(202L, 42L, 1002L, PromotionResourceType.FEED_TOP_SLOT,
                PromotionCampaignStatus.ACTIVE, "2026-06-20T11:00:00Z", "2026-06-20T12:00:00Z");
        PromotionCampaign second = campaignWithStatus(201L, 42L, 1001L, PromotionResourceType.SEARCH_TOP_SLOT,
                PromotionCampaignStatus.ACTIVE, "2026-06-20T10:00:00Z", "2026-06-20T11:00:00Z");
        when(profileService.getById(42L)).thenReturn(Optional.of(User.builder().id(42L).nickname("周屿").build()));
        when(campaignMapper.listByCreatorUserId(42L, 21, 0)).thenReturn(List.of(first, second));
        when(allocationMapper.listByCampaignIds(List.of(202L, 201L))).thenReturn(List.of());
        when(escrowMapper.listByCampaignIds(List.of(202L, 201L))).thenReturn(List.of());

        var result = service.listCampaigns(42L, 21, 0);

        assertThat(result).hasSize(2);
        assertThat(result).allMatch(item -> item.creatorNickname().equals("周屿"));
        verify(profileService).getById(42L);
    }

    @Test
    void listCampaignsUsesAllocationAsTheOnlyWinningRecommendationPeriod() {
        PromotionCampaign campaign = campaignWithStatus(202L, 42L, 1002L, PromotionResourceType.FEED_TOP_SLOT,
                PromotionCampaignStatus.ACTIVE, "2026-06-20T11:00:00Z", "2026-06-20T12:00:00Z");
        com.tongji.promotion.model.PromotionSlotAllocation allocation =
                com.tongji.promotion.model.PromotionSlotAllocation.builder()
                        .campaignId(202L)
                        .allocationStartAt(Instant.parse("2026-06-20T11:00:00Z"))
                        .allocationEndAt(Instant.parse("2026-06-20T12:00:00Z"))
                        .build();
        when(campaignMapper.listByCreatorUserId(42L, 20, 0)).thenReturn(List.of(campaign));
        when(allocationMapper.listByCampaignIds(List.of(202L))).thenReturn(List.of(allocation));
        when(escrowMapper.listByCampaignIds(List.of(202L))).thenReturn(List.of());
        when(profileService.getById(42L)).thenReturn(Optional.of(User.builder().id(42L).nickname("周屿").build()));

        var result = service.listCampaigns(42L, 20, 0).getFirst();

        assertThat(result.participationOutcome().name()).isEqualTo("WON");
        assertThat(result.allocation()).isSameAs(allocation);
    }

    private PromotionCampaign campaignWithStatus(long id, long creator, long post, PromotionResourceType type,
                                                 PromotionCampaignStatus status, String startAt, String endAt) {
        return PromotionCampaign.builder()
                .id(id)
                .creatorUserId(creator)
                .postId(post)
                .resourceType(type)
                .status(status)
                .startAt(Instant.parse(startAt))
                .endAt(Instant.parse(endAt))
                .createdAt(Instant.parse(startAt))
                .updatedAt(Instant.parse(startAt))
                .build();
    }

    private KnowPost post(long id, long creatorId, String status, String visible) {
        return KnowPost.builder()
                .id(id)
                .creatorId(creatorId)
                .status(status)
                .visible(visible)
                .build();
    }
}
