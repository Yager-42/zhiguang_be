package com.tongji.promotion.bprime.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.promotion.bprime.mapper.PromotionAuctionDecisionMapper;
import com.tongji.promotion.bprime.model.PromotionAuctionDecision;
import com.tongji.promotion.bprime.model.PromotionAuctionDecisionRecord;
import com.tongji.promotion.bprime.model.PromotionAuctionSnapshot;
import com.tongji.promotion.bprime.model.PromotionRankingItem;
import com.tongji.promotion.mapper.PromotionAuctionWindowMapper;
import com.tongji.promotion.model.PromotionAuctionWindow;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

@Service
public class PromotionSnapshotService {

    private final PromotionAuctionWindowMapper windowMapper;
    private final PromotionAuctionDecisionMapper decisionMapper;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public PromotionSnapshotService(PromotionAuctionWindowMapper windowMapper,
                                    PromotionAuctionDecisionMapper decisionMapper,
                                    StringRedisTemplate redisTemplate,
                                    ObjectMapper objectMapper) {
        this.windowMapper = windowMapper;
        this.decisionMapper = decisionMapper;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    public PromotionAuctionSnapshot snapshot(long auctionWindowId) {
        PromotionAuctionWindow window = windowMapper.findById(auctionWindowId);
        String status = window == null || window.getStatus() == null ? "UNKNOWN" : window.getStatus().name();
        List<PromotionRankingItem> hotRanking = hotRanking(auctionWindowId);
        if (!hotRanking.isEmpty()) {
            return new PromotionAuctionSnapshot(auctionWindowId, status, hotRanking, Instant.now());
        }
        List<PromotionRankingItem> sorted = decisionMapper.listAcceptedByWindow(auctionWindowId).stream()
                .map(this::toDecision)
                .map(decision -> new PromotionRankingItem(decision.campaignId(), decision.bidderUserId(),
                        decision.postId(), decision.bidAmount(), 0))
                .sorted(Comparator.comparingLong(PromotionRankingItem::bidAmount).reversed()
                        .thenComparingLong(PromotionRankingItem::campaignId))
                .toList();
        List<PromotionRankingItem> ranking = java.util.stream.IntStream.range(0, sorted.size())
                .mapToObj(i -> new PromotionRankingItem(sorted.get(i).campaignId(), sorted.get(i).bidderUserId(),
                        sorted.get(i).postId(), sorted.get(i).bidAmount(), i + 1))
                .toList();
        return new PromotionAuctionSnapshot(auctionWindowId, status, ranking, Instant.now());
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

    private PromotionAuctionDecision toDecision(PromotionAuctionDecisionRecord record) {
        try {
            return objectMapper.readValue(record.getPayloadJson(), PromotionAuctionDecision.class);
        } catch (Exception e) {
            throw new IllegalStateException("Invalid promotion decision payload", e);
        }
    }
}
