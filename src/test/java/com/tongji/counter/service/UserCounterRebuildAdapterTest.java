package com.tongji.counter.service;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class UserCounterRebuildAdapterTest {

    @Test
    void readsFiveSegmentUserCounterSds() {
        UserCounterRebuildAdapter adapter = new UserCounterRebuildAdapter(mock(UserCounterService.class), null);
        byte[] raw = new byte[20];
        write32be(raw, 0, 11L);
        write32be(raw, 4, 22L);
        write32be(raw, 8, 33L);
        write32be(raw, 12, 44L);
        write32be(raw, 16, 55L);

        Map<String, Long> result = adapter.readFromRaw(raw);

        assertThat(result)
                .containsEntry("followings", 11L)
                .containsEntry("followers", 22L)
                .containsEntry("posts", 33L)
                .containsEntry("likedPosts", 44L)
                .containsEntry("favedPosts", 55L);
    }

    private static void write32be(byte[] raw, int offset, long value) {
        raw[offset] = (byte) ((value >>> 24) & 0xFF);
        raw[offset + 1] = (byte) ((value >>> 16) & 0xFF);
        raw[offset + 2] = (byte) ((value >>> 8) & 0xFF);
        raw[offset + 3] = (byte) (value & 0xFF);
    }
}
