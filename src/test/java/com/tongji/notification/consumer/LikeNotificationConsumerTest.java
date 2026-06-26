package com.tongji.notification.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.comment.mapper.CommentMapper;
import com.tongji.comment.model.Comment;
import com.tongji.counter.event.CounterEvent;
import com.tongji.knowpost.mapper.KnowPostMapper;
import com.tongji.knowpost.model.KnowPost;
import com.tongji.notification.model.LikeNotificationBucket;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.kafka.support.Acknowledgment;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LikeNotificationConsumerTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOperations;
    private HashOperations<String, Object, Object> hashOperations;
    private KnowPostMapper knowPostMapper;
    private CommentMapper commentMapper;
    private Acknowledgment acknowledgment;
    private LikeNotificationConsumer consumer;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        hashOperations = mock(HashOperations.class);
        knowPostMapper = mock(KnowPostMapper.class);
        commentMapper = mock(CommentMapper.class);
        acknowledgment = mock(Acknowledgment.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisTemplate.opsForHash()).thenReturn((HashOperations) hashOperations);
        consumer = new LikeNotificationConsumer(objectMapper, redisTemplate, knowPostMapper, commentMapper);
    }

    @Test
    void postLikeAggregatesIntoRedisBucket() throws Exception {
        KnowPost post = new KnowPost();
        post.setId(101L);
        post.setCreatorId(9L);
        when(knowPostMapper.findById(101L)).thenReturn(post);
        when(valueOperations.setIfAbsent(eq("notif:like:event:event-1"), eq("1"), any(Duration.class))).thenReturn(true);

        CounterEvent event = new CounterEvent("event-1", 1_719_390_000_000L, "knowpost", "101", "like", 0, 7L, 1);

        consumer.onMessage(objectMapper.writeValueAsString(event), acknowledgment);

        verify(hashOperations).increment(eq("notif:like:bucket:9:knowpost:101:1719390000000"), eq("count"), eq(1L));
        verify(hashOperations).put("notif:like:bucket:9:knowpost:101:1719390000000", "recipientUserId", "9");
        verify(hashOperations).put("notif:like:bucket:9:knowpost:101:1719390000000", "latestActorUserId", "7");
        verify(acknowledgment).acknowledge();
    }

    @Test
    void duplicateEventIdDoesNotAggregateAgain() throws Exception {
        KnowPost post = new KnowPost();
        post.setId(101L);
        post.setCreatorId(9L);
        when(knowPostMapper.findById(101L)).thenReturn(post);
        when(valueOperations.setIfAbsent(eq("notif:like:event:event-1"), eq("1"), any(Duration.class))).thenReturn(false);

        CounterEvent event = new CounterEvent("event-1", 1_719_390_000_000L, "knowpost", "101", "like", 0, 7L, 1);

        consumer.onMessage(objectMapper.writeValueAsString(event), acknowledgment);

        verify(hashOperations, never()).increment(any(), any(), any(Long.class));
        verify(acknowledgment).acknowledge();
    }

    @Test
    void selfLikeDoesNotAggregate() throws Exception {
        KnowPost post = new KnowPost();
        post.setId(101L);
        post.setCreatorId(7L);
        when(knowPostMapper.findById(101L)).thenReturn(post);

        CounterEvent event = new CounterEvent("event-1", 1_719_390_000_000L, "knowpost", "101", "like", 0, 7L, 1);

        consumer.onMessage(objectMapper.writeValueAsString(event), acknowledgment);

        verify(valueOperations, never()).setIfAbsent(any(), any(), any(Duration.class));
        verify(hashOperations, never()).increment(any(), any(), any(Long.class));
        verify(acknowledgment).acknowledge();
    }

    @Test
    void readBucketBuildsTypedView() {
        when(hashOperations.entries("notif:like:bucket:9:knowpost:101:1719390000000")).thenReturn(Map.of(
                "recipientUserId", "9",
                "entityType", "knowpost",
                "entityId", "101",
                "windowStartEpochMillis", "1719390000000",
                "windowEndEpochMillis", "1719390300000",
                "count", "3",
                "latestActorUserId", "7",
                "latestEventAt", "1719390000999"
        ));

        LikeNotificationBucket bucket = consumer.readBucket("notif:like:bucket:9:knowpost:101:1719390000000");

        assertThat(bucket.getRecipientUserId()).isEqualTo(9L);
        assertThat(bucket.getEntityType()).isEqualTo("knowpost");
        assertThat(bucket.getEntityId()).isEqualTo(101L);
        assertThat(bucket.getCount()).isEqualTo(3);
        assertThat(bucket.getLatestActorUserId()).isEqualTo(7L);
    }
}
