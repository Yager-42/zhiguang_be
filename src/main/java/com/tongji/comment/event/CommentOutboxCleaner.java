package com.tongji.comment.event;

import com.tongji.comment.mapper.CommentOutboxMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Slf4j
@Component
public class CommentOutboxCleaner {
    private final CommentOutboxMapper outboxMapper;
    private final int retentionHours;
    private final int batchSize;

    public CommentOutboxCleaner(CommentOutboxMapper outboxMapper,
                                @Value("${comment.outbox.retention-hours:24}") int retentionHours,
                                @Value("${comment.outbox.clean-batch-size:1000}") int batchSize) {
        this.outboxMapper = outboxMapper;
        this.retentionHours = retentionHours;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${comment.outbox.clean-interval-ms:86400000}")
    public void clean() {
        try {
            outboxMapper.deletePublishedBefore(LocalDateTime.now().minusHours(retentionHours), batchSize);
        } catch (RuntimeException exception) {
            log.warn("comment outbox cleanup failed", exception);
        }
    }
}
