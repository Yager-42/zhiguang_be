package com.tongji.notification.consumer;

import com.tongji.notification.model.LikeNotificationBucket;
import com.tongji.notification.service.NotificationCommandService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Instant;
import java.util.Set;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LikeNotificationFlushJobTest {

    private StringRedisTemplate redisTemplate;
    private LikeNotificationConsumer likeNotificationConsumer;
    private NotificationCommandService commandService;
    private LikeNotificationFlushJob flushJob;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        likeNotificationConsumer = mock(LikeNotificationConsumer.class);
        commandService = mock(NotificationCommandService.class);
        flushJob = new LikeNotificationFlushJob(redisTemplate, likeNotificationConsumer, commandService);
    }

    @Test
    void dueBucketFlushesAndDeletesRedisKeys() {
        long now = Instant.now().toEpochMilli();
        String indexKey = "notif:like:bucket:index:notif:like:bucket:9:knowpost:101:1719390000000";
        String bucketKey = "notif:like:bucket:9:knowpost:101:1719390000000";
        LikeNotificationBucket bucket = LikeNotificationBucket.builder()
                .recipientUserId(9L)
                .entityType("knowpost")
                .entityId(101L)
                .windowStartEpochMillis(now - 600_000)
                .windowEndEpochMillis(now - 300_000)
                .count(3)
                .latestActorUserId(7L)
                .latestEventAt(now - 200_000)
                .build();
        when(redisTemplate.keys("notif:like:bucket:index:notif:like:bucket:*")).thenReturn(Set.of(indexKey));
        when(likeNotificationConsumer.readBucket(bucketKey)).thenReturn(bucket);

        flushJob.flush();

        verify(commandService).createLikeNotification(bucket);
        verify(redisTemplate).delete(bucketKey);
        verify(redisTemplate).delete(indexKey);
    }

    @Test
    void activeBucketIsNotFlushed() {
        long now = Instant.now().toEpochMilli();
        String indexKey = "notif:like:bucket:index:notif:like:bucket:9:knowpost:101:1719390000000";
        String bucketKey = "notif:like:bucket:9:knowpost:101:1719390000000";
        LikeNotificationBucket bucket = LikeNotificationBucket.builder()
                .windowEndEpochMillis(now + 300_000)
                .build();
        when(redisTemplate.keys("notif:like:bucket:index:notif:like:bucket:*")).thenReturn(Set.of(indexKey));
        when(likeNotificationConsumer.readBucket(bucketKey)).thenReturn(bucket);

        flushJob.flush();

        verify(commandService, never()).createLikeNotification(bucket);
        verify(redisTemplate, never()).delete(bucketKey);
    }
}
