package com.tongji.promotion.schedule;

import com.tongji.promotion.bprime.config.PromotionBPrimeProperties;
import com.tongji.promotion.bprime.kafka.PromotionDecisionLogPort;
import com.tongji.promotion.bprime.mapper.PromotionBidEscrowMapper;
import com.tongji.promotion.bprime.model.PromotionAuctionDecision;
import com.tongji.promotion.mapper.PromotionAuctionWindowMapper;
import com.tongji.promotion.mapper.PromotionBidMapper;
import com.tongji.promotion.model.PromotionAuctionWindow;
import com.tongji.promotion.model.PromotionAuctionWindowStatus;
import com.tongji.promotion.model.PromotionBid;
import com.tongji.promotion.model.PromotionBidStatus;
import com.tongji.promotion.model.PromotionResourceType;
import com.tongji.promotion.service.PromotionAllocationCacheService;
import com.tongji.promotion.service.PromotionAuctionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.HashOperations;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(MockitoExtension.class)
class PromotionAuctionWindowCloserTest {

    @Mock
    private PromotionAuctionWindowMapper windowMapper;

    @Mock
    private PromotionBidMapper bidMapper;

    @Mock
    private PromotionBidEscrowMapper escrowMapper;

    @Mock
    private PromotionAuctionService auctionService;

    @Mock
    private PromotionDecisionLogPort decisionLogPort;

    @Mock
    private PromotionAllocationCacheService cacheService;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private HashOperations<String, Object, Object> hashOperations;

    private PromotionAuctionWindowCloser closer;
    private PromotionBPrimeProperties properties;

    @BeforeEach
    void setUp() {
        properties = new PromotionBPrimeProperties();
        properties.setEnabled(true);
        closer = new PromotionAuctionWindowCloser(windowMapper, bidMapper, escrowMapper, auctionService, decisionLogPort,
                cacheService, properties, redisTemplate);
    }

    @Test
    void closesDueWindowsAndRefreshesCache() {
        PromotionAuctionWindow window = window(301L, PromotionResourceType.FEED_TOP_SLOT,
                "2026-06-20T10:00:00Z", "2026-06-20T11:00:00Z");
        when(windowMapper.listClosableWindows(any(), eq(50))).thenReturn(List.of(window));
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(valueOperations.get("promotion:auction:{301}:close_decision_version")).thenReturn(null);
        when(hashOperations.increment("promotion:auction:{301}:state", "decisionVersion", 1L)).thenReturn(6L);
        when(bidMapper.listActiveBidsByWindowId(301L,
                Instant.parse("2026-06-20T11:00:00Z"),
                Instant.parse("2026-06-20T12:00:00Z")))
                .thenReturn(List.of(
                        bid(401L, 201L, 42L, 120L),
                        bid(402L, 202L, 43L, 100L)
                ));

        closer.closeDueWindows(Instant.parse("2026-06-20T11:00:00Z"), 50);

        org.mockito.ArgumentCaptor<PromotionAuctionDecision> captor = forClass(PromotionAuctionDecision.class);
        verify(decisionLogPort).append(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().decisionType()).isEqualTo("WINDOW_CLOSED");
        org.assertj.core.api.Assertions.assertThat(captor.getValue().decisionId())
                .isEqualTo("promotion-bprime-close-window-301-v6");
        org.assertj.core.api.Assertions.assertThat(captor.getValue().auctionWindowId()).isEqualTo(301L);
        org.assertj.core.api.Assertions.assertThat(captor.getValue().decisionVersion()).isEqualTo(6L);
        org.assertj.core.api.Assertions.assertThat(captor.getValue().payload())
                .containsKeys("finalRanking", "winners", "clearingPrices", "walletEffects",
                        "allocationStartAt", "allocationEndAt", "finalWindowStatus");
        org.assertj.core.api.Assertions.assertThat(captor.getValue().walletEffects())
                .extracting(com.tongji.promotion.bprime.model.PromotionWalletEffect::effectType)
                .contains("CAPTURE", "RELEASE");
        verify(cacheService).refreshActiveAllocations(PromotionResourceType.FEED_TOP_SLOT,
                Instant.parse("2026-06-20T11:00:00Z"));
        verify(valueOperations).set("promotion:auction:{301}:close_decision_version", "6");
        verify(redisTemplate, never()).delete("promotion:auction:{301}:close_decision_version");
        verify(windowMapper, never()).markSettled(eq(301L), any());
    }

    @Test
    void closeAppendRetryReusesPendingDecisionVersionInsteadOfBurningAnotherVersion() {
        PromotionAuctionWindow window = window(301L, PromotionResourceType.FEED_TOP_SLOT,
                "2026-06-20T10:00:00Z", "2026-06-20T11:00:00Z");
        when(windowMapper.listClosableWindows(any(), eq(50))).thenReturn(List.of(window));
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("promotion:auction:{301}:close_decision_version")).thenReturn("6");
        when(bidMapper.listActiveBidsByWindowId(301L,
                Instant.parse("2026-06-20T11:00:00Z"),
                Instant.parse("2026-06-20T12:00:00Z")))
                .thenReturn(List.of(bid(401L, 201L, 42L, 120L)));

        closer.closeDueWindows(Instant.parse("2026-06-20T11:00:00Z"), 50);

        org.mockito.ArgumentCaptor<PromotionAuctionDecision> captor = forClass(PromotionAuctionDecision.class);
        verify(decisionLogPort).append(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().decisionVersion()).isEqualTo(6L);
        verify(hashOperations, never()).increment("promotion:auction:{301}:state", "decisionVersion", 1L);
    }

    @Test
    void closeAppendFailureKeepsPendingDecisionVersionForRetry() {
        PromotionAuctionWindow window = window(301L, PromotionResourceType.FEED_TOP_SLOT,
                "2026-06-20T10:00:00Z", "2026-06-20T11:00:00Z");
        when(windowMapper.listClosableWindows(any(), eq(50))).thenReturn(List.of(window));
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(valueOperations.get("promotion:auction:{301}:close_decision_version")).thenReturn(null);
        when(hashOperations.increment("promotion:auction:{301}:state", "decisionVersion", 1L)).thenReturn(6L);
        when(bidMapper.listActiveBidsByWindowId(301L,
                Instant.parse("2026-06-20T11:00:00Z"),
                Instant.parse("2026-06-20T12:00:00Z")))
                .thenReturn(List.of(bid(401L, 201L, 42L, 120L)));
        org.mockito.Mockito.doThrow(new IllegalStateException("kafka down")).when(decisionLogPort).append(any());

        assertThatThrownBy(() -> closer.closeDueWindows(Instant.parse("2026-06-20T11:00:00Z"), 50))
                .isInstanceOf(IllegalStateException.class);

        verify(valueOperations).set("promotion:auction:{301}:close_decision_version", "6");
        verify(redisTemplate, never()).delete("promotion:auction:{301}:close_decision_version");
    }

    @Test
    void repeatedCloseBeforeProjectionSettlementMustNotBurnAnotherCloseDecisionVersion() {
        PromotionAuctionWindow window = window(301L, PromotionResourceType.FEED_TOP_SLOT,
                "2026-06-20T10:00:00Z", "2026-06-20T11:00:00Z");
        when(windowMapper.listClosableWindows(any(), eq(50))).thenReturn(List.of(window));
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(valueOperations.get("promotion:auction:{301}:close_decision_version")).thenReturn(null, "6");
        when(hashOperations.increment("promotion:auction:{301}:state", "decisionVersion", 1L)).thenReturn(6L, 7L);
        when(bidMapper.listActiveBidsByWindowId(301L,
                Instant.parse("2026-06-20T11:00:00Z"),
                Instant.parse("2026-06-20T12:00:00Z")))
                .thenReturn(List.of(bid(401L, 201L, 42L, 120L)));

        closer.closeDueWindows(Instant.parse("2026-06-20T11:00:00Z"), 50);
        closer.closeDueWindows(Instant.parse("2026-06-20T11:00:01Z"), 50);

        org.mockito.ArgumentCaptor<PromotionAuctionDecision> captor = forClass(PromotionAuctionDecision.class);
        verify(decisionLogPort, times(2)).append(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getAllValues())
                .extracting(PromotionAuctionDecision::decisionVersion)
                .containsExactly(6L, 6L);
        verify(hashOperations, times(1)).increment("promotion:auction:{301}:state", "decisionVersion", 1L);
    }

    @Test
    void closesDueWindowsDirectlyWhenBprimeDisabled() {
        properties.setEnabled(false);
        PromotionAuctionWindow window = window(301L, PromotionResourceType.FEED_TOP_SLOT,
                "2026-06-20T10:00:00Z", "2026-06-20T11:00:00Z");
        when(windowMapper.listClosableWindows(any(), eq(50))).thenReturn(List.of(window));
        when(bidMapper.listActiveBidsByWindowId(301L,
                Instant.parse("2026-06-20T11:00:00Z"),
                Instant.parse("2026-06-20T12:00:00Z")))
                .thenReturn(List.of(bid(401L, 201L, 42L, 120L)));

        closer.closeDueWindows(Instant.parse("2026-06-20T11:00:00Z"), 50);

        verify(auctionService).settleWindow(eq(window), anyList(), eq(Instant.parse("2026-06-20T11:00:00Z")));
        verify(decisionLogPort, never()).append(any());
    }

    @Test
    void refreshCurrentAllocationsRefreshesBothResources() {
        closer.refreshCurrentAllocations(Instant.parse("2026-06-20T11:00:00Z"));

        verify(cacheService).refreshActiveAllocations(PromotionResourceType.FEED_TOP_SLOT,
                Instant.parse("2026-06-20T11:00:00Z"));
        verify(cacheService).refreshActiveAllocations(PromotionResourceType.SEARCH_TOP_SLOT,
                Instant.parse("2026-06-20T11:00:00Z"));
    }

    private PromotionAuctionWindow window(long id, PromotionResourceType type, String start, String end) {
        return PromotionAuctionWindow.builder()
                .id(id).resourceType(type)
                .windowStartAt(Instant.parse(start)).windowEndAt(Instant.parse(end))
                .slotCount(1).reservePrice(1L)
                .status(PromotionAuctionWindowStatus.OPEN)
                .createdAt(Instant.parse(start)).updatedAt(Instant.parse(start))
                .build();
    }

    private PromotionBid bid(long id, long campaignId, long bidderUserId, long bidAmount) {
        return PromotionBid.builder()
                .id(id).campaignId(campaignId).auctionWindowId(301L).bidderUserId(bidderUserId)
                .bidAmount(bidAmount).walletBusinessRef("promotion-bid:" + id)
                .status(PromotionBidStatus.ACTIVE)
                .createdAt(Instant.parse("2026-06-20T10:05:00Z"))
                .updatedAt(Instant.parse("2026-06-20T10:05:00Z"))
                .build();
    }
}
