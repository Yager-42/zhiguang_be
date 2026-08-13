package com.tongji.relation.manager;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.common.id.IdNamespace;
import com.tongji.common.id.IdService;
import com.tongji.relation.event.RelationEvent;
import com.tongji.outbox.OutboxMapper;
import org.springframework.stereotype.Component;

@Component
public class RelationPublisher {

    private final ObjectMapper objectMapper;
    private final OutboxMapper outboxMapper;
    private final IdService idService;

    public RelationPublisher(ObjectMapper objectMapper, OutboxMapper outboxMapper, IdService idService) {
        this.objectMapper = objectMapper;
        this.outboxMapper = outboxMapper;
        this.idService = idService;
    }

    public void publishFollowCreated(long fromUserId, long toUserId, long relationId) {
        publish("FollowCreated", fromUserId, toUserId, relationId, relationId);
    }

    public void publishFollowCanceled(long fromUserId, long toUserId) {
        publish("FollowCanceled", fromUserId, toUserId, null, null);
    }

    private void publish(String type, long fromUserId, long toUserId, Long relationId, Long aggregateId) {
        long outboxId = idService.nextId(IdNamespace.OUTBOX_EVENT);
        String payload;
        try {
            payload = objectMapper.writeValueAsString(new RelationEvent(type, fromUserId, toUserId, relationId));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize relation event", e);
        }
        outboxMapper.insert(outboxId, "following", aggregateId, type, payload);
    }
}
