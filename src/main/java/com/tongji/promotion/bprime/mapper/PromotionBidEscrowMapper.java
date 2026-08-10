package com.tongji.promotion.bprime.mapper;

import com.tongji.promotion.bprime.model.PromotionBidEscrowRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;

@Mapper
public interface PromotionBidEscrowMapper {

    int insert(PromotionBidEscrowRecord record);

    PromotionBidEscrowRecord findByWindowAndCampaign(@Param("auctionWindowId") long auctionWindowId,
                                                      @Param("campaignId") long campaignId);

    PromotionBidEscrowRecord findById(@Param("id") long id);

    int increaseAuthorization(@Param("auctionWindowId") long auctionWindowId,
                              @Param("campaignId") long campaignId,
                              @Param("authorizedAmount") long authorizedAmount,
                              @Param("expiresAt") Instant expiresAt,
                              @Param("updatedAt") Instant updatedAt);

    int updateCurrentHold(@Param("auctionWindowId") long auctionWindowId,
                          @Param("campaignId") long campaignId,
                          @Param("currentHold") long currentHold,
                          @Param("updatedAt") Instant updatedAt);

    List<PromotionBidEscrowRecord> listActiveByWindowId(@Param("auctionWindowId") long auctionWindowId);

    List<PromotionBidEscrowRecord> listByWindowId(@Param("auctionWindowId") long auctionWindowId);

    int markClosedByWindowId(@Param("auctionWindowId") long auctionWindowId,
                             @Param("updatedAt") Instant updatedAt);
}
