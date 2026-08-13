package com.tongji.profile.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.common.id.IdNamespace;
import com.tongji.common.id.IdService;
import com.tongji.outbox.OutboxMapper;
import org.springframework.stereotype.Component;

@Component
public class UserProfileUpdatedProducer {

    private final OutboxMapper outboxMapper;
    private final IdService idService;
    private final ObjectMapper objectMapper;

    public UserProfileUpdatedProducer(OutboxMapper outboxMapper,
                                      IdService idService,
                                      ObjectMapper objectMapper) {
        this.outboxMapper = outboxMapper;
        this.idService = idService;
        this.objectMapper = objectMapper;
    }

    public void publish(UserProfileUpdatedEvent event) {
        try {
            long outboxId = idService.nextId(IdNamespace.OUTBOX_EVENT);
            String payload = objectMapper.writeValueAsString(new UserProfileUpdatedOutboxEvent("user_profile_updated", event));
            outboxMapper.insert(outboxId, "user", event.userId(), "user_profile_updated", payload);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to persist user profile update event", exception);
        }
    }

    private record UserProfileUpdatedOutboxEvent(String eventType, UserProfileUpdatedEvent user) {
    }
}
