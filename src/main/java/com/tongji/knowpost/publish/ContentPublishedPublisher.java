package com.tongji.knowpost.publish;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.common.exception.BusinessException;
import com.tongji.common.exception.ErrorCode;
import com.tongji.common.id.IdNamespace;
import com.tongji.common.id.IdService;
import com.tongji.relation.outbox.OutboxMapper;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class ContentPublishedPublisher {

    private final OutboxMapper outboxMapper;
    private final IdService idService;
    private final ObjectMapper objectMapper;

    public ContentPublishedPublisher(OutboxMapper outboxMapper, IdService idService, ObjectMapper objectMapper) {
        this.outboxMapper = outboxMapper;
        this.idService = idService;
        this.objectMapper = objectMapper;
    }

    public void publish(ContentPublishedEvent event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventType", "content_published");
        payload.put("postId", event.postId());
        payload.put("authorId", event.authorId());
        payload.put("publishAttemptId", event.publishAttemptId());
        payload.put("publishedAt", event.publishedAt());
        writeOutbox("knowpost", event.postId(), "content_published", payload);
    }

    public void publishDerivedFailure(String taskType,
                                      String targetType,
                                      Long targetId,
                                      String failureReason,
                                      Instant nextRetryHint) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventType", "publish_derived_failure");
        payload.put("taskType", taskType);
        payload.put("targetType", targetType);
        payload.put("targetId", targetId);
        payload.put("failureReason", failureReason);
        payload.put("nextRetryHint", nextRetryHint);
        writeOutbox(targetType, targetId, "publish_derived_failure", payload);
    }

    private void writeOutbox(String aggregateType, Long aggregateId, String type, Map<String, Object> payload) {
        try {
            long outboxId = idService.nextId(IdNamespace.OUTBOX_EVENT);
            outboxMapper.insert(outboxId, aggregateType, aggregateId, type, objectMapper.writeValueAsString(payload));
        } catch (JsonProcessingException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "发布事件序列化失败");
        }
    }
}
