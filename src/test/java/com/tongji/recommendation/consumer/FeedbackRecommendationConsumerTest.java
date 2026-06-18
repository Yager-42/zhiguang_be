package com.tongji.recommendation.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.comment.event.CommentFeedbackEvent;
import com.tongji.counter.event.CounterEvent;
import com.tongji.recommendation.gorse.GorseClient;
import com.tongji.recommendation.gorse.GorseProperties;
import com.tongji.relation.event.RelationEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class FeedbackRecommendationConsumerTest {

    @Mock
    private GorseClient gorseClient;
    @Mock
    private Acknowledgment acknowledgment;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private GorseProperties properties;

    @BeforeEach
    void setUp() {
        properties = new GorseProperties();
        properties.setEnabled(true);
    }

    @Test
    void counterLikeEventsBecomeGorseFeedback() throws Exception {
        CounterFeedbackRecommendationConsumer consumer =
                new CounterFeedbackRecommendationConsumer(objectMapper, gorseClient, properties);

        consumer.onMessage(objectMapper.writeValueAsString(
                CounterEvent.of("knowpost", "101", "like", 0, 7L, 1)), acknowledgment);

        verify(gorseClient).insertFeedback("like", 7L, "101");
        verify(acknowledgment).acknowledge();
    }

    @Test
    void commentEventsBecomeGorseFeedback() throws Exception {
        CommentFeedbackRecommendationConsumer consumer =
                new CommentFeedbackRecommendationConsumer(objectMapper, gorseClient, properties);

        consumer.onMessage(objectMapper.writeValueAsString(
                new CommentFeedbackEvent(11L, 101L, 0L, 0L, 7L, CommentFeedbackEvent.COMMENT)), acknowledgment);

        verify(gorseClient).insertFeedback("comment", 7L, "101");
        verify(acknowledgment).acknowledge();
    }

    @Test
    void followCreatedRowsBecomeGorseFeedback() {
        RelationFeedbackRecommendationConsumer consumer =
                new RelationFeedbackRecommendationConsumer(objectMapper, gorseClient, properties);

        consumer.onMessage(canalMessage(relationRow(new RelationEvent("FollowCreated", 7L, 9L, 123L))), acknowledgment);

        verify(gorseClient).insertFeedback("follow", 7L, "9");
        verify(acknowledgment).acknowledge();
    }

    @Test
    void nonMatchingFeedbackRowsAreIgnored() throws Exception {
        CounterFeedbackRecommendationConsumer counterConsumer =
                new CounterFeedbackRecommendationConsumer(objectMapper, gorseClient, properties);
        CommentFeedbackRecommendationConsumer commentConsumer =
                new CommentFeedbackRecommendationConsumer(objectMapper, gorseClient, properties);
        RelationFeedbackRecommendationConsumer relationConsumer =
                new RelationFeedbackRecommendationConsumer(objectMapper, gorseClient, properties);

        counterConsumer.onMessage(objectMapper.writeValueAsString(
                CounterEvent.of("comment", "101", "like", 0, 7L, 1)), acknowledgment);
        commentConsumer.onMessage(objectMapper.writeValueAsString(
                new CommentFeedbackEvent(11L, 101L, 0L, 0L, 7L, CommentFeedbackEvent.DELETE)), acknowledgment);
        relationConsumer.onMessage(canalMessage(relationRow(new RelationEvent("Blocked", 7L, 9L, 123L))), acknowledgment);

        verify(gorseClient, never()).insertFeedback(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString());
        verify(acknowledgment, times(3)).acknowledge();
    }

    @Test
    void malformedRelationRowDoesNotBlockValidRowsInSameBatch() {
        RelationFeedbackRecommendationConsumer consumer =
                new RelationFeedbackRecommendationConsumer(objectMapper, gorseClient, properties);

        consumer.onMessage(canalMessage("""
                {"payload":"not-json"},
                %s
                """.formatted(relationRow(new RelationEvent("FollowCreated", 7L, 9L, 123L)))), acknowledgment);

        verify(gorseClient).insertFeedback("follow", 7L, "9");
        verify(acknowledgment).acknowledge();
    }

    @Test
    void validRelationFailureLeavesBatchUnacked() {
        RelationFeedbackRecommendationConsumer consumer =
                new RelationFeedbackRecommendationConsumer(objectMapper, gorseClient, properties);
        doThrow(new RuntimeException("gorse down")).when(gorseClient).insertFeedback("follow", 7L, "9");

        consumer.onMessage(canalMessage(relationRow(new RelationEvent("FollowCreated", 7L, 9L, 123L))), acknowledgment);

        verify(gorseClient).insertFeedback("follow", 7L, "9");
        verify(acknowledgment, never()).acknowledge();
    }

    private String canalMessage(String row) {
        return """
                {"table":"outbox","type":"INSERT","data":[%s]}
                """.formatted(row);
    }

    private String relationRow(RelationEvent event) {
        try {
            return """
                    {"payload":"%s"}
                    """.formatted(objectMapper.writeValueAsString(event).replace("\"", "\\\""));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
