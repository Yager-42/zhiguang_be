package com.tongji.promotion.service;

import com.tongji.promotion.api.dto.PromotionAllocationView;
import com.tongji.promotion.mapper.PromotionSlotAllocationMapper;
import com.tongji.promotion.model.PromotionResourceType;
import com.tongji.promotion.model.PromotionSlotAllocation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PromotionAllocationServiceTest {

    @Mock
    private PromotionAllocationCacheService cacheService;

    @Mock
    private PromotionSlotAllocationMapper allocationMapper;

    private PromotionAllocationService service;

    @BeforeEach
    void setUp() {
        service = new PromotionAllocationService(cacheService, allocationMapper);
    }

    @Test
    void returnsCachedAllocationsWhenPresent() {
        PromotionAllocationView view = new PromotionAllocationView("201", "feed_top_slot", "301", "401");
        when(cacheService.readFromCache(PromotionResourceType.FEED_TOP_SLOT)).thenReturn(List.of(view));

        assertThat(service.getActiveFeedAllocation()).containsExactly(view);
        verify(allocationMapper, never()).listActive(any(), any());
    }

    @Test
    void fallsBackToDbWhenCacheMisses() {
        when(cacheService.readFromCache(PromotionResourceType.SEARCH_TOP_SLOT)).thenReturn(null);
        PromotionSlotAllocation alloc = PromotionSlotAllocation.builder()
                .id(1L).auctionWindowId(9L).resourceType(PromotionResourceType.SEARCH_TOP_SLOT)
                .slotIndex(0).campaignId(7L).postId(201L).bidderUserId(42L).clearingPrice(80L)
                .allocationStartAt(Instant.parse("2026-06-20T11:00:00Z"))
                .allocationEndAt(Instant.parse("2026-06-20T12:00:00Z"))
                .createdAt(Instant.parse("2026-06-20T11:00:00Z"))
                .build();
        when(allocationMapper.listActive(eq(PromotionResourceType.SEARCH_TOP_SLOT), any())).thenReturn(List.of(alloc));

        List<PromotionAllocationView> result = service.getActiveSearchAllocation();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).postId()).isEqualTo("201");
        assertThat(result.get(0).placementType()).isEqualTo("search_top_slot");
    }
}
