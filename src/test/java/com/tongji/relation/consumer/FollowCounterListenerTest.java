package com.tongji.relation.consumer;

import com.tongji.counter.service.UserCounterService;
import com.tongji.relation.manager.FollowCommittedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class FollowCounterListenerTest {

    @Mock
    private UserCounterService userCounterService;

    private FollowCounterListener listener;

    @BeforeEach
    void setUp() {
        listener = new FollowCounterListener(userCounterService);
    }

    @Test
    void followEventIncrementsBothCounters() {
        listener.onFollowCommitted(new FollowCommittedEvent(101L, 202L, 1));

        verify(userCounterService).incrementFollowings(101L, 1);
        verify(userCounterService).incrementFollowers(202L, 1);
    }

    @Test
    void unfollowEventDecrementsBothCounters() {
        listener.onFollowCommitted(new FollowCommittedEvent(101L, 202L, -1));

        verify(userCounterService).incrementFollowings(101L, -1);
        verify(userCounterService).incrementFollowers(202L, -1);
    }

    @Test
    void redisFailureIsToleratedWithoutRethrowOrRetry() {
        doThrow(new RuntimeException("redis down")).when(userCounterService).incrementFollowings(101L, 1);

        listener.onFollowCommitted(new FollowCommittedEvent(101L, 202L, 1));

        verify(userCounterService, never()).incrementFollowers(202L, 1);
    }
}