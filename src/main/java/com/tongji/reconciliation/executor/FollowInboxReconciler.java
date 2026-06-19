package com.tongji.reconciliation.executor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.recommendation.feed.TimelineDispatch;
import com.tongji.recommendation.feed.TimelineExecutor;
import com.tongji.reconciliation.model.ReconciliationTask;
import com.tongji.reconciliation.model.ReconciliationTaskType;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class FollowInboxReconciler implements Reconciler {

    private final ObjectMapper objectMapper;
    private final TimelineExecutor timelineExecutor;

    public FollowInboxReconciler(ObjectMapper objectMapper, TimelineExecutor timelineExecutor) {
        this.objectMapper = objectMapper;
        this.timelineExecutor = timelineExecutor;
    }

    @Override
    public String taskType() {
        return ReconciliationTaskType.FOLLOW_INBOX;
    }

    @Override
    public void reconcile(ReconciliationTask task) {
        FollowInboxPayload payload = readPayload(task);
        timelineExecutor.fanout(new TimelineDispatch(
                payload.postId(),
                payload.authorId(),
                Instant.parse(payload.publishedAt()),
                payload.largeAuthor()
        ));
    }

    private FollowInboxPayload readPayload(ReconciliationTask task) {
        if (task.getTaskPayload() == null || task.getTaskPayload().isBlank()) {
            throw new IllegalStateException("follow_inbox task " + task.getId() + " missing payload");
        }
        try {
            return objectMapper.readValue(task.getTaskPayload(), FollowInboxPayload.class);
        } catch (Exception e) {
            throw new IllegalStateException("Invalid follow_inbox payload for task " + task.getId(), e);
        }
    }

    public record FollowInboxPayload(
            long postId,
            long authorId,
            String publishedAt,
            boolean largeAuthor
    ) {
    }
}
