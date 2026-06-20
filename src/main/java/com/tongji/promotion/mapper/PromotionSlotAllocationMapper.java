package com.tongji.promotion.mapper;

import com.tongji.promotion.model.PromotionResourceType;
import com.tongji.promotion.model.PromotionSlotAllocation;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;

/**
 * 位分配持久化。{@link #listActive} 供 feed/search 读路径查询当前有效占位。
 */
@Mapper
public interface PromotionSlotAllocationMapper {

    int insert(PromotionSlotAllocation allocation);

    /** 当前时间覆盖 [allocation_start_at, allocation_end_at) 的有效占位，按位号升序。 */
    List<PromotionSlotAllocation> listActive(@Param("resourceType") PromotionResourceType resourceType,
                                             @Param("now") Instant now);
}
