package com.tongji.counter.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.tongji.common.singleflight.DistributedSingleFlightService;
import com.tongji.counter.schema.UserCounterKeys;
import com.tongji.counter.service.UserCounterReader;
import com.tongji.counter.service.UserCounterService;
import com.tongji.counter.service.UserCounters;
import com.tongji.relation.mapper.RelationMapper;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

/**
 * Owns user-counter SDS reads, sampled verification and coordinated rebuilds.
 */
@Service
public class UserCounterReaderImpl implements UserCounterReader {
    private static final int FIELD_SIZE = 4;
    private static final int FIELD_COUNT = 5;
    private static final int MIN_READABLE_BYTES = FIELD_SIZE * FIELD_COUNT;
    private static final Duration VERIFY_INTERVAL = Duration.ofSeconds(300);
    private static final String SINGLE_FLIGHT_STAGE = "user-counter";
    private static final TypeReference<UserCounters> RESULT_TYPE = new TypeReference<>() {
    };

    private final StringRedisTemplate redis;
    private final UserCounterService userCounterService;
    private final RelationMapper relationMapper;
    private final DistributedSingleFlightService singleFlightService;

    public UserCounterReaderImpl(StringRedisTemplate redis,
                                 UserCounterService userCounterService,
                                 RelationMapper relationMapper,
                                 DistributedSingleFlightService singleFlightService) {
        this.redis = redis;
        this.userCounterService = userCounterService;
        this.relationMapper = relationMapper;
        this.singleFlightService = singleFlightService;
    }

    @Override
    public Optional<UserCounters> find(long userId) {
        Snapshot snapshot = readSnapshot(userId);
        return snapshot == null ? Optional.empty() : Optional.of(snapshot.counters());
    }

    @Override
    public UserCounters getVerified(long userId) {
        Snapshot snapshot = readSnapshot(userId);
        if (snapshot == null) {
            return rebuildThroughSingleFlight(userId);
        }

        Boolean verify = redis.opsForValue().setIfAbsent(verificationKey(userId), "1", VERIFY_INTERVAL);
        if (!Boolean.TRUE.equals(verify)) {
            return snapshot.counters();
        }

        long followings = 0L;
        long followers = 0L;
        try {
            followings = relationMapper.countFollowingActive(userId);
        } catch (Exception ignored) {
        }
        try {
            followers = relationMapper.countFollowerActive(userId);
        } catch (Exception ignored) {
        }

        UserCounters counters = snapshot.counters();
        if (snapshot.segmentCount() != FIELD_COUNT
                || counters.followings() != followings
                || counters.followers() != followers) {
            return rebuildThroughSingleFlight(userId);
        }
        return counters;
    }

    private UserCounters rebuildThroughSingleFlight(long userId) {
        return singleFlightService.execute(
                SINGLE_FLIGHT_STAGE,
                String.valueOf(userId),
                RESULT_TYPE,
                () -> rebuildAndRead(userId)
        );
    }

    private UserCounters rebuildAndRead(long userId) {
        userCounterService.rebuildAllCounters(userId);
        return find(userId).orElseGet(UserCounters::zero);
    }

    private Snapshot readSnapshot(long userId) {
        byte[] raw = redis.execute((RedisCallback<byte[]>) connection -> connection.stringCommands().get(
                UserCounterKeys.sdsKey(userId).getBytes(StandardCharsets.UTF_8)
        ));
        if (raw == null || raw.length < MIN_READABLE_BYTES) {
            return null;
        }
        return new Snapshot(decode(raw), raw.length / FIELD_SIZE);
    }

    private static UserCounters decode(byte[] raw) {
        return new UserCounters(
                read32be(raw, 0),
                read32be(raw, FIELD_SIZE),
                read32be(raw, FIELD_SIZE * 2),
                read32be(raw, FIELD_SIZE * 3),
                read32be(raw, FIELD_SIZE * 4)
        );
    }

    private static long read32be(byte[] raw, int offset) {
        long value = 0L;
        for (int i = 0; i < FIELD_SIZE; i++) {
            value = (value << 8) | (raw[offset + i] & 0xFFL);
        }
        return value;
    }

    private static String verificationKey(long userId) {
        return "ucnt:chk:" + userId;
    }

    private record Snapshot(UserCounters counters, int segmentCount) {
    }
}
