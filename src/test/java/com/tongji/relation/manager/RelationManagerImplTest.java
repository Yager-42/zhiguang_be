package com.tongji.relation.manager;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.common.resilience.GuardResult;
import com.tongji.common.resilience.GuardedOperation;
import com.tongji.common.resilience.ResilienceGuard;
import com.tongji.common.id.IdNamespace;
import com.tongji.common.id.IdService;
import com.tongji.relation.mapper.RelationMapper;
import com.tongji.outbox.OutboxMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RelationManagerImplTest {

    private static final long FROM_USER_ID = 101L;
    private static final long TO_USER_ID = 202L;

    private RelationMapper relationMapper;
    private OutboxMapper outboxMapper;
    private IdService idService;
    private RecordingResilienceGuard resilienceGuard;
    private RelationManager relationManager;
    private TestStringRedisTemplate redisTemplate;

    @BeforeEach
    void setUp() {
        relationMapper = mock(RelationMapper.class);
        outboxMapper = mock(OutboxMapper.class);
        redisTemplate = new TestStringRedisTemplate();
        idService = mock(IdService.class);
        resilienceGuard = new RecordingResilienceGuard();
        relationManager = new RelationManagerImpl(
                relationMapper,
                redisTemplate,
                idService,
                new RelationPublisher(new ObjectMapper(), outboxMapper, idService),
                resilienceGuard
        );
    }

    @Test
    void followSkipsDuplicateSideEffectsWhenAlreadyFollowing() {
        allowRateLimit();
        when(relationMapper.existsFollowing(FROM_USER_ID, TO_USER_ID)).thenReturn(1);

        RelationWriteResult result = relationManager.follow(FROM_USER_ID, TO_USER_ID);

        assertThat(result.success()).isTrue();
        assertThat(result.stateChanged()).isFalse();
        assertThat(result.following()).isTrue();
        verify(relationMapper, never()).insertFollowing(eq(11L), eq(FROM_USER_ID), eq(TO_USER_ID), eq(1));
        verify(idService, never()).nextId(IdNamespace.RELATION);
        verify(outboxMapper, never()).insert(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void duplicateFollowReturnsUnchangedSuccessEvenWhenRateLimited() {
        redisTemplate.nextResult = 0L;
        when(relationMapper.existsFollowing(FROM_USER_ID, TO_USER_ID)).thenReturn(1);

        RelationWriteResult result = relationManager.follow(FROM_USER_ID, TO_USER_ID);

        assertThat(result.success()).isTrue();
        assertThat(result.stateChanged()).isFalse();
        assertThat(result.following()).isTrue();
        verify(relationMapper, never()).insertFollowing(eq(11L), eq(FROM_USER_ID), eq(TO_USER_ID), eq(1));
        verify(idService, never()).nextId(IdNamespace.RELATION);
        verify(outboxMapper, never()).insert(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void unfollowSkipsDuplicateSideEffectsWhenAlreadyUnfollowed() {
        when(relationMapper.existsFollowing(FROM_USER_ID, TO_USER_ID)).thenReturn(0);

        RelationWriteResult result = relationManager.unfollow(FROM_USER_ID, TO_USER_ID);

        assertThat(result.success()).isTrue();
        assertThat(result.stateChanged()).isFalse();
        assertThat(result.following()).isFalse();
        verify(relationMapper, never()).cancelFollowing(FROM_USER_ID, TO_USER_ID);
        verify(idService, never()).nextId(IdNamespace.OUTBOX_EVENT);
        verify(outboxMapper, never()).insert(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void followUsesRelationIdForRowAndOutboxIdForEvent() throws Exception {
        allowRateLimit();
        when(relationMapper.existsFollowing(FROM_USER_ID, TO_USER_ID)).thenReturn(0);
        when(idService.nextId(IdNamespace.RELATION)).thenReturn(9001L);
        when(relationMapper.findActiveFollowingId(FROM_USER_ID, TO_USER_ID)).thenReturn(9001L);
        when(idService.nextId(IdNamespace.OUTBOX_EVENT)).thenReturn(7001L);
        when(relationMapper.insertFollowing(9001L, FROM_USER_ID, TO_USER_ID, 1)).thenReturn(1);

        RelationWriteResult result = relationManager.follow(FROM_USER_ID, TO_USER_ID);

        assertThat(result.success()).isTrue();
        assertThat(result.stateChanged()).isTrue();
        assertThat(result.following()).isTrue();
        verify(idService).nextId(IdNamespace.RELATION);
        verify(idService).nextId(IdNamespace.OUTBOX_EVENT);

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(outboxMapper).insert(eq(7001L), eq("following"), eq(9001L), eq("FollowCreated"), payloadCaptor.capture());
        assertThat(payloadCaptor.getValue()).contains("\"type\":\"FollowCreated\"");
        assertThat(payloadCaptor.getValue()).contains("\"fromUserId\":101");
        assertThat(payloadCaptor.getValue()).contains("\"toUserId\":202");
        assertThat(payloadCaptor.getValue()).contains("\"id\":9001");
    }

    @Test
    void refollowPublishesPersistedActiveRelationIdInsteadOfFreshAllocatedId() {
        allowRateLimit();
        when(relationMapper.existsFollowing(FROM_USER_ID, TO_USER_ID)).thenReturn(0);
        when(idService.nextId(IdNamespace.RELATION)).thenReturn(9001L);
        when(relationMapper.insertFollowing(9001L, FROM_USER_ID, TO_USER_ID, 1)).thenReturn(1);
        when(relationMapper.findActiveFollowingId(FROM_USER_ID, TO_USER_ID)).thenReturn(5005L);
        when(idService.nextId(IdNamespace.OUTBOX_EVENT)).thenReturn(7001L);

        RelationWriteResult result = relationManager.follow(FROM_USER_ID, TO_USER_ID);

        assertThat(result.success()).isTrue();
        verify(outboxMapper).insert(eq(7001L), eq("following"), eq(5005L), eq("FollowCreated"), org.mockito.ArgumentMatchers.contains("\"id\":5005"));
    }

    @Test
    void unfollowUsesOutboxIdForEventWhenStateChanges() {
        when(relationMapper.existsFollowing(FROM_USER_ID, TO_USER_ID)).thenReturn(1);
        when(relationMapper.cancelFollowing(FROM_USER_ID, TO_USER_ID)).thenReturn(1);
        when(idService.nextId(IdNamespace.OUTBOX_EVENT)).thenReturn(8002L);

        RelationWriteResult result = relationManager.unfollow(FROM_USER_ID, TO_USER_ID);

        assertThat(result.success()).isTrue();
        assertThat(result.stateChanged()).isTrue();
        assertThat(result.following()).isFalse();
        verify(idService).nextId(IdNamespace.OUTBOX_EVENT);
        verify(outboxMapper).insert(eq(8002L), eq("following"), eq(null), eq("FollowCanceled"), org.mockito.ArgumentMatchers.contains("\"type\":\"FollowCanceled\""));
    }

    @Test
    void followReturnsFailureWhenRateLimited() {
        redisTemplate.nextResult = 0L;
        when(relationMapper.existsFollowing(FROM_USER_ID, TO_USER_ID)).thenReturn(0);

        RelationWriteResult result = relationManager.follow(FROM_USER_ID, TO_USER_ID);

        assertThat(result.success()).isFalse();
        assertThat(result.stateChanged()).isFalse();
        assertThat(result.following()).isFalse();
        verify(relationMapper).existsFollowing(FROM_USER_ID, TO_USER_ID);
        verify(relationMapper, never()).insertFollowing(eq(11L), eq(FROM_USER_ID), eq(TO_USER_ID), eq(1));
        verify(outboxMapper, never()).insert(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void followPropagatesOutboxFailure() {
        allowRateLimit();
        when(relationMapper.existsFollowing(FROM_USER_ID, TO_USER_ID)).thenReturn(0);
        when(idService.nextId(IdNamespace.RELATION)).thenReturn(9001L);
        when(relationMapper.insertFollowing(9001L, FROM_USER_ID, TO_USER_ID, 1)).thenReturn(1);
        when(relationMapper.findActiveFollowingId(FROM_USER_ID, TO_USER_ID)).thenReturn(9001L);
        when(idService.nextId(IdNamespace.OUTBOX_EVENT)).thenReturn(7001L);
        when(outboxMapper.insert(eq(7001L), eq("following"), eq(9001L), eq("FollowCreated"), anyString()))
                .thenThrow(new RuntimeException("outbox write failed"));

        assertThatThrownBy(() -> relationManager.follow(FROM_USER_ID, TO_USER_ID))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("outbox write failed");
        assertThat(resilienceGuard.resourceNames).contains("relation:outbox-publish");
    }

    @Test
    void followFailsWhenGuardFallsBackOnCriticalOutboxPublish() {
        allowRateLimit();
        when(relationMapper.existsFollowing(FROM_USER_ID, TO_USER_ID)).thenReturn(0);
        when(idService.nextId(IdNamespace.RELATION)).thenReturn(9001L);
        when(relationMapper.insertFollowing(9001L, FROM_USER_ID, TO_USER_ID, 1)).thenReturn(1);
        when(relationMapper.findActiveFollowingId(FROM_USER_ID, TO_USER_ID)).thenReturn(9001L);
        resilienceGuard.forceFallback = true;

        assertThatThrownBy(() -> relationManager.follow(FROM_USER_ID, TO_USER_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("relation outbox publish degraded");
    }

    private void allowRateLimit() {
        redisTemplate.nextResult = 1L;
    }

    private static final class TestStringRedisTemplate extends StringRedisTemplate {
        private Long nextResult = 1L;

        @Override
        @SuppressWarnings("unchecked")
        public <T> T execute(RedisScript<T> script, List<String> keys, Object... args) {
            return (T) nextResult;
        }
    }

    private static final class RecordingResilienceGuard implements ResilienceGuard {
        private final List<String> resourceNames = new java.util.ArrayList<>();
        private boolean forceFallback;

        @Override
        public <T> GuardResult<T> execute(String resourceName,
                                          GuardedOperation<T> operation,
                                          Supplier<T> fallbackSupplier,
                                          Predicate<Throwable> systemFailureClassifier) {
            resourceNames.add(resourceName);
            if (forceFallback) {
                return GuardResult.fallback(fallbackSupplier.get(), null);
            }
            try {
                return GuardResult.success(operation.execute());
            } catch (Exception exception) {
                return GuardResult.fallback(fallbackSupplier.get(), exception);
            }
        }
    }
}
