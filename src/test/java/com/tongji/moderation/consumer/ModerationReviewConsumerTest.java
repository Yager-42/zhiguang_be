package com.tongji.moderation.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.moderation.service.ModerationReviewExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.Acknowledgment;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class ModerationReviewConsumerTest {

    private ModerationReviewExecutor executor;
    private Acknowledgment acknowledgment;
    private ModerationReviewConsumer consumer;

    @BeforeEach
    void setUp() {
        executor = mock(ModerationReviewExecutor.class);
        acknowledgment = mock(Acknowledgment.class);
        consumer = new ModerationReviewConsumer(new ObjectMapper(), executor);
    }

    @Test
    void validReviewRequestedPayloadCallsExecutorAndAcknowledges() {
        consumer.onMessage(canalMessage("""
                {"payload":"{\\"entity\\":\\"moderation_report\\",\\"op\\":\\"review_requested\\",\\"reportId\\":21,\\"targetType\\":\\"post\\",\\"targetId\\":101}"}
                """), acknowledgment);

        verify(executor).review(21L);
        verify(acknowledgment).acknowledge();
    }

    @Test
    void unrelatedOutboxPayloadIsAcknowledgedAndSkipped() {
        consumer.onMessage(canalMessage("""
                {"payload":"{\\"entity\\":\\"knowpost\\",\\"op\\":\\"upsert\\",\\"id\\":101}"}
                """), acknowledgment);

        verifyNoInteractions(executor);
        verify(acknowledgment).acknowledge();
    }

    @Test
    void malformedPayloadIsAcknowledgedAndSkipped() {
        consumer.onMessage(canalMessage("""
                {"payload":"not-json"}
                """), acknowledgment);

        verifyNoInteractions(executor);
        verify(acknowledgment).acknowledge();
    }

    @Test
    void executorFailurePropagatesWithoutAcknowledging() {
        doThrow(new IllegalStateException("db down")).when(executor).review(21L);

        assertThatThrownBy(() -> consumer.onMessage(canalMessage("""
                {"payload":"{\\"entity\\":\\"moderation_report\\",\\"op\\":\\"review_requested\\",\\"reportId\\":21}"}
                """), acknowledgment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("db down");

        verify(acknowledgment, never()).acknowledge();
    }

    @Test
    void batchExecutorFailureStopsBatchBeforePropagating() {
        doThrow(new IllegalStateException("db down")).when(executor).review(21L);

        assertThatThrownBy(() -> consumer.onMessage(canalMessage("""
                {"payload":"{\\"entity\\":\\"moderation_report\\",\\"op\\":\\"review_requested\\",\\"reportId\\":21}"},
                {"payload":"{\\"entity\\":\\"moderation_report\\",\\"op\\":\\"review_requested\\",\\"reportId\\":22}"}
                """), acknowledgment))
                .isInstanceOf(IllegalStateException.class);

        verify(executor).review(21L);
        verify(executor, never()).review(22L);
        verify(acknowledgment, never()).acknowledge();
    }

    private String canalMessage(String row) {
        return """
                {"table":"outbox","type":"INSERT","data":[%s]}
                """.formatted(row);
    }
}
