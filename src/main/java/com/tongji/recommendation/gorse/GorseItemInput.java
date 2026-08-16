package com.tongji.recommendation.gorse;

import java.time.Instant;
import java.util.List;

/**
 * 同步到 Gorse 的知文物料。
 */
public record GorseItemInput(
        long postId,
        long authorId,
        Instant publishedAt,
        String title,
        List<String> topics
) {
    public GorseItemInput {
        title = title == null ? "" : title;
        topics = topics == null ? List.of() : List.copyOf(topics);
    }
}
