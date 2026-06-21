package com.tongji.promotion.mapper;

import com.tongji.promotion.model.PaidBoostDelivery;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;

/**
 * boost 投放事实持久化。
 * <p>{@link #upsertPending} 依赖唯一键 {@code (campaign_id, delivery_bucket_start_at, viewer_user_id)}：
 * 同 bucket 内同 viewer 重复送达同一活动只累加 {@code delivery_count}，不新增第二条计费事实。
 * 结算侧 {@link #listPendingBefore} / {@link #markSettledWithAmount} 由定时聚合器驱动。</p>
 */
@Mapper
public interface PaidBoostDeliveryMapper {

    /** 记录/累加 delivery 事实；同 (campaign,bucket,viewer) 命中唯一键时只增加 delivery_count。 */
    int upsertPending(PaidBoostDelivery delivery);

    /** PENDING 且 bucket 起始时间早于 cutoff 的 delivery，按 bucket 升序，限量结算。 */
    List<PaidBoostDelivery> listPendingBefore(@Param("now") Instant now, @Param("limit") int limit);

    /** 结算完成：写 captured_amount + status=SETTLED + settled_at。 */
    int markSettledWithAmount(@Param("id") long id, @Param("capturedAmount") long capturedAmount,
                              @Param("settledAt") Instant settledAt);

    /** 查询某活动的 delivery 明细（按 bucket 倒序），供 API 分页。 */
    List<PaidBoostDelivery> listByCampaignId(@Param("campaignId") long campaignId,
                                             @Param("limit") int limit,
                                             @Param("offset") int offset);
}
