package com.tongji.promotion.mapper;

import com.tongji.promotion.model.PromotionBid;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;

@Mapper
public interface PromotionBidMapper {

    int insert(PromotionBid bid);

    int insertIgnore(PromotionBid bid);

    int upsertAccepted(PromotionBid bid);

    PromotionBid findByCampaignIdAndAuctionWindowId(@Param("campaignId") long campaignId,
                                                    @Param("auctionWindowId") long auctionWindowId);

    List<PromotionBid> listActiveBidsByWindowId(@Param("auctionWindowId") long auctionWindowId,
                                                @Param("allocationStartAt") Instant allocationStartAt,
                                                @Param("allocationEndAt") Instant allocationEndAt);

    List<PromotionBid> listSettledBidsByWindowId(@Param("auctionWindowId") long auctionWindowId,
                                                 @Param("allocationStartAt") Instant allocationStartAt,
                                                 @Param("allocationEndAt") Instant allocationEndAt);

    int markWon(@Param("id") long id,
                @Param("slotIndex") int slotIndex,
                @Param("clearingPrice") long clearingPrice);

    int markLost(@Param("id") long id);
}
