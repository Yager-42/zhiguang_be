package com.tongji.knowpost.publish;

import java.time.Instant;

/**
 * 发布受理事务持久化的不可变执行快照。
 *
 * @param attemptId 发布尝试 ID
 * @param postId 知文 ID
 * @param authorId 作者 ID
 * @param runVersion 当前执行轮次，从 1 开始
 * @param contentObjectKey 受理时固定的 MinIO 对象 Key
 * @param contentEtag 受理时记录的对象 ETag，可为空
 * @param contentSha256 受理时固定的正文 SHA-256
 * @param requestedAt 受理时间，UTC 时间点
 * @since 2026-08-28
 */
public record PublishRequestedEvent(
        long attemptId,
        long postId,
        long authorId,
        int runVersion,
        String contentObjectKey,
        String contentEtag,
        String contentSha256,
        Instant requestedAt
) {
}
