package com.tongji.knowpost.publish;

import java.time.Instant;

/**
 * 从共享 Outbox 总线读取的发布完成消息。
 *
 * @param postId 知文 ID
 * @param authorId 作者 ID
 * @param publishAttemptId 发布尝试 ID
 * @param runVersion 完成发布的执行轮次
 * @param publishedAt 发布事实时间，UTC 时间点
 * @since 2026-08-28
 */
public record ContentPublishedMessage(
        long postId,
        long authorId,
        long publishAttemptId,
        int runVersion,
        Instant publishedAt
) {
}
