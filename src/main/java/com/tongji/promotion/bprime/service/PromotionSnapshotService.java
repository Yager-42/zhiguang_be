package com.tongji.promotion.bprime.service;

import com.tongji.promotion.bprime.model.PromotionAuctionSnapshot;
import com.tongji.promotion.bprime.model.PromotionRankingItem;
import com.tongji.promotion.mapper.PromotionAuctionWindowMapper;
import com.tongji.promotion.mapper.PromotionSlotAllocationMapper;
import com.tongji.promotion.model.PromotionAuctionWindow;
import com.tongji.promotion.model.PromotionAuctionWindowStatus;
import com.tongji.promotion.model.PromotionSlotAllocation;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Service
public class PromotionSnapshotService {

    private final PromotionAuctionWindowMapper windowMapper;
    private final PromotionSlotAllocationMapper allocationMapper;
    private final StringRedisTemplate redisTemplate;

    public PromotionSnapshotService(PromotionAuctionWindowMapper windowMapper,
                                    PromotionSlotAllocationMapper allocationMapper,
                                    StringRedisTemplate redisTemplate) {
        this.windowMapper = windowMapper;
        this.allocationMapper = allocationMapper;
        this.redisTemplate = redisTemplate;
    }

    public PromotionAuctionSnapshot snapshot(long auctionWindowId) {
        PromotionAuctionWindow window = windowMapper.findById(auctionWindowId);
        String status = window == null || window.getStatus() == null ? "UNKNOWN" : window.getStatus().name();
        if (window != null && window.getStatus() == PromotionAuctionWindowStatus.SETTLED) {
            return new PromotionAuctionSnapshot(auctionWindowId, status, allocationRanking(auctionWindowId),
                    Instant.now(), redisDecisionVersion(auctionWindowId));
        }
        List<PromotionRankingItem> hotRanking = hotRanking(auctionWindowId);
        if (!hotRanking.isEmpty()) {
            return new PromotionAuctionSnapshot(auctionWindowId, status, hotRanking, Instant.now(),
                    redisDecisionVersion(auctionWindowId));
        }
        return new PromotionAuctionSnapshot(auctionWindowId, status, List.of(), Instant.now(),
                redisDecisionVersion(auctionWindowId));
    }

    private List<PromotionRankingItem> hotRanking(long auctionWindowId) {
        String prefix = "promotion:auction:" + auctionWindowId;
        Set<String> campaigns = redisTemplate.opsForZSet().reverseRange(prefix + ":ranking", 0, 29);
        if (campaigns == null || campaigns.isEmpty()) {
            return List.of();
        }
        List<PromotionRankingItem> items = new ArrayList<>();
        int rank = 1;
        for (String campaign : campaigns) {
            String campaignKey = prefix + ":campaign:" + campaign;
            String amount = (String) redisTemplate.opsForHash().get(campaignKey, "bidAmount");
            String bidder = (String) redisTemplate.opsForHash().get(campaignKey, "bidderUserId");
            String post = (String) redisTemplate.opsForHash().get(campaignKey, "postId");
            if (amount != null && bidder != null && post != null) {
                items.add(new PromotionRankingItem(Long.parseLong(campaign), Long.parseLong(bidder),
                        Long.parseLong(post), Long.parseLong(amount), rank));
                rank++;
            }
        }
        return items;
    }

    private long redisDecisionVersion(long auctionWindowId) {
        String value = redisTemplate.opsForValue().get("promotion:auction:" + auctionWindowId + ":decision_version");
        return value == null || value.isBlank() ? 0L : Long.parseLong(value);
    }

    private List<PromotionRankingItem> allocationRanking(long auctionWindowId) {
        return allocationMapper.listByAuctionWindowId(auctionWindowId).stream()
                .map(this::toRankingItem)
                .toList();
    }

    private PromotionRankingItem toRankingItem(PromotionSlotAllocation allocation) {
        return new PromotionRankingItem(allocation.getCampaignId(), allocation.getBidderUserId(),
                allocation.getPostId(), allocation.getClearingPrice(), allocation.getSlotIndex() + 1);
    }
}
