package com.tongji.wallet.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 内容创作积分奖励配置，绑定前缀 {@code content-reward.*}。
 * <ul>
 *   <li>{@code enabled}：是否启用内容创作奖励，默认 true。关闭后 reward 为 no-op，前端不显示 "+N 积分"。</li>
 *   <li>{@code post-amount}：发帖奖励积分，默认 10。</li>
 *   <li>{@code comment-amount}：发评论奖励积分，默认 2。</li>
 * </ul>
 * <p>
 * 紧急开关：发现刷量时改 {@code enabled=false} 重启即可停止发放。
 */
@Data
@Component
@ConfigurationProperties(prefix = "content-reward")
public class ContentRewardProperties {

    /** 是否启用内容创作奖励。 */
    private boolean enabled = true;

    /** 发帖奖励积分。 */
    private long postAmount = 10L;

    /** 发评论奖励积分。 */
    private long commentAmount = 2L;
}
