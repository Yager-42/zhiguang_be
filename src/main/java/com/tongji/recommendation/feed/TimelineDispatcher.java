package com.tongji.recommendation.feed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.common.util.OutboxMessageUtil;
import com.tongji.relation.mapper.RelationMapper;
import com.tongji.relation.outbox.OutboxTopics;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
public class TimelineDispatcher {

    @Value("${feed.fanout.push-pull-threshold:10000}")
    private int pushPullThreshold = 10_000;

    private final ObjectMapper objectMapper;
    private final RelationMapper relationMapper;
    private final TimelineExecutor timelineExecutor;
    private final TaskExecutor taskExecutor;

    public TimelineDispatcher(ObjectMapper objectMapper,
                              RelationMapper relationMapper,
                              TimelineExecutor timelineExecutor,
                              @Qualifier("taskExecutor") TaskExecutor taskExecutor) {
        this.objectMapper = objectMapper;
        this.relationMapper = relationMapper;
        this.timelineExecutor = timelineExecutor;
        this.taskExecutor = taskExecutor;
    }

    @KafkaListener(topics = OutboxTopics.CANAL_OUTBOX, groupId = "feed-timeline-consumer")
    public void onMessage(String message, Acknowledgment acknowledgment) {
        List<TimelineDispatch> dispatches = parseDispatches(message);
        if (dispatches.isEmpty()) {
            acknowledgment.acknowledge();
            return;
        }

        taskExecutor.execute(() -> {
            try {
                for (TimelineDispatch dispatch : dispatches) {
                    timelineExecutor.fanout(dispatch);
                }
                acknowledgment.acknowledge();
            } catch (Throwable ignored) {}
        });
    }

    private List<TimelineDispatch> parseDispatches(String message) {
        List<TimelineDispatch> dispatches = new ArrayList<>();
        for (JsonNode row : OutboxMessageUtil.extractRows(objectMapper, message)) {
            JsonNode payloadNode = row.get("payload");
            if (payloadNode == null) {
                continue;
            }
            try {
                JsonNode payload = objectMapper.readTree(payloadNode.asText());
                if (!"content_published".equals(text(payload.get("eventType")))) {
                    continue;
                }
                Long contentId = longValue(payload.get("postId"));
                Long authorId = longValue(payload.get("authorId"));
                Instant publishTs = instantValue(payload.get("publishedAt"));
                if (contentId == null || authorId == null || publishTs == null) {
                    continue;
                }
                dispatches.add(new TimelineDispatch(
                        contentId,
                        authorId,
                        publishTs,
                        relationMapper.countFollowerActive(authorId) >= pushPullThreshold
                ));
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception ignored) {}
        }
        return dispatches;
    }

    private String text(JsonNode node) {
        return node == null ? null : node.asText();
    }

    private Long longValue(JsonNode node) {
        if (node == null) {
            return null;
        }
        try {
            return Long.parseLong(node.asText());
        } catch (Exception ignored) {
            return null;
        }
    }

    private Instant instantValue(JsonNode node) {
        if (node == null || node.asText().isBlank()) {
            return null;
        }
        try {
            return Instant.parse(node.asText());
        } catch (Exception ignored) {
            return null;
        }
    }
}
