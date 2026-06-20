package com.tongji.promotion.mapper;

import com.tongji.promotion.model.PromotionBid;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 推广出价持久化。
 * <p>结算侧 {@link #markWon} / {@link #markLost} 由 GSP 结算驱动；{@link #listActiveBidsByWindowId} 按出价倒序读 ACTIVE 出价供排序。</p>
 */
@Mapper
public interface PromotionBidMapper {

    int insert(PromotionBid bid);

    /** 同活动同窗口是否已出价（防重复）。 */
    PromotionBid findByCampaignIdAndAuctionWindowId(@Param("campaignId") long campaignId,
                                                    @Param("auctionWindowId") long auctionWindowId);

    /** 窗口下所有 ACTIVE 出价，按 bid_amount 倒序、id 升序（稳定排序）。 */
    List<PromotionBid> listActiveBidsByWindowId(@Param("auctionWindowId") long auctionWindowId);

    /** 中标结算：写成交价与位号。 */
    int markWon(@Param("id") long id,
                @Param("slotIndex") int slotIndex,
                @Param("clearingPrice") long clearingPrice);

    /** 落败结算：仅置状态。 */
    int markLost(@Param("id") long id);
}
