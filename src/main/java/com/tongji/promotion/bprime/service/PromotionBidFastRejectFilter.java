package com.tongji.promotion.bprime.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.tongji.promotion.bprime.config.PromotionBPrimeProperties;
import com.tongji.promotion.bprime.model.PromotionAuctionDecision;
import com.tongji.promotion.bprime.model.PromotionBidFastRejectionReason;
import com.tongji.promotion.bprime.model.PromotionBidRoute;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * 维护进程内单调竞价水位，只提前拒绝能够被本地状态证明必败的出价。
 *
 * <p>实现直接遵循 Eliaaazzz 网关预聚合过滤的安全约束：接受仍由 Redis Lua 决定；缓存缺失或落后时
 * 一律放行。水位通过 {@code max(old, acceptedBid)} 更新，因此乱序接受事件不会造成水位倒退。</p>
 *
 * @since 2026-08-09
 */
@Component
public class PromotionBidFastRejectFilter {

    private static final String OPEN_STATUS = "OPEN";
    private static final String BID_ACCEPTED = "BID_ACCEPTED";

    private final boolean enabled;
    private final long expiryMarginMs;
    private final Cache<Long, CampaignWatermark> campaignWatermarks;

    public PromotionBidFastRejectFilter(PromotionBPrimeProperties properties) {
        this.enabled = properties.isFastRejectEnabled();
        this.expiryMarginMs = properties.getFastRejectExpiryMarginMs();
        Duration expiration = Duration.ofSeconds(properties.getFastRejectExpireAfterAccessSeconds());
        this.campaignWatermarks = Caffeine.newBuilder()
                .maximumSize(properties.getFastRejectMaximumCampaigns())
                .expireAfterAccess(expiration)
                .build();
    }

    /**
     * 使用可靠的 campaign 路由预热或刷新本地水位元数据。
     *
     * <p>相同窗口只刷新路由字段并保留已接受最高价；campaign 进入新窗口时重置最高价。该方法不访问外部系统。</p>
     *
     * @param route Redis 中已校验的 campaign 出价路由
     */
    public void observeRoute(PromotionBidRoute route) {
        if (!enabled) {
            return;
        }
        Objects.requireNonNull(route, "route");
        Objects.requireNonNull(route.windowEndAt(), "route.windowEndAt");
        campaignWatermarks.asMap().compute(route.campaignId(), (campaignId, current) -> {
            long acceptedBid = current != null && current.auctionWindowId() == route.auctionWindowId()
                    ? current.highestAcceptedBid()
                    : 0L;
            return new CampaignWatermark(route.auctionWindowId(), route.bidderUserId(), route.reservePrice(),
                    route.windowStatus(), route.windowEndAt(), acceptedBid);
        });
    }

    /**
     * 观察 Redis Lua 已完成的决策，并在 Kafka 确认前推进本地单调水位。
     *
     * <p>只有已预热且窗口匹配的 campaign 才更新，避免迟到的旧窗口事件污染新窗口。Kafka 失败不会回滚
     * Redis 热状态，因此本地水位也不回滚。</p>
     *
     * @param decision Redis Lua 返回的权威决策
     */
    public void observeDecision(PromotionAuctionDecision decision) {
        if (!enabled) {
            return;
        }
        Objects.requireNonNull(decision, "decision");
        if (!decision.accepted() || !BID_ACCEPTED.equals(decision.type())) {
            return;
        }
        campaignWatermarks.asMap().computeIfPresent(decision.campaignId(), (campaignId, current) -> {
            if (current.auctionWindowId() != decision.auctionWindowId()
                    || current.bidderUserId() != decision.bidderUserId()) {
                return current;
            }
            return current.withHighestAcceptedBid(Math.max(current.highestAcceptedBid(), decision.bidAmount()));
        });
    }

    /**
     * 查询本地状态能否证明当前出价必败。
     *
     * @param bidderUserId 当前认证用户 ID
     * @param campaignId 推广 campaign ID
     * @param bidAmount 出价金额，必须为正数
     * @param now 服务端当前时间
     * @return 可证明的拒绝；缓存缺失、owner 不匹配或状态不确定时返回空并交给权威链路
     */
    public Optional<FastRejectCandidate> findCandidate(long bidderUserId, long campaignId, long bidAmount, Instant now) {
        if (!enabled) {
            return Optional.empty();
        }
        Objects.requireNonNull(now, "now");
        CampaignWatermark watermark = campaignWatermarks.getIfPresent(campaignId);
        if (watermark == null || watermark.bidderUserId() != bidderUserId) {
            return Optional.empty();
        }
        Instant latestSafeFastRejectAt = watermark.windowEndAt().minusMillis(expiryMarginMs);
        if (!OPEN_STATUS.equals(watermark.windowStatus()) || !now.isBefore(latestSafeFastRejectAt)) {
            return Optional.empty();
        }
        if (watermark.highestAcceptedBid() > 0L
                && bidAmount >= watermark.reservePrice()
                && bidAmount <= watermark.highestAcceptedBid()) {
            return Optional.of(new FastRejectCandidate(watermark.auctionWindowId(),
                    PromotionBidFastRejectionReason.BID_NOT_HIGHER));
        }
        return Optional.empty();
    }

    /** 本地可证明的同步拒绝结果。 */
    public record FastRejectCandidate(long auctionWindowId, PromotionBidFastRejectionReason reason) {
    }

    private record CampaignWatermark(long auctionWindowId, long bidderUserId, long reservePrice,
                                     String windowStatus, Instant windowEndAt, long highestAcceptedBid) {

        private CampaignWatermark withHighestAcceptedBid(long bidAmount) {
            return new CampaignWatermark(auctionWindowId, bidderUserId, reservePrice, windowStatus, windowEndAt,
                    bidAmount);
        }
    }
}
