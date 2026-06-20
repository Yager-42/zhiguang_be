package com.tongji.promotion.mapper;

import com.tongji.promotion.model.PromotionCampaign;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 推广活动持久化。 */
@Mapper
public interface PromotionCampaignMapper {

    int insert(PromotionCampaign campaign);

    PromotionCampaign findById(@Param("id") long id);
}
