package com.tongji.notification.consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.comment.mapper.CommentMapper;
import com.tongji.comment.model.Comment;
import com.tongji.counter.event.CounterEvent;
import com.tongji.counter.event.CounterTopics;
import com.tongji.knowpost.mapper.KnowPostMapper;
import com.tongji.knowpost.model.KnowPost;
import com.tongji.notification.model.LikeNotificationBucket;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
public class LikeNotificationConsumer {

    private static final long WINDOW_MILLIS = 5 * 60 * 1000L;
    private static final Duration EVENT_DEDUPE_TTL = Duration.ofHours(6);
    private static final Duration BUCKET_TTL = Duration.ofMinutes(20);

    private final ObjectMapper objectMapper;
    private final StringRedisTemplate redisTemplate;
    private final KnowPostMapper knowPostMapper;
    private final CommentMapper commentMapper;

    public LikeNotificationConsumer(ObjectMapper objectMapper,
                                    StringRedisTemplate redisTemplate,
                                    KnowPostMapper knowPostMapper,
                                    CommentMapper commentMapper) {
        this.objectMapper = objectMapper;
        this.redisTemplate = redisTemplate;
        this.knowPostMapper = knowPostMapper;
        this.commentMapper = commentMapper;
    }

    @KafkaListener(topics = CounterTopics.EVENTS, groupId = "notification-like-consumer")
    public void onMessage(String message, Acknowledgment acknowledgment) throws Exception {
        CounterEvent event = objectMapper.readValue(message, CounterEvent.class);
        if (!"like".equals(event.getMetric())
                || event.getDelta() != 1
                || event.getEventId() == null
                || event.getOccurredAt() == null) {
            acknowledgment.acknowledge();
            return;
        }
        Long recipientUserId = resolveRecipientUserId(event);
        if (recipientUserId == null || recipientUserId.equals(event.getUserId())) {
            acknowledgment.acknowledge();
            return;
        }
        String dedupeKey = "notif:like:event:" + event.getEventId();
        Boolean accepted = redisTemplate.opsForValue().setIfAbsent(dedupeKey, "1", EVENT_DEDUPE_TTL);
        if (!Boolean.TRUE.equals(accepted)) {
            acknowledgment.acknowledge();
            return;
        }
        long windowStart = (event.getOccurredAt() / WINDOW_MILLIS) * WINDOW_MILLIS;
        long windowEnd = windowStart + WINDOW_MILLIS;
        String bucketKey = buildBucketKey(recipientUserId, event.getEntityType(), event.getEntityId(), windowStart);
        redisTemplate.opsForHash().increment(bucketKey, "count", 1);
        redisTemplate.opsForHash().put(bucketKey, "recipientUserId", String.valueOf(recipientUserId));
        redisTemplate.opsForHash().put(bucketKey, "entityType", event.getEntityType());
        redisTemplate.opsForHash().put(bucketKey, "entityId", event.getEntityId());
        redisTemplate.opsForHash().put(bucketKey, "windowStartEpochMillis", String.valueOf(windowStart));
        redisTemplate.opsForHash().put(bucketKey, "windowEndEpochMillis", String.valueOf(windowEnd));
        redisTemplate.opsForHash().put(bucketKey, "latestActorUserId", String.valueOf(event.getUserId()));
        redisTemplate.opsForHash().put(bucketKey, "latestEventAt", String.valueOf(event.getOccurredAt()));
        redisTemplate.opsForValue().set("notif:like:bucket:index:" + bucketKey, "1", BUCKET_TTL);
        redisTemplate.expire(bucketKey, BUCKET_TTL);
        acknowledgment.acknowledge();
    }

    public LikeNotificationBucket readBucket(String bucketKey) {
        var entries = redisTemplate.opsForHash().entries(bucketKey);
        if (entries == null || entries.isEmpty()) {
            return null;
        }
        try {
            return LikeNotificationBucket.builder()
                    .recipientUserId(parseLong(entries.get("recipientUserId")))
                    .entityType((String) entries.get("entityType"))
                    .entityId(parseLong(entries.get("entityId")))
                    .windowStartEpochMillis(parseLong(entries.get("windowStartEpochMillis")))
                    .windowEndEpochMillis(parseLong(entries.get("windowEndEpochMillis")))
                    .count(parseInt(entries.get("count")))
                    .latestActorUserId(parseLong(entries.get("latestActorUserId")))
                    .latestEventAt(parseLong(entries.get("latestEventAt")))
                    .build();
        } catch (Exception exception) {
            throw new IllegalStateException("invalid like notification bucket " + bucketKey, exception);
        }
    }

    public String buildBucketKey(long recipientUserId, String entityType, String entityId, long windowStart) {
        return "notif:like:bucket:%d:%s:%s:%d".formatted(recipientUserId, entityType, entityId, windowStart);
    }

    private Long resolveRecipientUserId(CounterEvent event) {
        if ("knowpost".equals(event.getEntityType())) {
            KnowPost post = knowPostMapper.findById(parseLong(event.getEntityId()));
            return post == null ? null : post.getCreatorId();
        }
        if ("comment".equals(event.getEntityType())) {
            Comment comment = commentMapper.findById(parseLong(event.getEntityId()));
            return comment == null ? null : comment.getCreatorId();
        }
        return null;
    }

    private Long parseLong(Object value) {
        if (value == null) {
            return null;
        }
        return Long.parseLong(String.valueOf(value));
    }

    private Integer parseInt(Object value) {
        if (value == null) {
            return null;
        }
        return Integer.parseInt(String.valueOf(value));
    }
}
