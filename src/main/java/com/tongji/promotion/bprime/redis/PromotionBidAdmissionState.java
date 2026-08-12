package com.tongji.promotion.bprime.redis;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.tongji.promotion.bprime.config.PromotionBPrimeProperties;
import com.tongji.promotion.bprime.model.PromotionAuctionBatchResult;
import com.tongji.promotion.bprime.model.PromotionAuctionDecision;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 保存 Redis 已提交的版本化窗口 admission 状态；仅用于确定性拒绝，绝不接受出价。
 *
 * @since 2026-08-12
 */
@Component
public class PromotionBidAdmissionState {

    private final Cache<Long, Snapshot> states;

    public PromotionBidAdmissionState(PromotionBPrimeProperties properties) {
        states = Caffeine.newBuilder()
                .maximumSize(properties.getFastRejectPriceCacheMaximumSize())
                .expireAfterAccess(Duration.ofMinutes(5))
                .build();
    }

    /** 批量 Lua 返回后按版本更新本实例状态。 */
    public void update(long windowId, PromotionAuctionBatchResult result) {
        merge(windowId, new Snapshot(result.committedPriceCents(), result.winnerCommandId(),
                result.winnerCampaignId(), result.status(), result.actualEndAtEpochMs(), result.decisionVersion()));
    }

    /** Stream 事实按版本推进其他实例状态。 */
    public void update(PromotionAuctionDecision decision) {
        long price = payloadLong(decision, "currentPriceCents", decision.bidAmount());
        long winnerCampaignId = payloadLong(decision, "winnerCampaignId", decision.campaignId());
        long endAt = payloadLong(decision, "endAtEpochMs",
                payloadLong(decision, "actualEndAtEpochMs", Long.MAX_VALUE));
        String status = switch (decision.type()) {
            case "AUCTION_SOLD" -> "SOLD";
            case "AUCTION_NO_BID" -> "NO_BID";
            default -> "OPEN";
        };
        merge(decision.auctionWindowId(), new Snapshot(price,
                decision.accepted() && "BID_ACCEPTED".equals(decision.type()) ? decision.commandId() : null,
                winnerCampaignId, status, endAt, decision.decisionVersion()));
    }

    public Snapshot get(long windowId) {
        return states.getIfPresent(windowId);
    }

    private void merge(long windowId, Snapshot incoming) {
        states.asMap().compute(windowId, (ignored, current) -> {
            if (current == null || incoming.decisionVersion() > current.decisionVersion()) {
                return incoming;
            }
            if (incoming.decisionVersion() < current.decisionVersion()) {
                return current;
            }
            long price = Math.max(current.committedPriceCents(), incoming.committedPriceCents());
            String terminalStatus = "OPEN".equals(current.status()) ? incoming.status() : current.status();
            return new Snapshot(price,
                    incoming.winnerCommandId() == null ? current.winnerCommandId() : incoming.winnerCommandId(),
                    incoming.winnerCampaignId() == 0 ? current.winnerCampaignId() : incoming.winnerCampaignId(),
                    terminalStatus, Math.max(current.actualEndAtEpochMs(), incoming.actualEndAtEpochMs()),
                    current.decisionVersion());
        });
    }

    private long payloadLong(PromotionAuctionDecision decision, String field, long fallback) {
        Object value = decision.payload().get(field);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    public record Snapshot(
            long committedPriceCents,
            String winnerCommandId,
            long winnerCampaignId,
            String status,
            long actualEndAtEpochMs,
            long decisionVersion
    ) {
    }
}
