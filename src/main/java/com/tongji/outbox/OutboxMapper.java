package com.tongji.outbox;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 持久化和有界清理共享 Outbox 事件。
 *
 * @since 2026-08-28
 */
@Mapper
public interface OutboxMapper {
    int insert(@Param("id") Long id,
               @Param("aggregateType") String aggregateType,
               @Param("aggregateId") Long aggregateId,
               @Param("type") String type,
               @Param("payload") String payload);

    int insertUnique(@Param("id") Long id,
                     @Param("eventKey") String eventKey,
                     @Param("aggregateType") String aggregateType,
                     @Param("aggregateId") Long aggregateId,
                     @Param("type") String type,
                     @Param("payload") String payload);

    int deleteCreatedBefore(@Param("cutoff") java.time.LocalDateTime cutoff,
                            @Param("limit") int limit);
}
