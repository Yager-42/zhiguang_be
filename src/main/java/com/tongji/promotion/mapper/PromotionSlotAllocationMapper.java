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

    int countByAuctionWindowId(@Param("auctionWindowId") long auctionWindowId);

    List<PromotionSlotAllocation> listByAuctionWindowId(@Param("auctionWindowId") long auctionWindowId);

    /**
     * 批量读取参赛记录已生成的推荐位分配。
     *
     * @param campaignIds 已限制为单页上限内的参赛记录 ID，不允许为空
     * @return 每个胜出参赛记录的实际分配，按开始时间倒序
     */
    List<PromotionSlotAllocation> listByCampaignIds(@Param("campaignIds") List<Long> campaignIds);

    /** 当前时间覆盖 [allocation_start_at, allocation_end_at) 的有效占位，按位号升序。 */
    List<PromotionSlotAllocation> listActive(@Param("resourceType") PromotionResourceType resourceType,
                                             @Param("now") Instant now);
}
