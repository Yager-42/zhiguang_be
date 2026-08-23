package com.tongji.promotion.mapper;

import com.tongji.promotion.model.PromotionCampaign;
import com.tongji.promotion.model.PromotionResourceType;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;

/** 推广活动持久化。 */
@Mapper
public interface PromotionCampaignMapper {

    int insert(PromotionCampaign campaign);

    /** 按系统场次写入参赛记录；重复报名保持原记录不变。 */
    int insertParticipation(PromotionCampaign campaign);

    PromotionCampaign findById(@Param("id") long id);

    PromotionCampaign findByIdForUpdate(@Param("id") long id);

    PromotionCampaign findExactParticipation(@Param("creatorUserId") long creatorUserId,
                                               @Param("postId") long postId,
                                               @Param("resourceType") PromotionResourceType resourceType,
                                               @Param("startAt") Instant startAt,
                                               @Param("endAt") Instant endAt);

    /** 按创建时间倒序分页查询指定用户的推广活动。 */
    List<PromotionCampaign> listByCreatorUserId(@Param("creatorUserId") long creatorUserId,
                                                @Param("limit") int limit,
                                                @Param("offset") int offset);
}
