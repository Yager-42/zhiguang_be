package com.tongji.recommendation.feed;

import java.time.Instant;

public record TimelineItem(
        long contentId,
        long authorId,
        Instant publishTs
) {}
