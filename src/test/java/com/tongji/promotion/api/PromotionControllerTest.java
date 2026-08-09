package com.tongji.promotion.api;

import com.tongji.auth.token.JwtService;
import com.tongji.promotion.api.dto.CreatePromotionCampaignRequest;
import com.tongji.promotion.api.dto.PromotionCampaignResponse;
import com.tongji.promotion.bprime.service.PromotionBidEscrowService;
import com.tongji.promotion.bprime.service.PromotionSnapshotService;
import com.tongji.promotion.model.PromotionCampaign;
import com.tongji.promotion.model.PromotionCampaignStatus;
import com.tongji.promotion.model.PromotionResourceType;
import com.tongji.promotion.service.PromotionAllocationService;
import com.tongji.promotion.service.PromotionCampaignService;
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
    private JwtService jwtService;

    private PromotionController controller;

    @BeforeEach
    void setUp() {
        controller = new PromotionController(campaignService, bidEscrowService, snapshotService, allocationService,
                jwtService);
    }

    @Test
    void createCampaignDelegatesAndExposesPlacement() {
        Instant start = Instant.parse("2026-06-20T10:00:00Z");
        Instant end = Instant.parse("2026-06-20T11:00:00Z");
        when(jwtService.extractUserId(any())).thenReturn(42L);
        when(campaignService.createCampaign(eq(42L), eq(1001L),
                eq(PromotionResourceType.FEED_TOP_SLOT), eq(start), eq(end)))
                .thenReturn(campaign(201L, 42L, 1001L, PromotionResourceType.FEED_TOP_SLOT, start, end));

        PromotionCampaignResponse response = controller.createCampaign(
                new CreatePromotionCampaignRequest(1001L, "feed_top_slot", start, end), null);

        verify(campaignService).createCampaign(eq(42L), eq(1001L),
                eq(PromotionResourceType.FEED_TOP_SLOT), eq(start), eq(end));
        assertThat(response.resourceType()).isEqualTo("feed_top_slot");
    }

    @Test
    void getCampaignDelegatesToService() {
        when(campaignService.getCampaign(201L))
                .thenReturn(campaign(201L, 42L, 1001L, PromotionResourceType.SEARCH_TOP_SLOT,
                        Instant.parse("2026-06-20T10:00:00Z"), Instant.parse("2026-06-20T11:00:00Z")));

        PromotionCampaignResponse response = controller.getCampaign(201L);

        assertThat(response.id()).isEqualTo("201");
        assertThat(response.resourceType()).isEqualTo("search_top_slot");
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
}
