package com.tongji.common.singleflight;

import com.tongji.common.singleflight.model.SingleFlightAcquireResult;
import com.tongji.common.singleflight.model.SingleFlightAction;
import com.tongji.common.singleflight.model.SingleFlightErrorType;
import com.tongji.common.singleflight.model.SingleFlightMetaSnapshot;
import com.tongji.common.singleflight.model.SingleFlightPolicy;
import com.tongji.common.singleflight.model.SingleFlightStatus;
import com.tongji.common.singleflight.model.SingleFlightStoredResult;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

@Repository
public class RedisSingleFlightCoordinatorRepository implements SingleFlightCoordinatorRepository {

    private static final String META_KEY_PREFIX = "zg:singleflight:meta:";
    private static final String RESULT_KEY_PREFIX = "zg:singleflight:result:";
    private static final String OWNER_SEQ_KEY = "zg:singleflight:owner-seq";

    private static final String ACQUIRE_OR_JOIN_SCRIPT_TEXT =
            "local status = redis.call('HGET', KEYS[1], 'status') "
                    + "if not status then "
                    + "  local token = redis.call('INCR', KEYS[2]) "
                    + "  redis.call('HSET', KEYS[1], 'status', 'PENDING', 'stage', ARGV[1], 'ownerId', ARGV[2], 'ownerToken', token, 'requestKey', ARGV[3], 'updatedAt', ARGV[4], 'heartbeatAt', ARGV[4], 'expireAt', ARGV[5], 'retryable', '0') "
                    + "  redis.call('PEXPIRE', KEYS[1], tonumber(ARGV[6])) "
                    + "  return 'OWNER_NEW|' .. token "
                    + "end "
                    + "if status == 'SUCCEEDED' then return 'REPLAY_SUCCESS|' .. status end "
                    + "if status == 'FAILED' then "
                    + "  local retryable = redis.call('HGET', KEYS[1], 'retryable') or '0' "
                    + "  if retryable == '1' then "
                    + "    local token = redis.call('INCR', KEYS[2]) "
                    + "    redis.call('HSET', KEYS[1], 'status', 'PENDING', 'stage', ARGV[1], 'ownerId', ARGV[2], 'ownerToken', token, 'requestKey', ARGV[3], 'updatedAt', ARGV[4], 'heartbeatAt', ARGV[4], 'expireAt', ARGV[5], 'errorType', '', 'errorCode', '', 'retryable', '0') "
                    + "    redis.call('PEXPIRE', KEYS[1], tonumber(ARGV[6])) "
                    + "    return 'OWNER_TAKEOVER|' .. token "
                    + "  end "
                    + "  local errorType = redis.call('HGET', KEYS[1], 'errorType') or '' "
                    + "  local errorCode = redis.call('HGET', KEYS[1], 'errorCode') or '' "
                    + "  return 'REPLAY_FAILURE|0|' .. errorType .. '|' .. errorCode "
                    + "end "
                    + "local heartbeatAt = tonumber(redis.call('HGET', KEYS[1], 'heartbeatAt') or '0') "
                    + "if heartbeatAt > 0 and (tonumber(ARGV[4]) - heartbeatAt) <= tonumber(ARGV[7]) then "
                    + "  redis.call('HINCRBY', KEYS[1], 'followerCount', 1) "
                    + "  return 'FOLLOWER_WAIT|' .. (redis.call('HGET', KEYS[1], 'ownerToken') or '') "
                    + "end "
                    + "local token = redis.call('INCR', KEYS[2]) "
                    + "redis.call('HSET', KEYS[1], 'status', 'PENDING', 'stage', ARGV[1], 'ownerId', ARGV[2], 'ownerToken', token, 'requestKey', ARGV[3], 'updatedAt', ARGV[4], 'heartbeatAt', ARGV[4], 'expireAt', ARGV[5], 'errorType', '', 'errorCode', '', 'retryable', '0') "
                    + "redis.call('PEXPIRE', KEYS[1], tonumber(ARGV[6])) "
                    + "return 'OWNER_TAKEOVER|' .. token";

    private static final String MARK_RUNNING_SCRIPT_TEXT =
            "if redis.call('HGET', KEYS[1], 'ownerId') ~= ARGV[1] then return 0 end "
                    + "if redis.call('HGET', KEYS[1], 'ownerToken') ~= ARGV[2] then return 0 end "
                    + "local status = redis.call('HGET', KEYS[1], 'status') "
                    + "if status ~= 'PENDING' and status ~= 'RUNNING' then return 0 end "
                    + "redis.call('HSET', KEYS[1], 'status', 'RUNNING', 'updatedAt', ARGV[3], 'heartbeatAt', ARGV[3], 'expireAt', ARGV[4]) "
                    + "redis.call('PEXPIRE', KEYS[1], tonumber(ARGV[5])) "
                    + "return 1";

    private static final String HEARTBEAT_SCRIPT_TEXT =
            "if redis.call('HGET', KEYS[1], 'ownerId') ~= ARGV[1] then return 0 end "
                    + "if redis.call('HGET', KEYS[1], 'ownerToken') ~= ARGV[2] then return 0 end "
                    + "if redis.call('HGET', KEYS[1], 'status') ~= 'RUNNING' then return 0 end "
                    + "redis.call('HSET', KEYS[1], 'updatedAt', ARGV[3], 'heartbeatAt', ARGV[3], 'expireAt', ARGV[4]) "
                    + "redis.call('PEXPIRE', KEYS[1], tonumber(ARGV[5])) "
                    + "return 1";

    private static final String STORE_RESULT_SCRIPT_TEXT =
            "if redis.call('HGET', KEYS[1], 'ownerId') ~= ARGV[1] then return 0 end "
                    + "if redis.call('HGET', KEYS[1], 'ownerToken') ~= ARGV[2] then return 0 end "
                    + "local status = redis.call('HGET', KEYS[1], 'status') "
                    + "if status ~= 'PENDING' and status ~= 'RUNNING' then return 0 end "
                    + "redis.call('HSET', KEYS[2], 'payload', ARGV[3], 'codec', ARGV[4], 'compressed', ARGV[5], 'rawSize', ARGV[6], 'storedSize', ARGV[7], 'checksum', ARGV[8], 'contentType', ARGV[9], 'finishedAt', ARGV[10], 'ownerToken', ARGV[2]) "
                    + "redis.call('PEXPIRE', KEYS[2], tonumber(ARGV[11])) "
                    + "return 1";

    private static final String FINISH_SUCCESS_SCRIPT_TEXT =
            "if redis.call('HGET', KEYS[1], 'ownerId') ~= ARGV[1] then return 0 end "
                    + "if redis.call('HGET', KEYS[1], 'ownerToken') ~= ARGV[2] then return 0 end "
                    + "local status = redis.call('HGET', KEYS[1], 'status') "
                    + "if status ~= 'PENDING' and status ~= 'RUNNING' then return 0 end "
                    + "redis.call('HSET', KEYS[1], 'status', 'SUCCEEDED', 'updatedAt', ARGV[3], 'expireAt', ARGV[4], 'resultRef', KEYS[2], 'errorType', '', 'errorCode', '', 'retryable', '0') "
                    + "redis.call('PEXPIRE', KEYS[1], tonumber(ARGV[5])) "
                    + "return 1";

    private static final String FINISH_FAILURE_SCRIPT_TEXT =
            "if redis.call('HGET', KEYS[1], 'ownerId') ~= ARGV[1] then return 0 end "
                    + "if redis.call('HGET', KEYS[1], 'ownerToken') ~= ARGV[2] then return 0 end "
                    + "local status = redis.call('HGET', KEYS[1], 'status') "
                    + "if status ~= 'PENDING' and status ~= 'RUNNING' then return 0 end "
                    + "redis.call('HSET', KEYS[1], 'status', 'FAILED', 'updatedAt', ARGV[3], 'expireAt', ARGV[4], 'errorType', ARGV[5], 'errorCode', ARGV[6], 'retryable', ARGV[7]) "
                    + "redis.call('PEXPIRE', KEYS[1], tonumber(ARGV[8])) "
                    + "return 1";

    private static final DefaultRedisScript<String> ACQUIRE_OR_JOIN_SCRIPT = stringScript(ACQUIRE_OR_JOIN_SCRIPT_TEXT);
    private static final DefaultRedisScript<Long> MARK_RUNNING_SCRIPT = longScript(MARK_RUNNING_SCRIPT_TEXT);
    private static final DefaultRedisScript<Long> HEARTBEAT_SCRIPT = longScript(HEARTBEAT_SCRIPT_TEXT);
    private static final DefaultRedisScript<Long> STORE_RESULT_SCRIPT = longScript(STORE_RESULT_SCRIPT_TEXT);
    private static final DefaultRedisScript<Long> FINISH_SUCCESS_SCRIPT = longScript(FINISH_SUCCESS_SCRIPT_TEXT);
    private static final DefaultRedisScript<Long> FINISH_FAILURE_SCRIPT = longScript(FINISH_FAILURE_SCRIPT_TEXT);

    private final StringRedisTemplate redis;

    public RedisSingleFlightCoordinatorRepository(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public SingleFlightAcquireResult acquireOrJoin(String stage, String requestKey, String ownerId, SingleFlightPolicy policy) {
        long now = System.currentTimeMillis();
        String raw = redis.execute(
                ACQUIRE_OR_JOIN_SCRIPT,
                List.of(metaKey(requestKey), OWNER_SEQ_KEY),
                stage,
                ownerId,
                requestKey,
                String.valueOf(now),
                String.valueOf(now + policy.runningTtlMillis()),
                String.valueOf(policy.runningTtlMillis()),
                String.valueOf(policy.takeoverDetectMillis())
        );
        return parseAcquireResult(raw);
    }

    @Override
    public boolean markRunning(String requestKey, String ownerId, Long ownerToken, long runningTtlMillis) {
        long now = System.currentTimeMillis();
        Long result = redis.execute(MARK_RUNNING_SCRIPT, Collections.singletonList(metaKey(requestKey)),
                ownerId, String.valueOf(ownerToken), String.valueOf(now),
                String.valueOf(now + runningTtlMillis), String.valueOf(runningTtlMillis));
        return Long.valueOf(1L).equals(result);
    }

    @Override
    public boolean heartbeat(String requestKey, String ownerId, Long ownerToken, long runningTtlMillis) {
        long now = System.currentTimeMillis();
        Long result = redis.execute(HEARTBEAT_SCRIPT, Collections.singletonList(metaKey(requestKey)),
                ownerId, String.valueOf(ownerToken), String.valueOf(now),
                String.valueOf(now + runningTtlMillis), String.valueOf(runningTtlMillis));
        return Long.valueOf(1L).equals(result);
    }

    @Override
    public boolean storeResult(String requestKey, String ownerId, Long ownerToken, SingleFlightStoredResult storedResult, long resultTtlMillis) {
        if (storedResult == null) {
            return false;
        }
        Long result = redis.execute(STORE_RESULT_SCRIPT, List.of(metaKey(requestKey), resultKey(requestKey)),
                ownerId,
                String.valueOf(ownerToken),
                value(storedResult.payload()),
                value(storedResult.codec()),
                storedResult.compressed() ? "1" : "0",
                String.valueOf(storedResult.rawSize()),
                String.valueOf(storedResult.storedSize()),
                value(storedResult.checksum()),
                value(storedResult.contentType()),
                String.valueOf(storedResult.finishedAt()),
                String.valueOf(resultTtlMillis));
        return Long.valueOf(1L).equals(result);
    }

    @Override
    public boolean finishSuccess(String requestKey, String ownerId, Long ownerToken, long resultTtlMillis) {
        long now = System.currentTimeMillis();
        Long result = redis.execute(FINISH_SUCCESS_SCRIPT, List.of(metaKey(requestKey), resultKey(requestKey)),
                ownerId, String.valueOf(ownerToken), String.valueOf(now),
                String.valueOf(now + resultTtlMillis), String.valueOf(resultTtlMillis));
        return Long.valueOf(1L).equals(result);
    }

    @Override
    public boolean finishFailure(String requestKey, String ownerId, Long ownerToken, SingleFlightErrorType errorType,
                                 String errorCode, boolean retryable, long ttlMillis) {
        long now = System.currentTimeMillis();
        Long result = redis.execute(FINISH_FAILURE_SCRIPT, Collections.singletonList(metaKey(requestKey)),
                ownerId, String.valueOf(ownerToken), String.valueOf(now), String.valueOf(now + ttlMillis),
                errorType == null ? SingleFlightErrorType.UNEXPECTED.name() : errorType.name(),
                value(errorCode == null ? "FLIGHT_FAILED" : errorCode), retryable ? "1" : "0", String.valueOf(ttlMillis));
        return Long.valueOf(1L).equals(result);
    }

    @Override
    public SingleFlightStoredResult getStoredResult(String requestKey) {
        Map<Object, Object> raw = redis.opsForHash().entries(resultKey(requestKey));
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        return new SingleFlightStoredResult(
                asString(raw.get("payload")),
                asString(raw.get("codec")),
                asBoolean(raw.get("compressed")),
                asInt(raw.get("rawSize")),
                asInt(raw.get("storedSize")),
                asString(raw.get("checksum")),
                asString(raw.get("contentType")),
                asLong(raw.get("finishedAt")),
                asLongNullable(raw.get("ownerToken"))
        );
    }

    @Override
    public SingleFlightMetaSnapshot getMeta(String requestKey) {
        Map<Object, Object> raw = redis.opsForHash().entries(metaKey(requestKey));
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        return new SingleFlightMetaSnapshot(
                asString(raw.get("stage")),
                SingleFlightStatus.from(asString(raw.get("status"))),
                asString(raw.get("ownerId")),
                asLongNullable(raw.get("ownerToken")),
                asLongNullable(raw.get("heartbeatAt")),
                asBoolean(raw.get("retryable")),
                SingleFlightErrorType.from(asString(raw.get("errorType"))),
                asString(raw.get("errorCode"))
        );
    }

    private SingleFlightAcquireResult parseAcquireResult(String raw) {
        if (raw == null || raw.isBlank()) {
            return SingleFlightAcquireResult.followerWait(null);
        }
        String[] parts = raw.split("\\|", -1);
        SingleFlightAction action = SingleFlightAction.from(parts[0]);
        if (action == SingleFlightAction.OWNER_NEW) {
            return SingleFlightAcquireResult.ownerNew(parseLong(parts, 1));
        }
        if (action == SingleFlightAction.OWNER_TAKEOVER) {
            return SingleFlightAcquireResult.ownerTakeover(parseLong(parts, 1));
        }
        if (action == SingleFlightAction.FOLLOWER_WAIT) {
            return SingleFlightAcquireResult.followerWait(parseLong(parts, 1));
        }
        if (action == SingleFlightAction.REPLAY_SUCCESS) {
            return SingleFlightAcquireResult.replaySuccess();
        }
        return SingleFlightAcquireResult.replayFailure(
                "1".equals(valueAt(parts, 1)) || "true".equalsIgnoreCase(valueAt(parts, 1)),
                SingleFlightErrorType.from(valueAt(parts, 2)),
                valueAt(parts, 3)
        );
    }

    private static DefaultRedisScript<String> stringScript(String scriptText) {
        DefaultRedisScript<String> script = new DefaultRedisScript<>();
        script.setScriptText(scriptText);
        script.setResultType(String.class);
        return script;
    }

    private static DefaultRedisScript<Long> longScript(String scriptText) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptText(scriptText);
        script.setResultType(Long.class);
        return script;
    }

    private String metaKey(String requestKey) {
        return META_KEY_PREFIX + cacheKey(requestKey);
    }

    private String resultKey(String requestKey) {
        return RESULT_KEY_PREFIX + cacheKey(requestKey);
    }

    private String cacheKey(String requestKey) {
        return sha256Hex(requestKey) + ":" + requestKey.replaceAll("[^A-Za-z0-9:_-]", "_");
    }

    private String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 not available", exception);
        }
    }

    private String value(String value) {
        return value == null ? "" : value;
    }

    private String valueAt(String[] values, int index) {
        return values != null && index >= 0 && index < values.length ? values[index] : null;
    }

    private Long parseLong(String[] values, int index) {
        Long parsed = asLongNullable(valueAt(values, index));
        return parsed == null ? 0L : parsed;
    }

    private String asString(Object value) {
        return value == null ? null : value.toString();
    }

    private boolean asBoolean(Object value) {
        String text = asString(value);
        return "1".equals(text) || "true".equalsIgnoreCase(text);
    }

    private int asInt(Object value) {
        Long parsed = asLongNullable(value);
        return parsed == null ? 0 : parsed.intValue();
    }

    private long asLong(Object value) {
        Long parsed = asLongNullable(value);
        return parsed == null ? 0L : parsed;
    }

    private Long asLongNullable(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException exception) {
            return null;
        }
    }
}
