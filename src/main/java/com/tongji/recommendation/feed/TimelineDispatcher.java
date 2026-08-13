package com.tongji.recommendation.feed;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.outbox.OutboxEvent;
import com.tongji.outbox.OutboxMessageReader;
import com.tongji.outbox.OutboxPayload;
import com.tongji.reconciliation.executor.FollowInboxReconciler.FollowInboxPayload;
import com.tongji.reconciliation.model.ReconciliationTargetType;
import com.tongji.reconciliation.model.ReconciliationTaskType;
import com.tongji.reconciliation.service.ReconciliationService;
import com.tongji.relation.mapper.RelationMapper;
import com.tongji.outbox.OutboxTopics;
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
    private final ReconciliationService reconciliationService;

    public TimelineDispatcher(ObjectMapper objectMapper,
                              RelationMapper relationMapper,
                              TimelineExecutor timelineExecutor,
                              @Qualifier("taskExecutor") TaskExecutor taskExecutor,
                              ReconciliationService reconciliationService) {
        this.objectMapper = objectMapper;
        this.relationMapper = relationMapper;
        this.timelineExecutor = timelineExecutor;
        this.taskExecutor = taskExecutor;
        this.reconciliationService = reconciliationService;
    }

    @KafkaListener(topics = OutboxTopics.CANAL_OUTBOX, groupId = "feed-timeline-consumer")
    public void onMessage(String message, Acknowledgment acknowledgment) {
        List<TimelineDispatch> dispatches = parseDispatches(message);
        if (dispatches.isEmpty()) {
            acknowledgment.acknowledge();
            return;
        }

        taskExecutor.execute(() -> {
            for (TimelineDispatch dispatch : dispatches) {
                try {
                    timelineExecutor.fanout(dispatch);
                } catch (Throwable ignored) {
                    reconciliationService.createTaskIfAbsent(
                            ReconciliationTaskType.FOLLOW_INBOX,
                            ReconciliationTargetType.USER,
                            dispatch.authorId(),
                            writePayload(dispatch)
                    );
                }
            }
            acknowledgment.acknowledge();
        });
    }

    private List<TimelineDispatch> parseDispatches(String message) {
        List<TimelineDispatch> dispatches = new ArrayList<>();
        for (OutboxEvent event : OutboxMessageReader.read(objectMapper, message)) {
            OutboxPayload payload = event.parsePayload(objectMapper).orElse(null);
            if (payload == null || !"content_published".equals(payload.text("eventType"))) {
                continue;
            }
            Long contentId = payload.longValue("postId");
            Long authorId = payload.longValue("authorId");
            Instant publishTs = payload.instantValue("publishedAt");
            if (contentId == null || authorId == null || publishTs == null) {
                continue;
            }
            dispatches.add(new TimelineDispatch(
                    contentId,
                    authorId,
                    publishTs,
                    relationMapper.countFollowerActive(authorId) >= pushPullThreshold
            ));
        }
        return dispatches;
    }



    private String writePayload(TimelineDispatch dispatch) {
        try {
            return objectMapper.writeValueAsString(new FollowInboxPayload(
                    dispatch.contentId(),
                    dispatch.authorId(),
                    dispatch.publishTs().toString(),
                    dispatch.largeAuthor()
            ));
        } catch (Exception e) {
            throw new IllegalStateException("Unable to serialize follow inbox payload", e);
        }
    }
}
