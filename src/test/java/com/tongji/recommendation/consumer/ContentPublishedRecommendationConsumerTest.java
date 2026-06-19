package com.tongji.recommendation.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.reconciliation.model.ReconciliationTargetType;
import com.tongji.reconciliation.model.ReconciliationTaskType;
import com.tongji.reconciliation.service.ReconciliationService;
import com.tongji.recommendation.gorse.GorseClient;
import com.tongji.recommendation.gorse.GorseProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.TaskExecutor;
import org.springframework.kafka.support.Acknowledgment;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ContentPublishedRecommendationConsumerTest {

    @Mock
    private GorseClient gorseClient;
    @Mock
    private TaskExecutor taskExecutor;
    @Mock
    private Acknowledgment acknowledgment;
    @Mock
    private ReconciliationService reconciliationService;

    private GorseProperties properties;
    private ContentPublishedRecommendationConsumer consumer;

    @BeforeEach
    void setUp() {
        properties = new GorseProperties();
        properties.setEnabled(true);
        consumer = new ContentPublishedRecommendationConsumer(
                new ObjectMapper(),
                gorseClient,
                properties,
                taskExecutor,
                reconciliationService
        );
    }

    @Test
    void contentPublishedRowsAreUpsertedAsync() {
        consumer.onMessage(canalMessage(contentPublishedRow(101L, 7L, Instant.parse("2026-06-18T10:15:30Z"))), acknowledgment);

        ArgumentCaptor<Runnable> taskCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(taskExecutor).execute(taskCaptor.capture());
        verify(acknowledgment, never()).acknowledge();

        taskCaptor.getValue().run();

        verify(gorseClient).upsertItem(101L, 7L, Instant.parse("2026-06-18T10:15:30Z"));
        verify(acknowledgment).acknowledge();
    }

    @Test
    void consumerCreatesReconciliationTaskAndAcknowledgesWhenAsyncUpsertFails() {
        doThrow(new RuntimeException("gorse down")).when(gorseClient)
                .upsertItem(101L, 7L, Instant.parse("2026-06-18T10:15:30Z"));

        consumer.onMessage(canalMessage(contentPublishedRow(101L, 7L, Instant.parse("2026-06-18T10:15:30Z"))), acknowledgment);

        ArgumentCaptor<Runnable> taskCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(taskExecutor).execute(taskCaptor.capture());

        taskCaptor.getValue().run();

        verify(reconciliationService).createTaskIfAbsent(
                ReconciliationTaskType.GORSE_ITEM_UPSERT,
                ReconciliationTargetType.POST,
                101L
        );
        verify(acknowledgment).acknowledge();
    }

    @Test
    void disabledGorseAcknowledgesWithoutSchedulingWork() {
        properties.setEnabled(false);

        consumer.onMessage(canalMessage(contentPublishedRow(101L, 7L, Instant.parse("2026-06-18T10:15:30Z"))), acknowledgment);

        verify(taskExecutor, never()).execute(any());
        verify(acknowledgment).acknowledge();
    }

    @Test
    void consumerCreatesTaskOnlyForFailedItemInBatch() {
        doThrow(new RuntimeException("gorse down")).when(gorseClient)
                .upsertItem(102L, 8L, Instant.parse("2026-06-18T10:16:30Z"));

        consumer.onMessage(canalMessage("""
                %s,
                %s
                """.formatted(
                contentPublishedRow(101L, 7L, Instant.parse("2026-06-18T10:15:30Z")),
                contentPublishedRow(102L, 8L, Instant.parse("2026-06-18T10:16:30Z"))
        )), acknowledgment);

        ArgumentCaptor<Runnable> taskCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(taskExecutor).execute(taskCaptor.capture());
        taskCaptor.getValue().run();

        verify(gorseClient).upsertItem(101L, 7L, Instant.parse("2026-06-18T10:15:30Z"));
        verify(gorseClient).upsertItem(102L, 8L, Instant.parse("2026-06-18T10:16:30Z"));
        verify(reconciliationService).createTaskIfAbsent(
                ReconciliationTaskType.GORSE_ITEM_UPSERT,
                ReconciliationTargetType.POST,
                102L
        );
        verify(acknowledgment).acknowledge();
    }

    private String canalMessage(String row) {
        return """
                {"table":"outbox","type":"INSERT","data":[%s]}
                """.formatted(row);
    }

    private String contentPublishedRow(long postId, long authorId, Instant publishedAt) {
        return """
                {"payload":"{\\"eventType\\":\\"content_published\\",\\"postId\\":%d,\\"authorId\\":%d,\\"publishedAt\\":\\"%s\\"}"}
                """.formatted(postId, authorId, publishedAt);
    }
}
