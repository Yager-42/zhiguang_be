package com.tongji.knowpost.publish;

import java.time.Instant;

public record ContentPublishedEvent(
        long postId,
        long authorId,
        long publishAttemptId,
        Instant publishedAt
) {}
