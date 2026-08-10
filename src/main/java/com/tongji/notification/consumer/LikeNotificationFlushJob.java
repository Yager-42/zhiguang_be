package com.tongji.notification.consumer;

import com.tongji.notification.model.LikeNotificationBucket;
import com.tongji.notification.service.NotificationCommandService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Set;

@Service
public class LikeNotificationFlushJob {
    private static final int FLUSH_BATCH_SIZE = 1_000;

    private final StringRedisTemplate redisTemplate;
    private final LikeNotificationConsumer likeNotificationConsumer;
    private final NotificationCommandService notificationCommandService;

    public LikeNotificationFlushJob(StringRedisTemplate redisTemplate,
                                    LikeNotificationConsumer likeNotificationConsumer,
                                    NotificationCommandService notificationCommandService) {
        this.redisTemplate = redisTemplate;
        this.likeNotificationConsumer = likeNotificationConsumer;
        this.notificationCommandService = notificationCommandService;
    }

    @Scheduled(fixedDelay = 30000L)
    public void flush() {
        long now = Instant.now().toEpochMilli();
        Set<String> bucketKeys = redisTemplate.opsForZSet().rangeByScore(
                LikeNotificationConsumer.DUE_BUCKET_INDEX_KEY,
                Double.NEGATIVE_INFINITY, now, 0, FLUSH_BATCH_SIZE);
        if (bucketKeys == null || bucketKeys.isEmpty()) {
            return;
        }
        for (String bucketKey : bucketKeys) {
            LikeNotificationBucket bucket = likeNotificationConsumer.readBucket(bucketKey);
            if (bucket == null || bucket.getWindowEndEpochMillis() == null) {
                redisTemplate.opsForZSet().remove(LikeNotificationConsumer.DUE_BUCKET_INDEX_KEY, bucketKey);
                continue;
            }
            if (bucket.getWindowEndEpochMillis() > now) {
                redisTemplate.opsForZSet().add(LikeNotificationConsumer.DUE_BUCKET_INDEX_KEY,
                        bucketKey, bucket.getWindowEndEpochMillis());
                continue;
            }
            notificationCommandService.createLikeNotification(bucket);
            redisTemplate.delete(bucketKey);
            redisTemplate.opsForZSet().remove(LikeNotificationConsumer.DUE_BUCKET_INDEX_KEY, bucketKey);
        }
    }
}
