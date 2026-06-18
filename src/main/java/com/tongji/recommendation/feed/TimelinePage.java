package com.tongji.recommendation.feed;

import java.util.List;

public record TimelinePage(
        List<TimelineItem> items,
        String nextCursor
) {}
