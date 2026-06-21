package com.tongji.promotion.service;

import com.tongji.promotion.config.PaidBoostProperties;
import com.tongji.promotion.model.PaidBoostChannel;
import org.springframework.stereotype.Service;

/**
 * 仓内 boost 出价适配层：按创作者出价（{@code bidAmount}）换算有效加权值（{@code effectiveBoostValue}）。
 * <p>参考 bytedance 竞价域 {@code BidAmount/effectiveAmount} 语义，但不引入跨仓编译依赖；
 * 排序与 follow 优先级只消费本 service 返回值，不直接拿请求体出价入库。
 * 当前规则：推荐渠道取 {@code min(bidAmount, recommendationMaxBoostEffect)}；关注渠道取 {@code bidAmount}。</p>
 */
@Service
public class PaidBoostQuoteService {

    private final PaidBoostProperties properties;

    public PaidBoostQuoteService(PaidBoostProperties properties) {
        this.properties = properties;
    }

    public long quote(PaidBoostChannel channel, long bidAmount) {
        long validated = Math.max(bidAmount, 0L);
        if (channel == PaidBoostChannel.HOME_RECOMMENDATION) {
            return Math.min(validated, properties.getRecommendationMaxBoostEffect());
        }
        return validated;
    }
}
