package com.tongji.recommendation.feed;

import java.time.Instant;

public record TimelineDispatch(
        long contentId,
        long authorId,
        Instant publishTs,
        boolean largeAuthor
) {}
