package com.tongji.comment.event;

import com.tongji.comment.mapper.CommentOutboxMapper;
import com.tongji.comment.model.CommentOutbox;
import com.tongji.comment.model.CommentOutboxRetry;
import com.tongji.comment.metrics.CommentMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
public class CommentOutboxDispatcher {
    private static final int MAX_ERROR_LENGTH = 500;
    private static final long ERROR_LOG_INTERVAL_MILLIS = 10_000L;
    private static final AtomicLong LAST_ERROR_LOG_TIME = new AtomicLong();

    private final CommentOutboxMapper outboxMapper;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final TaskExecutor coordinator;
    private final String writeTopic;
    private final String eventTopic;
    private final int batchSize;
    private final int claimSeconds;
    private final AtomicBoolean running = new AtomicBoolean();
    private final CommentMetrics metrics;

    public CommentOutboxDispatcher(CommentOutboxMapper outboxMapper,
                                   KafkaTemplate<String, String> kafkaTemplate,
                                   @Qualifier("commentOutboxExecutor") TaskExecutor coordinator,
                                   @Value("${comment.kafka.write-topic:comment-write}") String writeTopic,
                                   @Value("${comment.kafka.event-topic:comment-events}") String eventTopic,
                                   @Value("${comment.outbox.batch-size:500}") int batchSize,
                                   @Value("${comment.outbox.claim-seconds:30}") int claimSeconds,
                                   CommentMetrics metrics) {
        this.outboxMapper = outboxMapper;
        this.kafkaTemplate = kafkaTemplate;
        this.coordinator = coordinator;
        this.writeTopic = writeTopic;
        this.eventTopic = eventTopic;
        this.batchSize = batchSize;
        this.claimSeconds = claimSeconds;
        this.metrics = metrics;
    }

    @Scheduled(fixedDelayString = "${comment.outbox.publish-interval-ms:50}")
    public void dispatchReady() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            LocalDateTime now = LocalDateTime.now();
            outboxMapper.releaseExpiredClaims(now);
            String claimToken = UUID.randomUUID().toString();
            int claimedCount = outboxMapper.claimReady(claimToken, now, now.plusSeconds(claimSeconds), batchSize);
            if (claimedCount == 0) {
                running.set(false);
                return;
            }
            dispatchClaimed(claimToken, outboxMapper.findClaimed(claimToken));
        } catch (RuntimeException exception) {
            running.set(false);
            throw exception;
        }
    }

    private void dispatchClaimed(String claimToken, List<CommentOutbox> rows) {
        long startedAt = System.nanoTime();
        List<CompletableFuture<SendOutcome>> outcomes = new ArrayList<>(rows.size());
        for (CommentOutbox row : rows) {
            try {
                String topic = route(row.getEventType());
                outcomes.add(kafkaTemplate.send(topic, String.valueOf(row.getAggregateId()), row.getPayload())
                        .handle((result, error) -> new SendOutcome(row, error)));
            } catch (RuntimeException exception) {
                outcomes.add(CompletableFuture.completedFuture(new SendOutcome(row, exception)));
            }
        }
        CompletableFuture<?>[] futures = outcomes.toArray(CompletableFuture[]::new);
        CompletableFuture.allOf(futures).whenCompleteAsync((ignored, coordinationError) -> {
            try {
                finishBatch(claimToken, outcomes);
                metrics.outboxBatch(rows.size(), Duration.ofNanos(System.nanoTime() - startedAt));
            } finally {
                running.set(false);
            }
        }, coordinator::execute);
    }

    private void finishBatch(String claimToken, List<CompletableFuture<SendOutcome>> outcomes) {
        List<Long> published = new ArrayList<>();
        List<CommentOutboxRetry> retries = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();
        for (CompletableFuture<SendOutcome> future : outcomes) {
            SendOutcome outcome = future.join();
            CommentOutbox row = outcome.row();
            if (outcome.error() == null) {
                published.add(row.getEventId());
                continue;
            }
            int retryCount = row.getRetryCount() + 1;
            long delaySeconds = Math.min(30L, 1L << Math.min(retryCount, 5));
            retries.add(new CommentOutboxRetry(row.getEventId(), retryCount,
                    now.plusSeconds(delaySeconds), truncate(outcome.error().getMessage())));
            metrics.outbox("send_failure", row.getEventType(), 1);
            logSendFailure(row.getEventType(), retryCount, outcome.error());
        }
        if (!published.isEmpty()) {
            outboxMapper.markPublishedBatch(published, claimToken, now);
            outcomes.stream()
                    .map(CompletableFuture::join)
                    .filter(outcome -> outcome.error() == null)
                    .forEach(outcome -> metrics.outbox("published", outcome.row().getEventType(), 1));
        }
        if (!retries.isEmpty()) {
            outboxMapper.markRetryBatch(retries, claimToken);
            outcomes.stream()
                    .map(CompletableFuture::join)
                    .filter(outcome -> outcome.error() != null)
                    .forEach(outcome -> metrics.outbox("retry", outcome.row().getEventType(), 1));
        }
    }

    private void logSendFailure(String eventType, int retryCount, Throwable error) {
        long now = System.currentTimeMillis();
        long previous = LAST_ERROR_LOG_TIME.get();
        if (now - previous >= ERROR_LOG_INTERVAL_MILLIS
                && LAST_ERROR_LOG_TIME.compareAndSet(previous, now)) {
            log.warn("comment outbox send failed, eventType={}, retryCount={}", eventType, retryCount, error);
        }
    }

    private String route(String eventType) {
        CommentEventType type = CommentEventType.valueOf(eventType);
        return type == CommentEventType.COMMENT_WRITE_REQUESTED ? writeTopic : eventTopic;
    }

    private String truncate(String message) {
        if (message == null || message.length() <= MAX_ERROR_LENGTH) {
            return message;
        }
        return message.substring(0, MAX_ERROR_LENGTH);
    }

    private record SendOutcome(CommentOutbox row, Throwable error) {
    }
}
