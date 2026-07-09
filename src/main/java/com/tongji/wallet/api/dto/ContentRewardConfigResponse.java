package com.tongji.wallet.api.dto;

/**
 * 内容创作奖励配置响应。供前端拉取奖励金额，发帖 / 发评论后按配置显示 "+N 积分"。
 *
 * @param enabled       是否启用内容创作奖励（false 时前端不显示提示）。
 * @param postAmount    发帖奖励积分。
 * @param commentAmount 发评论奖励积分。
 */
public record ContentRewardConfigResponse(
        boolean enabled,
        long postAmount,
        long commentAmount
) {}
