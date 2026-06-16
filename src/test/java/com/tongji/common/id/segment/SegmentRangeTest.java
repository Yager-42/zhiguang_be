package com.tongji.common.id.segment;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SegmentRangeTest {

    @Test
    void storesInclusiveBounds() {
        SegmentRange range = new SegmentRange(1001L, 2000L);

        assertThat(range.getStartInclusive()).isEqualTo(1001L);
        assertThat(range.getEndInclusive()).isEqualTo(2000L);
    }

    @Test
    void rejectsStartGreaterThanEnd() {
        assertThatThrownBy(() -> new SegmentRange(2L, 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("startInclusive must be less than or equal to endInclusive");
    }
}
