package com.tongji.promotion.schedule;

import com.tongji.promotion.bprime.config.PromotionBPrimeProperties;
import com.tongji.promotion.bprime.kafka.PromotionDecisionLogPort;
import com.tongji.promotion.bprime.mapper.PromotionBidEscrowMapper;
import com.tongji.promotion.bprime.model.PromotionAuctionDecision;
import com.tongji.promotion.bprime.model.PromotionBidEscrowRecord;
import com.tongji.promotion.bprime.model.PromotionWalletEffect;
import com.tongji.promotion.bprime.redis.PromotionAuctionRedisKeys;
import com.tongji.promotion.mapper.PromotionAuctionWindowMapper;
import com.tongji.promotion.mapper.PromotionBidMapper;
import com.tongji.promotion.model.PromotionAuctionWindow;
import com.tongji.promotion.model.PromotionBid;
import com.tongji.promotion.model.PromotionResourceType;
import com.tongji.promotion.service.PromotionAllocationCacheService;
import com.tongji.promotion.service.PromotionAuctionService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class PromotionAuctionWindowCloser {

    private final PromotionAuctionWindowMapper windowMapper;
    private final PromotionBidMapper bidMapper;
    private final PromotionBidEscrowMapper escrowMapper;
    private final PromotionAuctionService auctionService;
    private final PromotionDecisionLogPort decisionLogPort;
    private final PromotionAllocationCacheService cacheService;
    private final PromotionBPrimeProperties bprimeProperties;
    private final StringRedisTemplate redisTemplate;

    public void closeDueWindows(Instant now, int batchSize) {
        for (PromotionAuctionWindow window : windowMapper.listClosableWindows(now, batchSize)) {
            if (!bprimeProperties.isEnabled()) {
                Instant allocationStartAt = window.getWindowEndAt();
                long spanSeconds = window.getWindowEndAt().getEpochSecond() - window.getWindowStartAt().getEpochSecond();
                Instant allocationEndAt = allocationStartAt.plusSeconds(spanSeconds);
                auctionService.settleWindow(window,
                        bidMapper.listActiveBidsByWindowId(window.getId(), allocationStartAt, allocationEndAt),
                        now);
                cacheService.refreshActiveAllocations(window.getResourceType(), now);
                continue;
            }
            Instant allocationStartAt = window.getWindowEndAt();
            long spanSeconds = window.getWindowEndAt().getEpochSecond() - window.getWindowStartAt().getEpochSecond();
            Instant allocationEndAt = allocationStartAt.plusSeconds(spanSeconds);
            List<PromotionBid> ranked = bidMapper.listActiveBidsByWindowId(window.getId(), allocationStartAt, allocationEndAt)
                    .stream()
                    .sorted(Comparator.comparingLong(PromotionBid::getBidAmount).reversed()
                            .thenComparingLong(PromotionBid::getId))
                    .toList();
            int winnerCount = (int) ranked.stream()
                    .takeWhile(bid -> bid.getBidAmount() >= window.getReservePrice())
                    .limit(window.getSlotCount())
                    .count();
            Map<Long, PromotionBidEscrowRecord> escrows = escrowMapper.listActiveByWindowId(window.getId()).stream()
                    .collect(Collectors.toMap(PromotionBidEscrowRecord::getCampaignId, Function.identity()));
            List<PromotionWalletEffect> walletEffects = closeWalletEffects(window, ranked, winnerCount, escrows);
            long decisionVersion = nextDecisionVersion(window.getId());
            decisionLogPort.append(new PromotionAuctionDecision(
                    "promotion-bprime-close-window-" + window.getId() + "-v" + decisionVersion,
                    "promotion-bprime-close-window-" + window.getId(),
                    "window-close:" + window.getId() + ":" + window.getWindowEndAt(),
                    window.getId(),
                    decisionVersion,
                    decisionVersion - 1,
                    0L,
                    0L,
                    0L,
                    window.getResourceType().name(),
                    "WINDOW_CLOSED",
                    false,
                    null,
                    0L,
                    finalRanking(ranked),
                    walletEffects,
                    Map.of(
                            "finalRanking", finalRanking(ranked),
                            "winners", winners(window, ranked, winnerCount),
                            "clearingPrices", clearingPrices(window, ranked, winnerCount),
                            "walletEffects", walletEffects,
                            "allocationStartAt", allocationStartAt.toString(),
                            "allocationEndAt", allocationEndAt.toString(),
                            "finalWindowStatus", "SETTLED"
                    ),
                    now));
            cacheService.refreshActiveAllocations(window.getResourceType(), now);
        }
    }

    public void refreshCurrentAllocations(Instant now) {
        cacheService.refreshActiveAllocations(PromotionResourceType.FEED_TOP_SLOT, now);
        cacheService.refreshActiveAllocations(PromotionResourceType.SEARCH_TOP_SLOT, now);
    }

    private List<com.tongji.promotion.bprime.model.PromotionRankingItem> finalRanking(List<PromotionBid> ranked) {
        return java.util.stream.IntStream.range(0, ranked.size())
                .mapToObj(i -> new com.tongji.promotion.bprime.model.PromotionRankingItem(
                        String.valueOf(ranked.get(i).getCampaignId()), String.valueOf(ranked.get(i).getBidderUserId()),
                        String.valueOf(ranked.get(i).getPostId()), ranked.get(i).getBidAmount(), i + 1))
                .toList();
    }

    private List<Map<String, Object>> winners(PromotionAuctionWindow window, List<PromotionBid> ranked, int winnerCount) {
        return java.util.stream.IntStream.range(0, winnerCount)
                .mapToObj(i -> Map.<String, Object>of(
                        "campaignId", ranked.get(i).getCampaignId(),
                        "bidderUserId", ranked.get(i).getBidderUserId(),
                        "postId", ranked.get(i).getPostId(),
                        "slotIndex", i,
                        "clearingPrice", clearingPrice(window, ranked, i)))
                .toList();
    }

    private List<Map<String, Object>> clearingPrices(PromotionAuctionWindow window, List<PromotionBid> ranked,
                                                     int winnerCount) {
        return java.util.stream.IntStream.range(0, winnerCount)
                .mapToObj(i -> Map.<String, Object>of(
                        "campaignId", ranked.get(i).getCampaignId(),
                        "slotIndex", i,
                        "clearingPrice", clearingPrice(window, ranked, i)))
                .toList();
    }

    private List<PromotionWalletEffect> closeWalletEffects(
            PromotionAuctionWindow window, List<PromotionBid> ranked, int winnerCount,
            Map<Long, PromotionBidEscrowRecord> escrows) {
        return java.util.stream.IntStream.range(0, ranked.size())
                .boxed()
                .flatMap(i -> walletEffectsForBid(window, ranked, winnerCount, i, escrows).stream())
                .toList();
    }

    private List<PromotionWalletEffect> walletEffectsForBid(
            PromotionAuctionWindow window, List<PromotionBid> ranked, int winnerCount, int index,
            Map<Long, PromotionBidEscrowRecord> escrows) {
        PromotionBid bid = ranked.get(index);
        PromotionBidEscrowRecord escrow = escrows.get(bid.getCampaignId());
        long authorizedAmount = escrow == null ? bid.getBidAmount() : escrow.getAuthorizedAmount();
        if (index >= winnerCount) {
            return List.of(new PromotionWalletEffect(
                    bid.getBidderUserId(), authorizedAmount, "RELEASE",
                    "promotion-bprime:" + bid.getAuctionWindowId() + ":" + bid.getCampaignId() + ":release"));
        }
        long clearingPrice = clearingPrice(window, ranked, index);
        long releaseAmount = authorizedAmount - clearingPrice;
        PromotionWalletEffect capture = new PromotionWalletEffect(
                bid.getBidderUserId(), clearingPrice, "CAPTURE",
                "promotion-bprime:" + window.getId() + ":" + bid.getCampaignId() + ":capture");
        if (releaseAmount <= 0) {
            return List.of(capture);
        }
        return List.of(capture, new PromotionWalletEffect(
                bid.getBidderUserId(), releaseAmount, "RELEASE",
                "promotion-bprime:" + window.getId() + ":" + bid.getCampaignId() + ":release"));
    }

    private long clearingPrice(PromotionAuctionWindow window, List<PromotionBid> ranked, int slotIndex) {
        long nextBid = (slotIndex + 1 < ranked.size()) ? ranked.get(slotIndex + 1).getBidAmount() : window.getReservePrice();
        return Math.max(nextBid, window.getReservePrice());
    }

    private long nextDecisionVersion(long auctionWindowId) {
        String prefix = PromotionAuctionRedisKeys.prefix(auctionWindowId);
        String pendingKey = prefix + ":close_decision_version";
        String pendingVersion = redisTemplate.opsForValue().get(pendingKey);
        if (pendingVersion != null && !pendingVersion.isBlank()) {
            return Long.parseLong(pendingVersion);
        }
        Long version = redisTemplate.opsForHash().increment(prefix + ":state", "decisionVersion", 1L);
        if (version == null) {
            throw new IllegalStateException("promotion decision version increment failed: " + auctionWindowId);
        }
        redisTemplate.opsForValue().set(pendingKey, String.valueOf(version));
        return version;
    }

}
