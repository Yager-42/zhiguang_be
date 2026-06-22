package com.tongji.promotion.bprime.mapper;

import com.tongji.promotion.bprime.model.PromotionAuctionCommandRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface PromotionAuctionCommandMapper {
    int insert(PromotionAuctionCommandRecord command);

    PromotionAuctionCommandRecord findByCommandId(@Param("commandId") String commandId);

    PromotionAuctionCommandRecord findByIdempotency(@Param("auctionWindowId") long auctionWindowId,
                                                    @Param("bidderUserId") long bidderUserId,
                                                    @Param("idempotencyKey") String idempotencyKey);

    int updateStatus(@Param("commandId") String commandId, @Param("status") String status);
}
