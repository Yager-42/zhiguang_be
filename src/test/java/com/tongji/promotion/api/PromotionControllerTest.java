package com.tongji.promotion.api;

import com.tongji.auth.token.JwtService;
import com.tongji.promotion.api.dto.EnterCurrentPromotionAuctionRequest;
import com.tongji.promotion.api.dto.PromotionAuctionEntryResponse;
import com.tongji.promotion.api.dto.PromotionCampaignResponse;
import com.tongji.promotion.api.dto.PromotionCampaignListResponse;
import com.tongji.promotion.bprime.service.PromotionBidEscrowService;
import com.tongji.promotion.bprime.service.PromotionSnapshotService;
import com.tongji.promotion.bprime.model.PromotionAuctionSnapshot;
import com.tongji.promotion.model.PromotionAuctionWindow;
import com.tongji.promotion.model.PromotionAuctionWindowStatus;
import com.tongji.promotion.model.PromotionCampaign;
import com.tongji.promotion.model.PromotionCampaignDetails;
import com.tongji.promotion.model.PromotionParticipationOutcome;
import com.tongji.promotion.model.PromotionCampaignStatus;
import com.tongji.promotion.model.PromotionResourceType;
import com.tongji.promotion.service.PromotionAllocationService;
import com.tongji.promotion.service.PromotionCampaignService;
import com.tongji.promotion.service.PromotionAuctionWindowService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PromotionControllerTest {

    @Mock
    private PromotionCampaignService campaignService;

    @Mock
    private PromotionBidEscrowService bidEscrowService;

    @Mock
    private PromotionSnapshotService snapshotService;

    @Mock
    private PromotionAllocationService allocationService;

    @Mock
    private PromotionAuctionWindowService windowService;

    @Mock
    private JwtService jwtService;

    private PromotionController controller;

    @BeforeEach
    void setUp() {
        controller = new PromotionController(campaignService, bidEscrowService, snapshotService, allocationService,
                windowService, jwtService);
    }

    @Test
    void getCampaignDelegatesToService() {
        PromotionCampaign campaign = campaign(201L, 42L, 1001L, PromotionResourceType.SEARCH_TOP_SLOT,
                Instant.parse("2026-06-20T10:00:00Z"), Instant.parse("2026-06-20T11:00:00Z"));
        when(campaignService.getCampaignDetails(201L))
                .thenReturn(details(campaign, "林墨"));

        PromotionCampaignResponse response = controller.getCampaign(201L);

        assertThat(response.id()).isEqualTo("201");
        assertThat(response.resourceType()).isEqualTo("search_top_slot");
        assertThat(response.creatorUserId()).isEqualTo("42");
        assertThat(response.creatorNickname()).isEqualTo("林墨");
    }

    @Test
    void listMyCampaignsReturnsCreatorDetailsAndHasMore() {
        when(jwtService.extractUserId(any())).thenReturn(42L);
        Instant start = Instant.parse("2026-06-20T10:00:00Z");
        PromotionCampaign first = campaign(202L, 42L, 1002L, PromotionResourceType.FEED_TOP_SLOT,
                start, start.plusSeconds(3600));
        PromotionCampaign second = campaign(201L, 42L, 1001L, PromotionResourceType.SEARCH_TOP_SLOT,
                start.minusSeconds(3600), start);
        when(campaignService.listCampaigns(42L, 2, 0)).thenReturn(java.util.List.of(
                details(first, "周屿"),
                details(second, "周屿")));

        PromotionCampaignListResponse response = controller.listMyCampaigns(null, 1, 0);

        assertThat(response.items()).hasSize(1);
        assertThat(response.items().getFirst().id()).isEqualTo("202");
        assertThat(response.hasMore()).isTrue();
    }

    @Test
    void currentSnapshotResolvesCurrentWindowByResource() {
        PromotionAuctionWindow window = PromotionAuctionWindow.builder()
                .id(301L)
                .resourceType(PromotionResourceType.FEED_TOP_SLOT)
                .status(PromotionAuctionWindowStatus.OPEN)
                .build();
        PromotionAuctionSnapshot snapshot = new PromotionAuctionSnapshot(
                "301", "OPEN", java.util.List.of(), Instant.now(), 0L);
        when(windowService.getCurrentOpenWindow(PromotionResourceType.FEED_TOP_SLOT)).thenReturn(window);
        when(snapshotService.snapshot(301L)).thenReturn(snapshot);

        PromotionAuctionSnapshot result = controller.currentSnapshot("feed_top_slot");

        assertThat(result.auctionWindowId()).isEqualTo("301");
    }

    @Test
    void enterCurrentAuctionCreatesParticipationWithoutFreezingBudget() {
        Instant start = Instant.parse("2026-06-20T10:00:00Z");
        PromotionAuctionWindow window = PromotionAuctionWindow.builder()
                .id(301L)
                .resourceType(PromotionResourceType.FEED_TOP_SLOT)
                .windowStartAt(start)
                .windowEndAt(start.plusSeconds(3600))
                .status(PromotionAuctionWindowStatus.OPEN)
                .build();
        PromotionCampaign participation = campaign(201L, 42L, 1001L,
                PromotionResourceType.FEED_TOP_SLOT, start.plusSeconds(3600), start.plusSeconds(7200));
        when(jwtService.extractUserId(any())).thenReturn(42L);
        when(windowService.getCurrentOpenWindow(PromotionResourceType.FEED_TOP_SLOT)).thenReturn(window);
        when(campaignService.getOrCreateParticipation(42L, 1001L,
                PromotionResourceType.FEED_TOP_SLOT, window)).thenReturn(participation);
        when(campaignService.describe(participation))
                .thenReturn(details(participation, "周屿"));

        PromotionAuctionEntryResponse response = controller.enterCurrentAuction(
                new EnterCurrentPromotionAuctionRequest(1001L, "feed_top_slot"), null);

        assertThat(response.participation().id()).isEqualTo("201");
        assertThat(response.auctionWindowId()).isEqualTo("301");
        verifyNoInteractions(bidEscrowService);
    }

    @Test
    void getActiveAllocationsDelegatesToAllocationService() {
        when(allocationService.getActive(PromotionResourceType.FEED_TOP_SLOT))
                .thenReturn(java.util.List.of(new com.tongji.promotion.api.dto.PromotionAllocationView(
                        "201", "feed_top_slot", "301", "401")));

        java.util.List<com.tongji.promotion.api.dto.PromotionAllocationView> result =
                controller.getActiveAllocations("feed_top_slot");

        verify(allocationService).getActive(PromotionResourceType.FEED_TOP_SLOT);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).placementType()).isEqualTo("feed_top_slot");
    }

    private PromotionCampaign campaign(long id, long creator, long post, PromotionResourceType type,
                                       Instant start, Instant end) {
        return PromotionCampaign.builder()
                .id(id).creatorUserId(creator).postId(post).resourceType(type)
                .status(PromotionCampaignStatus.ACTIVE).startAt(start).endAt(end)
                .createdAt(start).updatedAt(start).build();
    }

    private PromotionCampaignDetails details(PromotionCampaign campaign, String nickname) {
        return new PromotionCampaignDetails(campaign, nickname, PromotionParticipationOutcome.PENDING, null);
    }
}
