package com.tongji.knowpost.publish;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.common.exception.BusinessException;
import com.tongji.common.exception.ErrorCode;
import com.tongji.common.id.IdNamespace;
import com.tongji.common.id.IdService;
import com.tongji.outbox.OutboxMapper;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 在发布状态事务内写入具有稳定业务键的 Outbox 事件。
 *
 * <p>调用方负责事务边界；唯一 {@code event_key} 保证同一 attempt/run 只产生一个目标事件。</p>
 *
 * @since 2026-08-28
 */
@Component
public class PublishOutboxWriter {

    static final String PUBLISH_REQUESTED = "publish_requested";
    static final String CONTENT_PUBLISHED = "content_published";

    private final OutboxMapper outboxMapper;
    private final IdService idService;
    private final ObjectMapper objectMapper;

    public PublishOutboxWriter(OutboxMapper outboxMapper, IdService idService, ObjectMapper objectMapper) {
        this.outboxMapper = outboxMapper;
        this.idService = idService;
        this.objectMapper = objectMapper;
    }

    /**
     * 写入当前执行轮次的发布请求。
     *
     * @param attempt 已持久化且包含正文快照的发布尝试
     */
    public void writeRequested(PublishAttempt attempt) {
        PublishRequestedEvent event = new PublishRequestedEvent(
                attempt.getAttemptId(),
                attempt.getPostId(),
                attempt.getCreatorId(),
                attempt.getRunVersion(),
                attempt.getContentObjectKeySnapshot(),
                attempt.getContentEtagSnapshot(),
                attempt.getContentSha256Snapshot(),
                attempt.getUpdatedAt()
        );
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventType", PUBLISH_REQUESTED);
        payload.put("attemptId", event.attemptId());
        payload.put("postId", event.postId());
        payload.put("authorId", event.authorId());
        payload.put("runVersion", event.runVersion());
        payload.put("contentObjectKey", event.contentObjectKey());
        payload.put("contentEtag", event.contentEtag());
        payload.put("contentSha256", event.contentSha256());
        payload.put("requestedAt", event.requestedAt());
        write(
                "publish-requested:" + event.attemptId() + ":" + event.runVersion(),
                PUBLISH_REQUESTED,
                event.postId(),
                payload
        );
    }

    /**
     * 写入当前执行轮次唯一的发布完成事实。
     *
     * @param event 已通过 attempt/post CAS 的发布事实
     */
    public void writeContentPublished(ContentPublishedEvent event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventType", CONTENT_PUBLISHED);
        payload.put("postId", event.postId());
        payload.put("authorId", event.authorId());
        payload.put("publishAttemptId", event.publishAttemptId());
        payload.put("runVersion", event.runVersion());
        payload.put("publishedAt", event.publishedAt());
        write(
                "content-published:" + event.publishAttemptId() + ":" + event.runVersion(),
                CONTENT_PUBLISHED,
                event.postId(),
                payload
        );
    }

    private void write(String eventKey, String type, long postId, Map<String, Object> payload) {
        try {
            outboxMapper.insertUnique(
                    idService.nextId(IdNamespace.OUTBOX_EVENT),
                    eventKey,
                    "knowpost",
                    postId,
                    type,
                    objectMapper.writeValueAsString(payload)
            );
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "发布事件序列化失败");
        }
    }
}
