package com.tongji.counter.service;

import com.tongji.counter.schema.UserCounterKeys;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.IntFunction;

@Service
public class UserCounterRebuildAdapter {

    private final UserCounterService userCounterService;
    private final StringRedisTemplate redis;

    public UserCounterRebuildAdapter(UserCounterService userCounterService, StringRedisTemplate redis) {
        this.userCounterService = userCounterService;
        this.redis = redis;
    }

    public Map<String, Long> rebuildAndRead(long userId) {
        userCounterService.rebuildAllCounters(userId);
        Map<String, Long> rebuilt = read(userId);
        return rebuilt == null ? zeroCounters() : rebuilt;
    }

    public Map<String, Long> read(long userId) {
        byte[] raw = redis.execute((RedisCallback<byte[]>)
                connection -> connection.stringCommands().get(UserCounterKeys.sdsKey(userId).getBytes(StandardCharsets.UTF_8)));
        if (raw == null || raw.length < 20) {
            return null;
        }
        return readFromRaw(raw);
    }

    public Map<String, Long> readFromRaw(byte[] raw) {
        final int segments = raw.length / 4;
        IntFunction<Long> read = index -> {
            if (index < 1 || index > segments) {
                return 0L;
            }
            int offset = (index - 1) * 4;
            long value = 0L;
            for (int i = 0; i < 4; i++) {
                value = (value << 8) | (raw[offset + i] & 0xFFL);
            }
            return value;
        };
        Map<String, Long> result = new LinkedHashMap<>();
        result.put("followings", read.apply(1));
        result.put("followers", read.apply(2));
        result.put("posts", read.apply(3));
        result.put("likedPosts", read.apply(4));
        result.put("favedPosts", read.apply(5));
        return result;
    }

    private Map<String, Long> zeroCounters() {
        Map<String, Long> result = new LinkedHashMap<>();
        result.put("followings", 0L);
        result.put("followers", 0L);
        result.put("posts", 0L);
        result.put("likedPosts", 0L);
        result.put("favedPosts", 0L);
        return result;
    }
}
