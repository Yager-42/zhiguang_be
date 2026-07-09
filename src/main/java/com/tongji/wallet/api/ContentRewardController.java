package com.tongji.wallet.api;

import com.tongji.wallet.api.dto.ContentRewardConfigResponse;
import com.tongji.wallet.config.ContentRewardProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 内容创作奖励配置 API。供前端拉取奖励金额配置。
 * <p>
 * 需登录态访问（走 SecurityConfig 默认 anyRequest().authenticated()，不加 permitAll 白名单）。
 */
@RestController
@RequestMapping("/api/v1/content-reward")
@RequiredArgsConstructor
public class ContentRewardController {

    private final ContentRewardProperties properties;

    /**
     * 返回内容创作奖励配置。前端启动时拉一次缓存，发帖 / 发评论后按配置显示 "+N 积分"。
     */
    @GetMapping("/config")
    public ContentRewardConfigResponse config() {
        return new ContentRewardConfigResponse(
                properties.isEnabled(),
                properties.getPostAmount(),
                properties.getCommentAmount()
        );
    }
}
