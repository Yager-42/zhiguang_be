package com.tongji.comment.event;

import com.tongji.comment.config.CommentOutboxSchemaInitializer;
import com.tongji.comment.mapper.CommentWriteOutboxMapper;
import com.tongji.comment.model.CommentWriteOutbox;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Slf4j
@Component
public class CommentWriteOutboxPublisher {
    private static final int MAX_ERROR_LENGTH = 512;

    private final CommentWriteOutboxMapper outboxMapper;
    private final CommentWriteProducer producer;
    private final int batchSize;
    private final int claimSeconds;

    public CommentWriteOutboxPublisher(CommentWriteOutboxMapper outboxMapper,
                                       CommentWriteProducer producer,
                                       CommentOutboxSchemaInitializer schemaInitializer,
                                       @Value("${comment.outbox.batch-size:100}") int batchSize,
                                       @Value("${comment.outbox.claim-seconds:30}") int claimSeconds) {
        this.outboxMapper = outboxMapper;
        this.producer = producer;
        this.batchSize = batchSize;
        this.claimSeconds = claimSeconds;
    }

    @Scheduled(fixedDelayString = "${comment.outbox.publish-interval-ms:50}")
    public void publishReady() {
        LocalDateTime now = LocalDateTime.now();
        outboxMapper.releaseExpiredClaims(now);
        String claimToken = UUID.randomUUID().toString();
        if (outboxMapper.claimReady(claimToken, now, now.plusSeconds(claimSeconds), batchSize) == 0) {
            return;
        }
        List<CommentWriteOutbox> claimed = outboxMapper.findClaimed(claimToken);
        for (CommentWriteOutbox outbox : claimed) {
            publishOne(outbox, claimToken);
        }
    }

    private void publishOne(CommentWriteOutbox outbox, String claimToken) {
        try {
            producer.publish(new CommentWriteEvent(
                    outbox.getCommentId(),
                    outbox.getPostId(),
                    outbox.getRootId(),
                    outbox.getParentId(),
                    outbox.getCreatorId(),
                    outbox.getClientRequestId(),
                    outbox.getBody()));
            outboxMapper.markPublished(outbox.getCommentId(), claimToken, LocalDateTime.now());
        } catch (RuntimeException exception) {
            int attempts = outbox.getAttemptCount() + 1;
            long delaySeconds = Math.min(60L, 1L << Math.min(attempts, 6));
            outboxMapper.markRetry(
                    outbox.getCommentId(),
                    claimToken,
                    attempts,
                    LocalDateTime.now().plusSeconds(delaySeconds),
                    truncate(exception.getMessage()));
            log.warn("comment outbox publish failed commentId={} attempts={}", outbox.getCommentId(), attempts, exception);
        }
    }

    private String truncate(String value) {
        if (value == null || value.length() <= MAX_ERROR_LENGTH) {
            return value;
        }
        return value.substring(0, MAX_ERROR_LENGTH);
    }
}
