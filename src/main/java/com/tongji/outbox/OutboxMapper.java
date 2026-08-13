package com.tongji.outbox;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * Persists domain events in the shared outbox table.
 */
@Mapper
public interface OutboxMapper {
    int insert(@Param("id") Long id,
               @Param("aggregateType") String aggregateType,
               @Param("aggregateId") Long aggregateId,
               @Param("type") String type,
               @Param("payload") String payload);
}
