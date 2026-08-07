package com.tongji.comment.model;

import java.time.LocalDateTime;

public record CommentOutboxRetry(
        Long eventId,
        int retryCount,
        LocalDateTime nextAttemptAt,
        String lastError) {
}
