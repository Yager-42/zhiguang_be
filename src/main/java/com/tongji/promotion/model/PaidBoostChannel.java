package com.tongji.promotion.model;

/**
 * 付费加权投放渠道：只覆盖推荐排序与关注触达两类，不混入 search / 公共流 / 拍卖位。
 * <ul>
 *   <li>{@link #wireValue()} 返回对外请求/响应中的渠道串（小写下划线）。</li>
 *   <li>{@link #boostPlacement()} 返回 feed 商业位 placement 元数据值（带 {@code _boost} 后缀，
 *       区别于 slot allocation 的 {@code feed_top_slot}）。</li>
 * </ul>
 */
public enum PaidBoostChannel {
    HOME_RECOMMENDATION,
    FOLLOW_DELIVERY;

    /** 对外渠道串：请求体 channel 字段值与缓存 key 后缀。 */
    public String wireValue() {
        return name().toLowerCase();
    }

    /** feed 商业位 placement 元数据：客户端据此区分 boost 投放与 organic。 */
    public String boostPlacement() {
        return name().toLowerCase() + "_boost";
    }

    /** 解析请求渠道串（大小写不敏感）；非法返回 null 由调用方抛错。 */
    public static PaidBoostChannel fromString(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return PaidBoostChannel.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
