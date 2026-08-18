package com.tongji.relation.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.relation.manager.RelationManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class RelationFollowCommandConsumerTest {

    @Mock
    private RelationManager relationManager;
    @Mock
    private Acknowledgment acknowledgment;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private RelationFollowCommandConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new RelationFollowCommandConsumer(relationManager, objectMapper);
    }

    @Test
    void followEventExecutesFollowAndAcks() throws Exception {
        consumer.onMessage("{\"fromUserId\":101,\"toUserId\":202,\"follow\":true}", acknowledgment);

        verify(relationManager).follow(101L, 202L);
        verify(acknowledgment).acknowledge();
    }

    @Test
    void unfollowEventExecutesUnfollowAndAcks() throws Exception {
        consumer.onMessage("{\"fromUserId\":101,\"toUserId\":202,\"follow\":false}", acknowledgment);

        verify(relationManager).unfollow(101L, 202L);
        verify(acknowledgment).acknowledge();
    }

    @Test
    void managerFailureSkipsAckAndRethrows() {
        doThrow(new RuntimeException("db down")).when(relationManager).follow(101L, 202L);

        assertThatThrownBy(() ->
                consumer.onMessage("{\"fromUserId\":101,\"toUserId\":202,\"follow\":true}", acknowledgment))
                .isInstanceOf(RuntimeException.class);
        verify(acknowledgment, never()).acknowledge();
    }

    @Test
    void malformedMessagePropagatesWithoutAck() {
        assertThatThrownBy(() -> consumer.onMessage("not-json", acknowledgment))
                .isInstanceOf(Exception.class);
    }
}