package com.tongji.promotion.bprime.realtime;

import com.tongji.promotion.bprime.model.PromotionRankingItem;

import java.time.Instant;
import java.util.List;

/**
 * 房间公共状态通知；高频更新携带增量，终场事件才携带完整排名。
 *
 * @since 2026-08-09
 */
public record PromotionAuctionRealtimeEvent(
        String eventId,
        String eventType,
        long auctionWindowId,
        String decisionId,
        long decisionVersion,
        long eventVersion,
        String windowStatus,
        List<PromotionRankingItem> ranking,
        List<PromotionBidDelta> bidDeltas,
        Instant occurredAt
) {
    public static final String RANKING_DELTA = "RANKING_DELTA";
    public static final String WINDOW_CLOSED = "WINDOW_CLOSED";

    public PromotionAuctionRealtimeEvent {
        ranking = ranking == null ? List.of() : List.copyOf(ranking);
        bidDeltas = bidDeltas == null ? List.of() : List.copyOf(bidDeltas);
    }

    /**
     * 兼容旧的完整排名构造方式；新高频链路应使用 {@link #RANKING_DELTA}。
     */
    public PromotionAuctionRealtimeEvent(
            String eventId,
            String eventType,
            long auctionWindowId,
            String decisionId,
            long decisionVersion,
            long eventVersion,
            String windowStatus,
            List<PromotionRankingItem> ranking,
            Instant occurredAt) {
        this(eventId, eventType, auctionWindowId, decisionId, decisionVersion, eventVersion,
                windowStatus, ranking, List.of(), occurredAt);
    }
}
