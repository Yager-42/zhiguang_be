package com.tongji.promotion.mapper;

import com.tongji.promotion.model.PaidBoostCampaign;
import com.tongji.promotion.model.PaidBoostChannel;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;

/**
 * boost 活动持久化。
 * <p>{@link #listActiveByChannel} 供 active boost 读路径查询某渠道当前生效、预算未耗尽的活动（按 boost 值倒序）；
 * {@link #listClosable} 取已到期仍 ACTIVE 的活动供关闭结算；结算侧 {@link #increaseBudgetConsumed} 累加已结算 capture 额。</p>
 */
@Mapper
public interface PaidBoostCampaignMapper {

    int insert(PaidBoostCampaign campaign);

    PaidBoostCampaign findById(@Param("id") long id);

    /** 某渠道当前时间覆盖 [start_at, end_at)、ACTIVE 且预算未耗尽的活动，按 boost_value 倒序、id 升序。 */
    List<PaidBoostCampaign> listActiveByChannel(@Param("channel") PaidBoostChannel channel,
                                                @Param("now") Instant now);

    /** 已到期（end_at <= now）且仍 ACTIVE 的活动，按 end_at 升序，限量关闭。 */
    List<PaidBoostCampaign> listClosable(@Param("now") Instant now, @Param("limit") int limit);

    /** 结算累加：budget_consumed += amount。调用方已按剩余预算取 min，保证不超扣。 */
    int increaseBudgetConsumed(@Param("id") long id, @Param("amount") long amount, @Param("updatedAt") Instant updatedAt);

    /** 关闭活动：status=CLOSED + closed_at。 */
    int markClosed(@Param("id") long id, @Param("closedAt") Instant closedAt, @Param("updatedAt") Instant updatedAt);
}
