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
import com.tongji.promotion.model.PromotionAuctionWindowStatus;
import com.tongji.promotion.model.PromotionBid;
import com.tongji.promotion.model.PromotionCampaign;
import com.tongji.promotion.model.PromotionCampaignStatus;
import com.tongji.promotion.model.PromotionResourceType;
import com.tongji.wallet.model.WalletBusinessType;
import com.tongji.wallet.model.WalletLedgerReason;
import com.tongji.wallet.service.WalletService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PromotionCampaignServiceTest {

    @Mock
    private PromotionCampaignMapper campaignMapper;

    @Mock
    private PromotionAuctionWindowMapper windowMapper;

    @Mock
    private PromotionBidMapper bidMapper;

    @Mock
    private WalletService walletService;

    @Mock
    private KnowPostMapper knowPostMapper;

    @Mock
    private IdService idService;

    private PromotionCampaignService service;

    @BeforeEach
    void setUp() {
        service = new PromotionCampaignService(campaignMapper, windowMapper, bidMapper, walletService, knowPostMapper,
                idService);
    }

    @Test
    void createCampaignInsertsOwnedPublishedPublicPost() {
        Instant start = Instant.parse("2026-06-20T10:00:00Z");
        Instant end = Instant.parse("2026-06-20T11:00:00Z");
        when(knowPostMapper.findById(1001L)).thenReturn(post(1001L, 42L, "published", "public"));
        when(idService.nextId(IdNamespace.ADMIN_OPERATION)).thenReturn(201L);

        PromotionCampaign campaign = service.createCampaign(42L, 1001L, PromotionResourceType.FEED_TOP_SLOT, start, end);

        assertThat(campaign.getId()).isEqualTo(201L);
        assertThat(campaign.getPostId()).isEqualTo(1001L);
        assertThat(campaign.getStatus()).isEqualTo(PromotionCampaignStatus.ACTIVE);
        verify(campaignMapper).insert(any(PromotionCampaign.class));
    }

    @Test
    void createCampaignRejectsMissingPost() {
        when(knowPostMapper.findById(1001L)).thenReturn(null);

        assertThatThrownBy(() -> service.createCampaign(42L, 1001L, PromotionResourceType.FEED_TOP_SLOT,
                Instant.parse("2026-06-20T10:00:00Z"),
                Instant.parse("2026-06-20T11:00:00Z")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.BAD_REQUEST);
        verify(campaignMapper, never()).insert(any(PromotionCampaign.class));
    }

    @Test
    void createCampaignRejectsPostNotOwnedByCreator() {
        when(knowPostMapper.findById(1001L)).thenReturn(post(1001L, 99L, "published", "public"));

        assertThatThrownBy(() -> service.createCampaign(42L, 1001L, PromotionResourceType.FEED_TOP_SLOT,
                Instant.parse("2026-06-20T10:00:00Z"),
                Instant.parse("2026-06-20T11:00:00Z")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.BAD_REQUEST);
        verify(campaignMapper, never()).insert(any(PromotionCampaign.class));
    }

    @Test
    void createCampaignRejectsUnpublishedPost() {
        when(knowPostMapper.findById(1001L)).thenReturn(post(1001L, 42L, "draft", "public"));

        assertThatThrownBy(() -> service.createCampaign(42L, 1001L, PromotionResourceType.SEARCH_TOP_SLOT,
                Instant.parse("2026-06-20T10:00:00Z"),
                Instant.parse("2026-06-20T11:00:00Z")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.BAD_REQUEST);
        verify(campaignMapper, never()).insert(any(PromotionCampaign.class));
    }

    @Test
    void createCampaignRejectsNonPublicPostForFeedTopSlot() {
        when(knowPostMapper.findById(1001L)).thenReturn(post(1001L, 42L, "published", "followers"));

        assertThatThrownBy(() -> service.createCampaign(42L, 1001L, PromotionResourceType.FEED_TOP_SLOT,
                Instant.parse("2026-06-20T10:00:00Z"),
                Instant.parse("2026-06-20T11:00:00Z")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.BAD_REQUEST);
        verify(campaignMapper, never()).insert(any(PromotionCampaign.class));
    }

    @Test
    void createCampaignRejectsNonPublicPostForSearchTopSlot() {
        when(knowPostMapper.findById(1001L)).thenReturn(post(1001L, 42L, "published", "followers"));

        assertThatThrownBy(() -> service.createCampaign(42L, 1001L, PromotionResourceType.SEARCH_TOP_SLOT,
                Instant.parse("2026-06-20T10:00:00Z"),
                Instant.parse("2026-06-20T11:00:00Z")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.BAD_REQUEST);
        verify(campaignMapper, never()).insert(any(PromotionCampaign.class));
    }

    @Test
    void createCampaignRejectsNullStartAt() {
        assertThatThrownBy(() -> service.createCampaign(42L, 1001L, PromotionResourceType.FEED_TOP_SLOT,
                null,
                Instant.parse("2026-06-20T11:00:00Z")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.BAD_REQUEST);
        verify(knowPostMapper, never()).findById(anyLong());
    }

    @Test
    void createCampaignRejectsInvalidWindow() {
        assertThatThrownBy(() -> service.createCampaign(42L, 1001L, PromotionResourceType.FEED_TOP_SLOT,
                Instant.parse("2026-06-20T11:00:00Z"),
                Instant.parse("2026-06-20T10:00:00Z")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.BAD_REQUEST);
        verify(knowPostMapper, never()).findById(anyLong());
    }

    @Test
    void submitBidRejectsCampaignThatStartsAfterAllocationWindow() {
        Instant now = Instant.parse("2026-06-20T10:05:00Z");
        when(campaignMapper.findById(201L)).thenReturn(campaignWithStatus(201L, 42L, 1001L,
                PromotionResourceType.FEED_TOP_SLOT, PromotionCampaignStatus.ACTIVE,
                "2026-06-20T11:30:00Z", "2026-06-20T13:00:00Z"));
        when(windowMapper.findOpenWindow(PromotionResourceType.FEED_TOP_SLOT, now))
                .thenReturn(window(301L, PromotionResourceType.FEED_TOP_SLOT, "2026-06-20T10:00:00Z", "2026-06-20T11:00:00Z"));

        assertThatThrownBy(() -> service.submitBid(42L, 201L, 120L, now))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.PROMOTION_BID_WINDOW_CLOSED);
        verify(walletService, never()).hold(anyLong(), anyLong(), any(), any(), any());
    }

    @Test
    void submitBidRejectsCampaignThatEndsBeforeAllocationWindowEnds() {
        Instant now = Instant.parse("2026-06-20T10:05:00Z");
        when(campaignMapper.findById(201L)).thenReturn(campaignWithStatus(201L, 42L, 1001L,
                PromotionResourceType.FEED_TOP_SLOT, PromotionCampaignStatus.ACTIVE,
                "2026-06-20T09:00:00Z", "2026-06-20T11:30:00Z"));
        when(windowMapper.findOpenWindow(PromotionResourceType.FEED_TOP_SLOT, now))
                .thenReturn(window(301L, PromotionResourceType.FEED_TOP_SLOT, "2026-06-20T10:00:00Z", "2026-06-20T11:00:00Z"));

        assertThatThrownBy(() -> service.submitBid(42L, 201L, 120L, now))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.PROMOTION_BID_WINDOW_CLOSED);
        verify(walletService, never()).hold(anyLong(), anyLong(), any(), any(), any());
    }

    @Test
    void submitBidRejectsCampaignWithoutActiveStatus() {
        Instant now = Instant.parse("2026-06-20T10:05:00Z");
        when(campaignMapper.findById(201L)).thenReturn(campaignWithStatus(201L, 42L, 1001L,
                PromotionResourceType.FEED_TOP_SLOT, null,
                "2026-06-20T10:00:00Z", "2026-06-20T12:00:00Z"));
        when(windowMapper.findOpenWindow(PromotionResourceType.FEED_TOP_SLOT, now))
                .thenReturn(window(301L, PromotionResourceType.FEED_TOP_SLOT, "2026-06-20T10:00:00Z", "2026-06-20T11:00:00Z"));

        assertThatThrownBy(() -> service.submitBid(42L, 201L, 120L, now))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.PROMOTION_BID_WINDOW_CLOSED);
        verify(walletService, never()).hold(anyLong(), anyLong(), any(), any(), any());
    }

    @Test
    void submitBidHoldsBidAmountWhenWindowOpen() {
        Instant now = Instant.parse("2026-06-20T10:05:00Z");
        when(campaignMapper.findById(201L)).thenReturn(campaign(201L, 42L, 1001L, PromotionResourceType.FEED_TOP_SLOT));
        when(windowMapper.findOpenWindow(PromotionResourceType.FEED_TOP_SLOT, now))
                .thenReturn(window(301L, PromotionResourceType.FEED_TOP_SLOT, "2026-06-20T10:00:00Z", "2026-06-20T11:00:00Z"));
        when(idService.nextId(IdNamespace.ADMIN_OPERATION)).thenReturn(401L);

        service.submitBid(42L, 201L, 120L, now);

        verify(walletService).hold(
                42L,
                120L,
                WalletLedgerReason.HOLD_RESERVE,
                WalletBusinessType.PROMOTION,
                "promotion-bid:401"
        );
        verify(bidMapper).insert(any(PromotionBid.class));
    }

    private PromotionCampaign campaign(long id, long creator, long post, PromotionResourceType type) {
        return campaignWithStatus(id, creator, post, type, PromotionCampaignStatus.ACTIVE,
                "2026-06-20T10:00:00Z", "2026-06-20T12:00:00Z");
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

    private PromotionAuctionWindow window(long id, PromotionResourceType type, String start, String end) {
        return PromotionAuctionWindow.builder()
                .id(id)
                .resourceType(type)
                .windowStartAt(Instant.parse(start))
                .windowEndAt(Instant.parse(end))
                .slotCount(1)
                .reservePrice(1L)
                .status(PromotionAuctionWindowStatus.OPEN)
                .createdAt(Instant.parse(start))
                .updatedAt(Instant.parse(start))
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
