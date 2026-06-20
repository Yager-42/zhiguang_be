package com.tongji.promotion.service;

import com.tongji.common.id.IdNamespace;
import com.tongji.common.id.IdService;
import com.tongji.promotion.config.PromotionProperties;
import com.tongji.promotion.mapper.PromotionAuctionWindowMapper;
import com.tongji.promotion.model.PromotionAuctionWindow;
import com.tongji.promotion.model.PromotionAuctionWindowStatus;
import com.tongji.promotion.model.PromotionResourceType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PromotionAuctionWindowServiceTest {

    @Mock
    private PromotionAuctionWindowMapper windowMapper;

    @Mock
    private IdService idService;

    private PromotionAuctionWindowService service;

    @BeforeEach
    void setUp() {
        PromotionProperties properties = new PromotionProperties();
        Clock fixed = Clock.fixed(Instant.parse("2026-06-20T10:05:00Z"), ZoneOffset.UTC);
        service = new PromotionAuctionWindowService(windowMapper, idService, properties, fixed);
    }

    @Test
    void ensureOpenWindowCreatesCurrentAndNextWindowWhenMissing() {
        Instant now = Instant.parse("2026-06-20T10:05:00Z");
        when(windowMapper.findOpenWindow(PromotionResourceType.FEED_TOP_SLOT, now)).thenReturn(null);
        when(idService.nextId(IdNamespace.ADMIN_OPERATION)).thenReturn(301L, 302L);

        PromotionAuctionWindow window = service.ensureOpenWindow(PromotionResourceType.FEED_TOP_SLOT);

        assertThat(window.getId()).isEqualTo(301L);
        verify(windowMapper, times(2)).insert(any(PromotionAuctionWindow.class));
    }

    @Test
    void ensureOpenWindowKeepsCurrentAndBackfillsNextWhenMissing() {
        Instant now = Instant.parse("2026-06-20T10:05:00Z");
        PromotionAuctionWindow current = window(301L, PromotionResourceType.FEED_TOP_SLOT,
                "2026-06-20T10:00:00Z", "2026-06-20T11:00:00Z");
        when(windowMapper.findOpenWindow(PromotionResourceType.FEED_TOP_SLOT, now)).thenReturn(current);
        when(windowMapper.findExactWindow(PromotionResourceType.FEED_TOP_SLOT,
                Instant.parse("2026-06-20T11:00:00Z"), Instant.parse("2026-06-20T12:00:00Z"))).thenReturn(null);
        when(idService.nextId(IdNamespace.ADMIN_OPERATION)).thenReturn(302L);

        PromotionAuctionWindow window = service.ensureOpenWindow(PromotionResourceType.FEED_TOP_SLOT);

        assertThat(window.getId()).isEqualTo(301L);
        verify(windowMapper).insert(any(PromotionAuctionWindow.class));
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
