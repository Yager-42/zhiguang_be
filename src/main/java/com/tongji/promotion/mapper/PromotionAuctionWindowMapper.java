package com.tongji.promotion.mapper;

import com.tongji.promotion.model.PromotionAuctionWindow;
import com.tongji.promotion.model.PromotionResourceType;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;

/**
 * 竞价窗口持久化。
 * <p>{@link #findOpenWindow} 取当前 OPEN 且覆盖 {@code now} 的窗口；{@link #findExactWindow} 用于窗口续建的幂等判等；
 * {@link #listClosableWindows} 取已到期待结算窗口。</p>
 */
@Mapper
public interface PromotionAuctionWindowMapper {

    int insert(PromotionAuctionWindow window);

    PromotionAuctionWindow findById(@Param("id") long id);

    PromotionAuctionWindow findByIdForUpdate(@Param("id") long id);

    /**
     * 按窗口 ID 批量读取窗口状态。
     *
     * @param ids 已限制为单页上限内的窗口 ID，不允许为空
     * @return 窗口列表，不返回 {@code null}
     */
    List<PromotionAuctionWindow> listByIds(@Param("ids") List<Long> ids);

    /** 当前处于 OPEN 且时间覆盖 now 的窗口（收单窗口）。 */
    PromotionAuctionWindow findOpenWindow(@Param("resourceType") PromotionResourceType resourceType,
                                          @Param("now") Instant now);

    /** 精确匹配某资源类型 + 起止时间的窗口（续建幂等）。 */
    PromotionAuctionWindow findExactWindow(@Param("resourceType") PromotionResourceType resourceType,
                                           @Param("startAt") Instant startAt,
                                           @Param("endAt") Instant endAt);

    /** 已到期（window_end_at <= now）且仍 OPEN 的窗口，按到期时间升序，限量结算。 */
    List<PromotionAuctionWindow> listClosableWindows(@Param("now") Instant now,
                                                     @Param("batchSize") int batchSize);
    /** 启动恢复所需的 OPEN 活跃窗口。 */
    List<PromotionAuctionWindow> listActiveWindows();

    /** 已结算窗口游标，用于对账按 settled_at + id 稳定推进。 */
    List<PromotionAuctionWindow> listSettledWindowsCursor(@Param("lastSettledAt") Instant lastSettledAt,
                                                          @Param("lastWindowId") long lastWindowId,
                                                          @Param("lookbackStart") Instant lookbackStart,
                                                          @Param("batchSize") int batchSize);

    /** 标记窗口已结算：status=SETTLED + settled_at。 */
    int markSettled(@Param("id") long id, @Param("settledAt") Instant settledAt);

    int markSettledIfOpen(@Param("id") long id, @Param("settledAt") Instant settledAt);
}
