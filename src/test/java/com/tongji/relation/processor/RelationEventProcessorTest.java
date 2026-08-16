package com.tongji.relation.processor;

import com.tongji.counter.service.UserCounterService;
import com.tongji.relation.event.RelationEvent;
import com.tongji.relation.mapper.RelationMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RelationEventProcessorTest {

    @Mock
    private RelationMapper mapper;
    @Mock
    private StringRedisTemplate redis;
    @Mock
    private UserCounterService userCounterService;
    @Mock
    private ValueOperations<String, String> valueOperations;
    @Mock
    private ZSetOperations<String, String> zSetOperations;

    private RelationEventProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new RelationEventProcessor(mapper, redis, userCounterService);
        lenient().when(redis.opsForValue()).thenReturn(valueOperations);
        lenient().when(redis.opsForZSet()).thenReturn(zSetOperations);
    }

    @Test
    void followCreatedAppliesMirrorZsetAndRebuildsCountsWhenCurrentlyFollowing() {
        when(mapper.existsFollowing(1L, 2L)).thenReturn(1);
        when(redis.hasKey("dedup:rel:77")).thenReturn(false);

        processor.process(new RelationEvent("FollowCreated", 1L, 2L, 9001L), 77L);

        verify(mapper).insertFollower(9001L, 2L, 1L, 1);
        verify(zSetOperations).add(eq("uf:flws:1"), eq("2"), any(Double.class));
        verify(zSetOperations).add(eq("uf:fans:2"), eq("1"), any(Double.class));
        verify(valueOperations).set("dedup:rel:77", "1", Duration.ofMinutes(10));
    }

    @Test
    void duplicateDeliveryWithSameOutboxIdIsSkippedEntirely() {
        when(redis.hasKey("dedup:rel:77")).thenReturn(true);

        processor.process(new RelationEvent("FollowCreated", 1L, 2L, 9001L), 77L);

        verify(mapper, never()).existsFollowing(any(Long.class), any(Long.class));
        verify(mapper, never()).insertFollower(any(), any(), any(), any());
        verify(userCounterService, never()).rebuildFollowCounters(any(Long.class), any(Long.class));
        verify(valueOperations, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void failedProcessingDoesNotMarkDedupSoRedeliveryCanRetry() {
        when(redis.hasKey("dedup:rel:77")).thenReturn(false);
        when(mapper.existsFollowing(1L, 2L)).thenThrow(new RuntimeException("db down"));

        assertThatThrownBy(() -> processor.process(new RelationEvent("FollowCreated", 1L, 2L, 9001L), 77L))
                .isInstanceOf(RuntimeException.class);

        verify(valueOperations, never()).set(anyString(), anyString(), any(Duration.class));
        verify(userCounterService, never()).rebuildFollowCounters(any(Long.class), any(Long.class));
    }

    @Test
    void outOfOrderCanceledIsSkippedWhenPairIsCurrentlyFollowing() {
        // 用户取消关注后又重新关注：先到的 FollowCanceled 晚于 FollowCreated 到达，
        // 以当前 following 事实为准直接跳过镜像删除，避免误删有效镜像
        when(mapper.existsFollowing(1L, 2L)).thenReturn(1);
        when(redis.hasKey("dedup:rel:78")).thenReturn(false);

        processor.process(new RelationEvent("FollowCanceled", 1L, 2L, null), 78L);

        verify(mapper, never()).cancelFollower(2L, 1L);
        verify(zSetOperations, never()).remove(anyString(), anyString());
        // 计数仍然按事实收敛
        verify(userCounterService).rebuildFollowCounters(1L, 2L);
        verify(valueOperations).set("dedup:rel:78", "1", Duration.ofMinutes(10));
    }

    @Test
    void staleFollowCreatedIsSkippedWhenPairIsNotCurrentlyFollowing() {
        when(mapper.existsFollowing(1L, 2L)).thenReturn(0);
        when(redis.hasKey("dedup:rel:79")).thenReturn(false);

        processor.process(new RelationEvent("FollowCreated", 1L, 2L, 9002L), 79L);

        verify(mapper, never()).insertFollower(any(), any(), any(), any());
        verify(zSetOperations, never()).add(anyString(), anyString(), any(Double.class));
        verify(userCounterService).rebuildFollowCounters(1L, 2L);
    }

    @Test
    void followCanceledAppliesMirrorRemovalAndRebuildsCountsWhenPairNotFollowing() {
        when(mapper.existsFollowing(1L, 2L)).thenReturn(0);
        when(redis.hasKey("dedup:rel:80")).thenReturn(false);

        processor.process(new RelationEvent("FollowCanceled", 1L, 2L, null), 80L);

        verify(mapper).cancelFollower(2L, 1L);
        verify(zSetOperations).remove("uf:flws:1", "2");
        verify(zSetOperations).remove("uf:fans:2", "1");
        verify(userCounterService).rebuildFollowCounters(1L, 2L);
        verify(valueOperations).set("dedup:rel:80", "1", Duration.ofMinutes(10));
    }

    @Test
    void nullActorOrTargetIsIgnored() {
        processor.process(new RelationEvent("FollowCreated", null, 2L, 9003L), 81L);
        processor.process(new RelationEvent("FollowCreated", 1L, null, 9004L), 82L);
        processor.process(new RelationEvent("Unknown", 1L, 2L, null), 83L);

        verify(mapper, never()).existsFollowing(any(Long.class), any(Long.class));
        verify(valueOperations, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void withoutOutboxIdFallsBackToTypeAndPairDedupKey() {
        when(mapper.existsFollowing(1L, 2L)).thenReturn(1);
        when(redis.hasKey("dedup:rel:FollowCreated:1:2")).thenReturn(false);

        processor.process(new RelationEvent("FollowCreated", 1L, 2L, 9005L));

        verify(valueOperations).set("dedup:rel:FollowCreated:1:2", "1", Duration.ofMinutes(10));
        verify(userCounterService).rebuildFollowCounters(1L, 2L);
    }
}
