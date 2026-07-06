package com.tongji.common.singleflight;

import com.tongji.common.singleflight.model.SingleFlightStatus;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisSingleFlightNotificationServiceTest {

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void publishAddsStreamEventAndSetsTtl() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(DefaultRedisScript.class), anyList(), any(Object[].class))).thenReturn(1L);
        RedisSingleFlightNotificationService service = new RedisSingleFlightNotificationService(redis);

        service.publish("counter-sds:knowpost:1", "owner_succeeded",
                SingleFlightStatus.SUCCEEDED, 3L, null, false, 1234L);

        verify(redis).execute(
                any(DefaultRedisScript.class),
                eq(java.util.Collections.singletonList("zg:singleflight:stream:counter-sds:knowpost:1")),
                eq("owner_succeeded"),
                eq("SUCCEEDED"),
                eq("3"),
                eq(""),
                eq("0"),
                any(String.class),
                eq("1234")
        );
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void currentEventOffsetReadsLastStreamRecord() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        StreamOperations<String, Object, Object> streamOperations = mock(StreamOperations.class);
        when(redis.opsForStream()).thenReturn(streamOperations);
        when(streamOperations.reverseRange(anyString(), any(), any()))
                .thenReturn(List.of(org.springframework.data.redis.connection.stream.MapRecord
                        .create("zg:singleflight:stream:counter-sds:knowpost:1",
                                Map.<Object, Object>of("event", "owner_succeeded"))
                        .withId(org.springframework.data.redis.connection.stream.RecordId.of("1-0"))));
        RedisSingleFlightNotificationService service = new RedisSingleFlightNotificationService(redis);

        String offset = service.currentEventOffset("counter-sds:knowpost:1");

        org.assertj.core.api.Assertions.assertThat(offset).isEqualTo("1-0");
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void currentEventOffsetUsesZeroWhenStreamIsEmpty() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        StreamOperations<String, Object, Object> streamOperations = mock(StreamOperations.class);
        when(redis.opsForStream()).thenReturn(streamOperations);
        when(streamOperations.reverseRange(anyString(), any(), any())).thenReturn(List.of());
        RedisSingleFlightNotificationService service = new RedisSingleFlightNotificationService(redis);

        String offset = service.currentEventOffset("counter-sds:knowpost:1");

        org.assertj.core.api.Assertions.assertThat(offset).isEqualTo("0-0");
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void waitForTerminalEventReadsRedisStreamAfterCapturedOffsetWithBlock() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        StreamOperations<String, Object, Object> streamOperations = mock(StreamOperations.class);
        when(redis.opsForStream()).thenReturn(streamOperations);
        RedisSingleFlightNotificationService service = new RedisSingleFlightNotificationService(redis);

        service.waitForTerminalEvent("counter-sds:knowpost:1", "1-0", 25L);

        verify(streamOperations).read(any(StreamReadOptions.class), any(StreamOffset.class));
    }
}
