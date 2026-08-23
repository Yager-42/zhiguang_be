package com.tongji.favorite.migration;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FavoriteBitmapBackfillRunnerTest {

    @Test
    void decodesRedisBitOrderAndShardOffset() {
        byte[] bitmap = {(byte) 0x80, 0x01};

        assertThat(FavoriteBitmapBackfillRunner.decodeUserIds(bitmap, 2L))
                .containsExactly(65_536L, 65_551L);
    }
}
