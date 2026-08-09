package com.tongji.promotion.bprime.redis;

import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Mirrors the Redis guards that precede {@code BID_NOT_HIGHER} in the authoritative Lua script.
 *
 * <p>The status and command-dedupe reads share one Redis pipeline round trip. An unavailable or malformed result is
 * deliberately inconclusive so the caller falls through to the authoritative command path.</p>
 */
@Repository
public class PromotionBidFastPathPrecheckRepository {

    private static final byte[] STATUS_FIELD = "status".getBytes(StandardCharsets.UTF_8);

    private final StringRedisTemplate redisTemplate;

    public PromotionBidFastPathPrecheckRepository(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * Executes all status and dedupe guards in one Redis pipeline while preserving request order.
     *
     * @param checks fast-reject candidates in caller order
     * @return one guard result per candidate
     */
    public List<Result> checkBatch(List<Check> checks) {
        if (checks.isEmpty()) {
            return List.of();
        }
        Map<Long, Integer> statusPositions = new LinkedHashMap<>();
        for (Check check : checks) {
            statusPositions.computeIfAbsent(check.auctionWindowId(), ignored -> statusPositions.size());
        }
        try {
            List<Object> values = redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                for (Long auctionWindowId : statusPositions.keySet()) {
                    connection.hashCommands().hGet(stateKey(auctionWindowId), STATUS_FIELD);
                }
                for (Check check : checks) {
                    connection.hashCommands().hExists(commandsKey(check.auctionWindowId()),
                            commandHashField(check.commandId()));
                }
                return null;
            });
            if (values == null || values.size() != statusPositions.size() + checks.size()) {
                return unavailableResults(checks.size());
            }
            Map<Long, String> statuses = new LinkedHashMap<>();
            for (Map.Entry<Long, Integer> entry : statusPositions.entrySet()) {
                statuses.put(entry.getKey(), stringValue(values.get(entry.getValue())));
            }
            List<Result> results = new ArrayList<>(checks.size());
            int duplicateOffset = statusPositions.size();
            for (int index = 0; index < checks.size(); index++) {
                Check check = checks.get(index);
                String status = statuses.get(check.auctionWindowId());
                Boolean duplicate = booleanValue(values.get(duplicateOffset + index));
                results.add(status == null || duplicate == null
                        ? Result.unavailable()
                        : new Result(true, "OPEN".equals(status), duplicate));
            }
            return results;
        } catch (RuntimeException exception) {
            return unavailableResults(checks.size());
        }
    }

    private byte[] stateKey(long auctionWindowId) {
        return (PromotionAuctionRedisKeys.prefix(auctionWindowId) + ":state").getBytes(StandardCharsets.UTF_8);
    }

    private byte[] commandsKey(long auctionWindowId) {
        return (PromotionAuctionRedisKeys.prefix(auctionWindowId) + ":commands").getBytes(StandardCharsets.UTF_8);
    }

    private byte[] commandHashField(String commandId) {
        return (commandId + ":hash").getBytes(StandardCharsets.UTF_8);
    }

    private List<Result> unavailableResults(int size) {
        List<Result> results = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            results.add(Result.unavailable());
        }
        return results;
    }

    private String stringValue(Object value) {
        if (value instanceof byte[] bytes) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        return value instanceof String text ? text : null;
    }

    private Boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.longValue() != 0L;
        }
        if (value instanceof byte[] bytes) {
            return "1".equals(new String(bytes, StandardCharsets.UTF_8));
        }
        return null;
    }

    public record Check(long auctionWindowId, String commandId) {
    }

    public record Result(boolean available, boolean windowOpen, boolean duplicate) {

        public static Result unavailable() {
            return new Result(false, false, false);
        }

        public boolean allowsFastReject() {
            return available && windowOpen && !duplicate;
        }
    }
}
