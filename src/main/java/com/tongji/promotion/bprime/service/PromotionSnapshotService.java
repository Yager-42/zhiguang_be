package com.tongji.promotion.bprime.service;

import com.tongji.promotion.bprime.model.PromotionAuctionSnapshot;
import com.tongji.promotion.bprime.model.PromotionAuctionHotSnapshot;
import com.tongji.promotion.bprime.model.PromotionRankingItem;
import com.tongji.promotion.bprime.redis.PromotionRedisSnapshotAdapter;
import com.tongji.promotion.mapper.PromotionAuctionWindowMapper;
import com.tongji.promotion.mapper.PromotionSlotAllocationMapper;
import com.tongji.promotion.model.PromotionAuctionWindow;
import com.tongji.promotion.model.PromotionAuctionWindowStatus;
import com.tongji.promotion.model.PromotionSlotAllocation;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
public class PromotionSnapshotService {

    private final PromotionAuctionWindowMapper windowMapper;
    private final PromotionSlotAllocationMapper allocationMapper;
    private final PromotionRedisSnapshotAdapter redisSnapshotAdapter;

    public PromotionSnapshotService(PromotionAuctionWindowMapper windowMapper,
                                    PromotionSlotAllocationMapper allocationMapper,
                                    PromotionRedisSnapshotAdapter redisSnapshotAdapter) {
        this.windowMapper = windowMapper;
        this.allocationMapper = allocationMapper;
        this.redisSnapshotAdapter = redisSnapshotAdapter;
    }

    public PromotionAuctionSnapshot snapshot(long auctionWindowId) {
        PromotionAuctionWindow window = windowMapper.findById(auctionWindowId);
        String status = window == null || window.getStatus() == null ? "UNKNOWN" : window.getStatus().name();
        String windowIdStr = String.valueOf(auctionWindowId);
        Instant windowEndAt = window != null ? window.getWindowEndAt() : null;
        PromotionAuctionHotSnapshot hotSnapshot = redisSnapshotAdapter.snapshot(auctionWindowId);
        if (window != null && window.getStatus() == PromotionAuctionWindowStatus.SETTLED) {
            return new PromotionAuctionSnapshot(windowIdStr, status, allocationRanking(auctionWindowId),
                    Instant.now(), hotSnapshot.decisionVersion(), windowEndAt,
                    hotSnapshot.currentPriceCents(), hotSnapshot.winnerCampaignId(),
                    hotSnapshot.bidCount(), hotSnapshot.rules(), window.getWindowStartAt(),
                    window.getResourceType() != null ? window.getResourceType().placement() : null);
        }
        List<PromotionRankingItem> hotRanking = hotSnapshot.ranking();
        if (!hotRanking.isEmpty()) {
            return new PromotionAuctionSnapshot(windowIdStr, status, hotRanking, Instant.now(),
                    hotSnapshot.decisionVersion(), windowEndAt,
                    hotSnapshot.currentPriceCents(), hotSnapshot.winnerCampaignId(),
                    hotSnapshot.bidCount(), hotSnapshot.rules(),
                    window != null ? window.getWindowStartAt() : null,
                    window != null && window.getResourceType() != null ? window.getResourceType().placement() : null);
        }
        return new PromotionAuctionSnapshot(windowIdStr, status, List.of(), Instant.now(),
                hotSnapshot.decisionVersion(), windowEndAt,
                hotSnapshot.currentPriceCents(), hotSnapshot.winnerCampaignId(),
                hotSnapshot.bidCount(), hotSnapshot.rules(),
                window != null ? window.getWindowStartAt() : null,
                window != null && window.getResourceType() != null ? window.getResourceType().placement() : null);
    }

    private List<PromotionRankingItem> allocationRanking(long auctionWindowId) {
        return allocationMapper.listByAuctionWindowId(auctionWindowId).stream()
                .map(this::toRankingItem)
                .toList();
    }

    private PromotionRankingItem toRankingItem(PromotionSlotAllocation allocation) {
        return new PromotionRankingItem(String.valueOf(allocation.getCampaignId()), String.valueOf(allocation.getBidderUserId()),
                String.valueOf(allocation.getPostId()), allocation.getClearingPrice(), allocation.getSlotIndex() + 1);
    }
}
