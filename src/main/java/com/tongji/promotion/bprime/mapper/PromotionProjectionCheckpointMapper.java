package com.tongji.promotion.bprime.mapper;

import com.tongji.promotion.bprime.model.PromotionProjectionCheckpointRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface PromotionProjectionCheckpointMapper {
    PromotionProjectionCheckpointRecord findByAuctionWindowId(@Param("auctionWindowId") long auctionWindowId);

    int upsert(@Param("auctionWindowId") long auctionWindowId,
               @Param("lastDecisionId") String lastDecisionId,
               @Param("lastDecisionVersion") long lastDecisionVersion,
               @Param("lastStreamId") String lastStreamId);
}
