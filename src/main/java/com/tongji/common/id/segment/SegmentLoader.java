package com.tongji.common.id.segment;

@FunctionalInterface
public interface SegmentLoader {
    SegmentRange load(String bizTag);
}
