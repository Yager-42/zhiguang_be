package com.tongji.common.singleflight;

import com.tongji.common.singleflight.model.SingleFlightErrorType;
import com.tongji.common.singleflight.model.SingleFlightStatus;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.connection.Limit;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.domain.Range;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collections;
import java.util.List;

@Component
public class RedisSingleFlightNotificationService implements SingleFlightNotificationService {

    private static final String STREAM_KEY_PREFIX = "zg:singleflight:stream:";
    private static final String PUBLISH_SCRIPT_TEXT =
            "redis.call('XADD', KEYS[1], '*', "
                    + "'event', ARGV[1], "
                    + "'status', ARGV[2], "
                    + "'ownerToken', ARGV[3], "
                    + "'errorType', ARGV[4], "
                    + "'retryable', ARGV[5], "
                    + "'createdAt', ARGV[6]) "
                    + "redis.call('PEXPIRE', KEYS[1], tonumber(ARGV[7])) "
                    + "return 1";
    private static final DefaultRedisScript<Long> PUBLISH_SCRIPT = publishScript();

    private final StringRedisTemplate redis;

    public RedisSingleFlightNotificationService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public void publish(String requestKey, String event, SingleFlightStatus status, Long ownerToken,
                        SingleFlightErrorType errorType, boolean retryable, long streamTtlMillis) {
        String streamKey = streamKey(requestKey);
        Long result = redis.execute(
                PUBLISH_SCRIPT,
                Collections.singletonList(streamKey),
                value(event),
                status == null ? "" : status.name(),
                ownerToken == null ? "" : String.valueOf(ownerToken),
                errorType == null ? "" : errorType.name(),
                retryable ? "1" : "0",
                String.valueOf(System.currentTimeMillis()),
                String.valueOf(Math.max(1L, streamTtlMillis))
        );
        if (!Long.valueOf(1L).equals(result)) {
            throw new IllegalStateException("failed to publish distributed single-flight stream event");
        }
    }

    @Override
    public String currentEventOffset(String requestKey) {
        String streamKey = streamKey(requestKey);
        try {
            List<MapRecord<String, Object, Object>> records = redis.opsForStream().reverseRange(
                    streamKey,
                    Range.unbounded(),
                    Limit.limit().count(1)
            );
            if (records == null || records.isEmpty()) {
                return "0-0";
            }
            return records.get(0).getId().getValue();
        } catch (RuntimeException ignored) {
            return "0-0";
        }
    }

    @Override
    public void waitForTerminalEvent(String requestKey, String afterEventOffset, long blockTimeoutMillis) {
        if (blockTimeoutMillis <= 0) {
            return;
        }
        String streamKey = streamKey(requestKey);
        try {
            List<MapRecord<String, Object, Object>> ignored = redis.opsForStream().read(
                    StreamReadOptions.empty()
                            .block(Duration.ofMillis(blockTimeoutMillis))
                            .count(1),
                    StreamOffset.create(streamKey, ReadOffset.from(valueOrLatest(afterEventOffset)))
            );
        } catch (RuntimeException ignored) {
            // Followers still poll Redis metadata as a fallback.
        }
    }

    private String streamKey(String requestKey) {
        return STREAM_KEY_PREFIX + requestKey.replaceAll("[^A-Za-z0-9:_-]", "_");
    }

    private String value(String value) {
        return value == null ? "" : value;
    }

    private String valueOrLatest(String value) {
        return value == null || value.isBlank() ? "$" : value;
    }

    private static DefaultRedisScript<Long> publishScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptText(PUBLISH_SCRIPT_TEXT);
        script.setResultType(Long.class);
        return script;
    }

}
