package com.tongji.knowpost.api.dto;

import java.util.List;

/**
 * 首页 Feed 单条记录。
 *
 * <p>商业位元数据（{@code promoted}/{@code commercial}/{@code placementType}/
 * {@code promotionCampaignId}/{@code auctionWindowId}）显式区分 promoted 与 organic，
 * 不复用 {@code isTop}（后者仅表达帖子自身置顶状态）。organic 项这些字段为 {@code false}/{@code null}。</p>
 */
public record FeedItemResponse(
        String id,
        String title,
        String description,
        String coverImage,
        List<String> tags,
        String authorAvatar,
        String authorNickname,
        String tagJson,
        Long likeCount,
        Long favoriteCount,
        Boolean liked,
        Boolean faved,
        Boolean isTop,
        Boolean promoted,
        Boolean commercial,
        String placementType,
        String promotionCampaignId,
        String auctionWindowId
) {
    /** 构造 organic 项（无商业位）。 */
    public static FeedItemResponse organic(String id, String title, String description, String coverImage, List<String> tags,
                                           String authorAvatar, String authorNickname, String tagJson,
                                           Long likeCount, Long favoriteCount, Boolean liked, Boolean faved, Boolean isTop) {
        return new FeedItemResponse(id, title, description, coverImage, tags, authorAvatar, authorNickname, tagJson,
                likeCount, favoriteCount, liked, faved, isTop, false, false, null, null, null);
    }

    /** 覆盖互动计数/用户态，保留其余字段（含商业位标识）。 */
    public FeedItemResponse withInteractions(Long likeCount, Long favoriteCount, Boolean liked, Boolean faved) {
        return new FeedItemResponse(id, title, description, coverImage, tags, authorAvatar, authorNickname, tagJson,
                likeCount, favoriteCount, liked, faved, isTop, promoted, commercial, placementType,
                promotionCampaignId, auctionWindowId);
    }

    /** 标记为商业位（promoted + commercial + placement 元数据）。 */
    public FeedItemResponse withPromotion(String placementType, String promotionCampaignId, String auctionWindowId) {
        return new FeedItemResponse(id, title, description, coverImage, tags, authorAvatar, authorNickname, tagJson,
                likeCount, favoriteCount, liked, faved, isTop, true, true, placementType, promotionCampaignId, auctionWindowId);
    }
}
