package com.tongji.promotion.bprime.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface PromotionProjectionCheckpointMapper {
    int upsert(@Param("auctionWindowId") long auctionWindowId,
               @Param("lastDecisionId") String lastDecisionId,
               @Param("lastKafkaTopic") String lastKafkaTopic,
               @Param("lastKafkaPartition") Integer lastKafkaPartition,
               @Param("lastKafkaOffset") Long lastKafkaOffset);
}
