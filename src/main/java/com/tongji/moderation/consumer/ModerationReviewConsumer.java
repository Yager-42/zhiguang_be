package com.tongji.moderation.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.moderation.service.ModerationReviewExecutor;
import com.tongji.outbox.OutboxEvent;
import com.tongji.outbox.OutboxMessageReader;
import com.tongji.outbox.OutboxPayload;
import com.tongji.outbox.OutboxTopics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@ConditionalOnProperty(prefix = "moderation.llm", name = "enabled", havingValue = "true")
public class ModerationReviewConsumer {
    private final ObjectMapper objectMapper;
    private final ModerationReviewExecutor executor;

    @Autowired
    public ModerationReviewConsumer(ObjectMapper objectMapper,
                                    ModerationReviewExecutor executor) {
        this.objectMapper = objectMapper;
        this.executor = executor;
    }

    @KafkaListener(topics = OutboxTopics.CANAL_OUTBOX, groupId = "moderation-review-consumer")
    public void onMessage(String message, Acknowledgment ack) {
        List<OutboxEvent> events = OutboxMessageReader.read(objectMapper, message);
        for (OutboxEvent event : events) {
            Long reportId = extractReportId(event);
            if (reportId != null) {
                try {
                    executor.review(reportId);
                } catch (RuntimeException exception) {
                    log.warn("moderation review failed, reportId={}: {}", reportId, exception.getMessage(), exception);
                    throw exception;
                }
            }
        }
        ack.acknowledge();
    }

    private Long extractReportId(OutboxEvent event) {
        OutboxPayload payload = event.parsePayload(objectMapper).orElse(null);
        if (payload == null
                || !"moderation_report".equals(payload.text("entity"))
                || !"review_requested".equals(payload.text("op"))) {
            return null;
        }
        Long reportId = payload.longValue("reportId");
        if (reportId == null) {
            log.warn("moderation review outbox missing reportId");
        }
        return reportId;
    }

}
