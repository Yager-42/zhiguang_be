package com.tongji.relation.command;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.common.resilience.GuardResult;
import com.tongji.common.resilience.ResilienceGuard;
import com.tongji.relation.manager.RelationWriteResult;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 关注命令服务（生产端）。
 *
 * <p>职责：令牌桶限流（Redis 故障时 fail-open 放行）→ 同步投递 Kafka（acks=all + 3s
 * 等待，投递失败抛异常返回 500）。事实判断与写入全部由消费端
 * {@link com.tongji.relation.manager.RelationManagerImpl} 的事务链路完成，请求线程不碰 MySQL。</p>
 */
@Service
public class RelationCommandService {

    private static final Logger log = LoggerFactory.getLogger(RelationCommandService.class);

    private static final String TOKEN_BUCKET_LUA = """
            
            local key = KEYS[1]
            local capacity = tonumber(ARGV[1])
            local rate = tonumber(ARGV[2])
            local now = redis.call('TIME')[1]
            local last = redis.call('HGET', key, 'last')
            local tokens = redis.call('HGET', key, 'tokens')
            if not last then last = now; tokens = capacity end
            local elapsed = tonumber(now) - tonumber(last)
            local add = elapsed * rate
            tokens = math.min(capacity, tonumber(tokens) + add)
            if tokens < 1 then redis.call('HSET', key, 'last', now); redis.call('HSET', key, 'tokens', tokens); return 0 end
            tokens = tokens - 1
            redis.call('HSET', key, 'last', now)
            redis.call('HSET', key, 'tokens', tokens)
            redis.call('PEXPIRE', key, 60000)
            return 1
            """;

    private final StringRedisTemplate redisTemplate;
    private final ResilienceGuard resilienceGuard;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final DefaultRedisScript<Long> tokenScript;

    public RelationCommandService(StringRedisTemplate redisTemplate,
                                  ResilienceGuard resilienceGuard,
                                  KafkaTemplate<String, String> kafkaTemplate,
                                  ObjectMapper objectMapper,
                                  MeterRegistry meterRegistry) {
        this.redisTemplate = redisTemplate;
        this.resilienceGuard = resilienceGuard;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
        this.tokenScript = new DefaultRedisScript<>();
        this.tokenScript.setResultType(Long.class);
        this.tokenScript.setScriptText(TOKEN_BUCKET_LUA);
    }

    /**
     * 受理关注命令：限流 → 投递 Kafka。事实判断（幂等/冲突）全部由消费端事务链路完成，
     * 请求线程不碰 MySQL。
     * @return changed(true)=已受理；failed()=被限流
     */
    public RelationWriteResult follow(long fromUserId, long toUserId) {
        if (!rateLimit(fromUserId)) {
            return RelationWriteResult.failed();
        }
        send(fromUserId, toUserId, true);
        return RelationWriteResult.changed(true);
    }

    /**
     * 受理取关命令：直接投递（重复取关由消费端幂等门控兜底）。
     */
    public RelationWriteResult unfollow(long fromUserId, long toUserId) {
        send(fromUserId, toUserId, false);
        return RelationWriteResult.changed(true);
    }

    private boolean rateLimit(long fromUserId) {
        long startedAt = System.nanoTime();
        try {
            Long allowed = redisTemplate.execute(
                    tokenScript, List.of("rl:follow:" + fromUserId), "100", "1");
            return Long.valueOf(1L).equals(allowed);
        } catch (Exception ex) {
            // 限流依赖 Redis 故障时 fail-open：关注写不可因限流系统故障而阻塞
            log.warn("follow rate-limit unavailable, fail-open for userId={}", fromUserId, ex);
            return true;
        } finally {
            recordStageDuration("redis-rate-limit", startedAt);
        }
    }

    private void send(long fromUserId, long toUserId, boolean follow) {
        String payload;
        try {
            payload = objectMapper.writeValueAsString(new FollowCommandEvent(fromUserId, toUserId, follow));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("cannot encode follow command", ex);
        }
        long startedAt = System.nanoTime();
        try {
            GuardResult<Void> result = resilienceGuard.execute(
                    "relation:command-delivery",
                    () -> {
                        // 等待 broker 确认后才报告受理成功，避免返回成功但命令实际丢失。
                        kafkaTemplate.send(FollowCommandTopics.COMMAND, String.valueOf(fromUserId), payload)
                                .get(10, TimeUnit.SECONDS);
                        return null;
                    },
                    () -> null,
                    this::isSystemFailure
            );
            if (result.fallbackApplied()) {
                throw new IllegalStateException("relation command delivery degraded");
            }
        } finally {
            recordStageDuration("kafka-ack", startedAt);
        }
    }

    private void recordStageDuration(String stage, long startedAt) {
        meterRegistry.timer("relation.command.stage", "stage", stage)
                .record(System.nanoTime() - startedAt, TimeUnit.NANOSECONDS);
    }

    private boolean isSystemFailure(Throwable t) {
        return !(t instanceof org.apache.kafka.common.errors.RetriableException);
    }
}
