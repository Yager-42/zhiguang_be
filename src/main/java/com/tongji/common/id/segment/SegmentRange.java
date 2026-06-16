package com.tongji.common.id.segment;

public class SegmentRange {
    private final long startInclusive;
    private final long endInclusive;

    public SegmentRange(long startInclusive, long endInclusive) {
        if (startInclusive > endInclusive) {
            throw new IllegalArgumentException("startInclusive must be less than or equal to endInclusive");
        }
        this.startInclusive = startInclusive;
        this.endInclusive = endInclusive;
    }

    public long getStartInclusive() {
        return startInclusive;
    }

    public long getEndInclusive() {
        return endInclusive;
    }
}
