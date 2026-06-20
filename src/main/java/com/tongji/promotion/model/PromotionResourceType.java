package com.tongji.promotion.model;

/**
 * 推广资源类型：本期仅覆盖首页置顶位与搜索置顶位两类离散广告位。
 * <p>{@link #placement()} 返回对外响应中的 placement 元数据值（小写下划线）。</p>
 */
public enum PromotionResourceType {
    FEED_TOP_SLOT,
    SEARCH_TOP_SLOT;

    public String placement() {
        return name().toLowerCase();
    }
}
