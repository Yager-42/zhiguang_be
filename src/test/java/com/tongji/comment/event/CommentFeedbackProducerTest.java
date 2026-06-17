package com.tongji.comment.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CommentFeedbackProducerTest {

    @Test
    void publishSwallowsSerializationFailure() throws Exception {
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
        ObjectMapper objectMapper = mock(ObjectMapper.class);
        when(objectMapper.writeValueAsString(any()))
                .thenThrow(new JsonProcessingException("bad json") {
                });
        CommentFeedbackProducer producer = new CommentFeedbackProducer(kafkaTemplate, objectMapper, "comment-feedback");

        assertThatCode(() -> producer.publish(event()))
                .doesNotThrowAnyException();
    }

    @Test
    void publishSwallowsSynchronousKafkaSendFailure() {
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("kafka down"));
        CommentFeedbackProducer producer = new CommentFeedbackProducer(kafkaTemplate, new ObjectMapper(), "comment-feedback");

        assertThatCode(() -> producer.publish(event()))
                .doesNotThrowAnyException();
    }

    private static CommentFeedbackEvent event() {
        return new CommentFeedbackEvent(101L, 9L, 0L, 0L, 7L, CommentFeedbackEvent.COMMENT);
    }
}
