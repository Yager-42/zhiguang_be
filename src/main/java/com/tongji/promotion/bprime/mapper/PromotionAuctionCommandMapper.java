package com.tongji.promotion.bprime.mapper;

import com.tongji.promotion.bprime.model.PromotionAuctionCommandRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;

@Mapper
public interface PromotionAuctionCommandMapper {
    int insert(PromotionAuctionCommandRecord command);

    PromotionAuctionCommandRecord findByCommandId(@Param("commandId") String commandId);

    PromotionAuctionCommandRecord findByIdempotency(@Param("auctionWindowId") long auctionWindowId,
                                                    @Param("bidderUserId") long bidderUserId,
                                                    @Param("idempotencyKey") String idempotencyKey);

    List<PromotionAuctionCommandRecord> listPublishable(@Param("publishStaleBefore") Instant publishStaleBefore,
                                                        @Param("decisionStaleBefore") Instant decisionStaleBefore,
                                                        @Param("limit") int limit);

    int claimForPublishingBatch(@Param("commandIds") List<String> commandIds,
                                @Param("publishStaleBefore") Instant publishStaleBefore,
                                @Param("decisionStaleBefore") Instant decisionStaleBefore);

    int markPublishedBatch(@Param("commandIds") List<String> commandIds);

    int updateStatusBatch(@Param("commandIds") List<String> commandIds,
                          @Param("status") String status);
}
