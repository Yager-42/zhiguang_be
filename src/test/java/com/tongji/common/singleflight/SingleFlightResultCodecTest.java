package com.tongji.common.singleflight;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.common.singleflight.model.SingleFlightPolicy;
import com.tongji.common.singleflight.model.SingleFlightStoredResult;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SingleFlightResultCodecTest {

    private final SingleFlightResultCodec codec = new SingleFlightResultCodec(new ObjectMapper().findAndRegisterModules());

    @Test
    void roundTripsTypedJsonResultWithChecksum() {
        Map<String, Long> value = new LinkedHashMap<>();
        value.put("like", 12L);
        value.put("fav", 4L);

        SingleFlightStoredResult stored = codec.serialize(value, 9L, policy(1024));
        Map<String, Long> decoded = codec.deserialize(stored, new TypeReference<>() {
        });

        assertThat(stored.contentType()).isEqualTo("application/json");
        assertThat(stored.ownerToken()).isEqualTo(9L);
        assertThat(decoded).containsEntry("like", 12L).containsEntry("fav", 4L);
    }

    @Test
    void detectsChecksumMismatch() {
        Map<String, Long> value = Map.of("like", 1L);
        SingleFlightStoredResult stored = codec.serialize(value, 1L, policy(1024));
        SingleFlightStoredResult tampered = new SingleFlightStoredResult(
                stored.payload(),
                stored.codec(),
                stored.compressed(),
                stored.rawSize(),
                stored.storedSize(),
                "bad-checksum",
                stored.contentType(),
                stored.finishedAt(),
                stored.ownerToken()
        );

        assertThatThrownBy(() -> codec.deserialize(tampered, new TypeReference<Map<String, Long>>() {
        })).isInstanceOf(IllegalStateException.class)
                .hasMessage("single-flight result checksum mismatch");
    }

    private SingleFlightPolicy policy(int compressionThresholdBytes) {
        return new SingleFlightPolicy(
                15000L,
                600000L,
                60000L,
                20000L,
                3000L,
                2000L,
                10000L,
                1000L,
                true,
                30000L,
                1000,
                compressionThresholdBytes,
                "gzip"
        );
    }
}
