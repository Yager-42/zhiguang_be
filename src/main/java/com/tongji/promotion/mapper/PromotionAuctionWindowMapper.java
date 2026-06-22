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

    /** 标记窗口已结算：status=SETTLED + settled_at。 */
    int markSettled(@Param("id") long id, @Param("settledAt") Instant settledAt);
}
