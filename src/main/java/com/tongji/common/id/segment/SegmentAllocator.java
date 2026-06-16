package com.tongji.common.id.segment;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SegmentAllocator {
    private final LeafAllocMapper leafAllocMapper;

    public SegmentAllocator(LeafAllocMapper leafAllocMapper) {
        this.leafAllocMapper = leafAllocMapper;
    }

    @Transactional
    public SegmentRange allocateSegment(String bizTag) {
        int updatedRows = leafAllocMapper.updateMaxId(bizTag);
        if (updatedRows != 1) {
            throw new SegmentLoadException("Expected exactly one leaf_alloc row for bizTag=" + bizTag
                    + " but updatedRows=" + updatedRows);
        }

        LeafAlloc leafAlloc = leafAllocMapper.selectByBizTag(bizTag);
        if (leafAlloc == null) {
            throw new SegmentLoadException("Failed to reload leaf_alloc row for bizTag=" + bizTag);
        }

        long endInclusive = leafAlloc.getMaxId();
        long startInclusive = endInclusive - leafAlloc.getStep() + 1L;
        return new SegmentRange(startInclusive, endInclusive);
    }
}
