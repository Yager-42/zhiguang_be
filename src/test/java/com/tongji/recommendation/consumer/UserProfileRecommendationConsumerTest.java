package com.tongji.recommendation.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.profile.event.UserProfileUpdatedEvent;
import com.tongji.recommendation.gorse.GorseClient;
import com.tongji.recommendation.gorse.GorseProperties;
import com.tongji.relation.outbox.OutboxTopics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;

import java.time.LocalDate;
import java.util.Map;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class UserProfileRecommendationConsumerTest {

    @Mock
    private GorseClient gorseClient;
    @Mock
    private Acknowledgment acknowledgment;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private GorseProperties properties;
    private UserProfileRecommendationConsumer consumer;

    @BeforeEach
    void setUp() {
        properties = new GorseProperties();
        properties.setEnabled(true);
        consumer = new UserProfileRecommendationConsumer(objectMapper, gorseClient, properties);
    }

    @Test
    void profileEventsUpsertUsersInGorse() throws Exception {
        UserProfileUpdatedEvent event = new UserProfileUpdatedEvent(
                7L, "neo", "https://img", "bio", "zg007", "MALE",
                LocalDate.parse("2000-01-02"), "Tongji", "13800000000",
                "neo@example.com", "[\"ai\"]");

        consumer.onMessage(canalMessage(event), acknowledgment);

        verify(gorseClient).upsertUser(event);
        verify(acknowledgment).acknowledge();
    }

    @Test
    void disabledConsumerAcknowledgesWithoutCallingGorse() throws Exception {
        properties.setEnabled(false);

        consumer.onMessage(canalMessage(new UserProfileUpdatedEvent(
                7L, "neo", null, null, null, null, null, null, null, null, null
        )), acknowledgment);

        verify(gorseClient, never()).upsertUser(org.mockito.ArgumentMatchers.any());
        verify(acknowledgment).acknowledge();
    }

    @Test
    void malformedMixedBatchDoesNotBlockValidUserProfileRow() throws Exception {
        UserProfileUpdatedEvent event = new UserProfileUpdatedEvent(
                7L, "neo", "https://img", "bio", "zg007", "MALE",
                LocalDate.parse("2000-01-02"), "Tongji", "13800000000",
                "neo@example.com", "[\"ai\"]");

        consumer.onMessage(objectMapper.writeValueAsString(Map.of(
                "table", "outbox",
                "type", "INSERT",
                "data", java.util.List.of(
                        Map.of("payload", "not-json"),
                        Map.of("payload", objectMapper.writeValueAsString(Map.of(
                                "eventType", "user_profile_updated",
                                "user", event
                        )))
                )
        )), acknowledgment);

        verify(gorseClient).upsertUser(event);
        verify(acknowledgment).acknowledge();
    }

    @Test
    void validUserProfileFailureLeavesBatchUnacked() throws Exception {
        UserProfileUpdatedEvent event = new UserProfileUpdatedEvent(
                7L, "neo", "https://img", "bio", "zg007", "MALE",
                LocalDate.parse("2000-01-02"), "Tongji", "13800000000",
                "neo@example.com", "[\"ai\"]");
        doThrow(new RuntimeException("gorse down")).when(gorseClient).upsertUser(event);

        consumer.onMessage(canalMessage(event), acknowledgment);

        verify(gorseClient).upsertUser(event);
        verify(acknowledgment, never()).acknowledge();
    }

    private String canalMessage(UserProfileUpdatedEvent event) throws Exception {
        String payload = objectMapper.writeValueAsString(Map.of(
                "eventType", "user_profile_updated",
                "user", event
        ));
        return objectMapper.writeValueAsString(Map.of(
                "table", "outbox",
                "type", "INSERT",
                "data", java.util.List.of(Map.of("payload", payload))
        ));
    }
}
