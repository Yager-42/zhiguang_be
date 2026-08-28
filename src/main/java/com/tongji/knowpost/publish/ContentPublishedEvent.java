package com.tongji.knowpost.publish;

import java.time.Instant;

/**
 * 发布事实事务内写入 Outbox 的领域事件。
 *
 * @since 2026-08-28
 */
public record ContentPublishedEvent(
        long postId,
        long authorId,
        long publishAttemptId,
        int runVersion,
        Instant publishedAt
) {
}
