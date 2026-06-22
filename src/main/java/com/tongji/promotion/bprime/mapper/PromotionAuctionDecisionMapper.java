package com.tongji.promotion.bprime.mapper;

import com.tongji.promotion.bprime.model.PromotionAuctionDecisionRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface PromotionAuctionDecisionMapper {
    int insertIgnore(PromotionAuctionDecisionRecord decision);

    PromotionAuctionDecisionRecord findByDecisionId(@Param("decisionId") String decisionId);

    List<PromotionAuctionDecisionRecord> listAcceptedByWindow(@Param("auctionWindowId") long auctionWindowId);
}
