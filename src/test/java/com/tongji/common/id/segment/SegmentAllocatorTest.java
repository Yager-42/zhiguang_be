package com.tongji.common.id.segment;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SegmentAllocatorTest {

    @Test
    void allocateSegmentReturnsClosedRangeFromLeafAlloc() {
        LeafAllocMapper mapper = mock(LeafAllocMapper.class);
        LeafAlloc leafAlloc = new LeafAlloc();
        leafAlloc.setBizTag("audit_log");
        leafAlloc.setMaxId(2000L);
        leafAlloc.setStep(1000);
        when(mapper.updateMaxId("audit_log")).thenReturn(1);
        when(mapper.selectByBizTag("audit_log")).thenReturn(leafAlloc);

        SegmentAllocator allocator = new SegmentAllocator(mapper);

        SegmentRange range = allocator.allocateSegment("audit_log");

        assertThat(range.getStartInclusive()).isEqualTo(1001L);
        assertThat(range.getEndInclusive()).isEqualTo(2000L);

        InOrder inOrder = inOrder(mapper);
        inOrder.verify(mapper).updateMaxId("audit_log");
        inOrder.verify(mapper).selectByBizTag("audit_log");
    }

    @Test
    void allocateSegmentThrowsWhenBizTagDoesNotExist() {
        LeafAllocMapper mapper = mock(LeafAllocMapper.class);
        when(mapper.updateMaxId("missing")).thenReturn(0);

        SegmentAllocator allocator = new SegmentAllocator(mapper);

        assertThatThrownBy(() -> allocator.allocateSegment("missing"))
                .isInstanceOf(SegmentLoadException.class)
                .hasMessageContaining("missing");
    }

    @Test
    void allocateSegmentThrowsWhenUpdatedMoreThanOneRow() {
        LeafAllocMapper mapper = mock(LeafAllocMapper.class);
        LeafAlloc leafAlloc = new LeafAlloc();
        leafAlloc.setBizTag("audit_log");
        leafAlloc.setMaxId(2000L);
        leafAlloc.setStep(1000);
        when(mapper.updateMaxId("audit_log")).thenReturn(2);
        when(mapper.selectByBizTag("audit_log")).thenReturn(leafAlloc);

        SegmentAllocator allocator = new SegmentAllocator(mapper);

        assertThatThrownBy(() -> allocator.allocateSegment("audit_log"))
                .isInstanceOf(SegmentLoadException.class)
                .hasMessageContaining("audit_log");
    }

    @Test
    void allocateSegmentThrowsWhenUpdatedRowCannotBeReloaded() {
        LeafAllocMapper mapper = mock(LeafAllocMapper.class);
        when(mapper.updateMaxId("audit_log")).thenReturn(1);
        when(mapper.selectByBizTag("audit_log")).thenReturn(null);

        SegmentAllocator allocator = new SegmentAllocator(mapper);

        assertThatThrownBy(() -> allocator.allocateSegment("audit_log"))
                .isInstanceOf(SegmentLoadException.class)
                .hasMessageContaining("audit_log");
    }
}
