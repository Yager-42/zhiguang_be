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
        Set<String> indexKeys = redisTemplate.keys("notif:like:bucket:index:notif:like:bucket:*");
        if (indexKeys == null || indexKeys.isEmpty()) {
            return;
        }
        long now = Instant.now().toEpochMilli();
        for (String indexKey : indexKeys) {
            String bucketKey = indexKey.substring("notif:like:bucket:index:".length());
            LikeNotificationBucket bucket = likeNotificationConsumer.readBucket(bucketKey);
            if (bucket == null || bucket.getWindowEndEpochMillis() == null || bucket.getWindowEndEpochMillis() > now) {
                continue;
            }
            notificationCommandService.createLikeNotification(bucket);
            redisTemplate.delete(bucketKey);
            redisTemplate.delete(indexKey);
        }
    }
}
