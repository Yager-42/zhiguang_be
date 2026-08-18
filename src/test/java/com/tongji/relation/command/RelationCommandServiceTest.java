package com.tongji.relation.command;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.common.resilience.GuardResult;
import com.tongji.common.resilience.GuardedOperation;
import com.tongji.common.resilience.ResilienceGuard;
import com.tongji.relation.manager.RelationWriteResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RelationCommandServiceTest {

    private static final long FROM_USER_ID = 101L;
    private static final long TO_USER_ID = 202L;

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private RelationCommandService service;

    @BeforeEach
    void setUp() {
        ResilienceGuard resilienceGuard = new ResilienceGuard() {
            @Override
            public <T> GuardResult<T> execute(String resourceName,
                                              GuardedOperation<T> operation,
                                              Supplier<T> fallbackSupplier,
                                              Predicate<Throwable> systemFailureClassifier) {
                try {
                    return GuardResult.success(operation.execute());
                } catch (Exception exception) {
                    return GuardResult.fallback(fallbackSupplier.get(), exception);
                }
            }
        };
        service = new RelationCommandService(redisTemplate, resilienceGuard, kafkaTemplate, objectMapper);
    }


    @Test
    void followRejectsWhenRateLimited() {
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), any(), any())).thenReturn(0L);

        RelationWriteResult result = service.follow(FROM_USER_ID, TO_USER_ID);

        assertThat(result.success()).isFalse();
        verify(kafkaTemplate, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    void followDeliversCommandWhenRateLimitPasses() {
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), any(), any())).thenReturn(1L);
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));

        RelationWriteResult result = service.follow(FROM_USER_ID, TO_USER_ID);

        assertThat(result.success()).isTrue();
        assertThat(result.stateChanged()).isTrue();
        verify(kafkaTemplate).send(eq(FollowCommandTopics.COMMAND), eq(String.valueOf(FROM_USER_ID)),
                contains("\"fromUserId\":" + FROM_USER_ID));
        verify(kafkaTemplate).send(eq(FollowCommandTopics.COMMAND), eq(String.valueOf(FROM_USER_ID)),
                contains("\"follow\":true"));
    }

    @Test
    void followFailsClosedWhenDeliveryFails() {
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), any(), any())).thenReturn(1L);
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("broker down"));

        assertThatThrownBy(() -> service.follow(FROM_USER_ID, TO_USER_ID))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void redisRateLimitFailureFailsOpen() {
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), any(), any()))
                .thenThrow(new RuntimeException("redis down"));
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));

        RelationWriteResult result = service.follow(FROM_USER_ID, TO_USER_ID);

        assertThat(result.success()).isTrue();
        verify(kafkaTemplate).send(anyString(), anyString(), anyString());
    }

    @Test
    void unfollowDeliversDirectlyWithoutShortCircuitOrRateLimit() {
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));

        RelationWriteResult result = service.unfollow(FROM_USER_ID, TO_USER_ID);

        assertThat(result.success()).isTrue();
        verify(kafkaTemplate).send(eq(FollowCommandTopics.COMMAND), eq(String.valueOf(FROM_USER_ID)),
                contains("\"follow\":false"));
    }
}