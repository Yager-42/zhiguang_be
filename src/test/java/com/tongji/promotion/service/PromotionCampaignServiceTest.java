package com.tongji.promotion.service;

import com.tongji.common.id.IdNamespace;
import com.tongji.common.id.IdService;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
    private IdService idService;

    private PromotionCampaignService service;

    @BeforeEach
    void setUp() {
        service = new PromotionCampaignService(campaignMapper, windowMapper, bidMapper, walletService, idService);
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
        return PromotionCampaign.builder()
                .id(id)
                .creatorUserId(creator)
                .postId(post)
                .resourceType(type)
                .status(PromotionCampaignStatus.ACTIVE)
                .startAt(Instant.parse("2026-06-20T10:00:00Z"))
                .endAt(Instant.parse("2026-06-20T11:00:00Z"))
                .createdAt(Instant.parse("2026-06-20T10:00:00Z"))
                .updatedAt(Instant.parse("2026-06-20T10:00:00Z"))
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
}
