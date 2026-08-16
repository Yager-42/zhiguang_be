package com.tongji.recommendation.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.knowpost.mapper.KnowPostMapper;
import com.tongji.knowpost.model.KnowPost;
import com.tongji.reconciliation.model.ReconciliationTargetType;
import com.tongji.reconciliation.model.ReconciliationTaskType;
import com.tongji.reconciliation.service.ReconciliationService;
import com.tongji.recommendation.gorse.GorseClient;
import com.tongji.recommendation.gorse.GorseItemInput;
import com.tongji.recommendation.gorse.GorseItemInputFactory;
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
    @Mock
    private KnowPostMapper knowPostMapper;

    private GorseProperties properties;
    private ContentPublishedRecommendationConsumer consumer;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        properties = new GorseProperties();
        properties.setEnabled(true);
        consumer = new ContentPublishedRecommendationConsumer(
                objectMapper,
                gorseClient,
                properties,
                taskExecutor,
                reconciliationService,
                knowPostMapper,
                new GorseItemInputFactory(objectMapper)
        );
    }

    @Test
    void contentPublishedRowsAreUpsertedAsync() {
        Instant publishedAt = Instant.parse("2026-06-18T10:15:30Z");
        org.mockito.Mockito.when(knowPostMapper.findById(101L)).thenReturn(post(101L, 7L, publishedAt));
        consumer.onMessage(canalMessage(contentPublishedRow(101L, 7L, Instant.parse("2026-06-18T10:15:30Z"))), acknowledgment);

        ArgumentCaptor<Runnable> taskCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(taskExecutor).execute(taskCaptor.capture());
        verify(acknowledgment, never()).acknowledge();

        taskCaptor.getValue().run();

        verify(gorseClient).upsertItem(item(101L, 7L, publishedAt));
        verify(acknowledgment).acknowledge();
    }

    @Test
    void consumerCreatesReconciliationTaskAndAcknowledgesWhenAsyncUpsertFails() {
        Instant publishedAt = Instant.parse("2026-06-18T10:15:30Z");
        org.mockito.Mockito.when(knowPostMapper.findById(101L)).thenReturn(post(101L, 7L, publishedAt));
        doThrow(new RuntimeException("gorse down")).when(gorseClient)
                .upsertItem(item(101L, 7L, publishedAt));

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
        Instant firstPublishedAt = Instant.parse("2026-06-18T10:15:30Z");
        Instant secondPublishedAt = Instant.parse("2026-06-18T10:16:30Z");
        org.mockito.Mockito.when(knowPostMapper.findById(101L)).thenReturn(post(101L, 7L, firstPublishedAt));
        org.mockito.Mockito.when(knowPostMapper.findById(102L)).thenReturn(post(102L, 8L, secondPublishedAt));
        doThrow(new RuntimeException("gorse down")).when(gorseClient)
                .upsertItem(item(102L, 8L, secondPublishedAt));

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

        verify(gorseClient).upsertItem(item(101L, 7L, firstPublishedAt));
        verify(gorseClient).upsertItem(item(102L, 8L, secondPublishedAt));
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

    private KnowPost post(long postId, long authorId, Instant publishedAt) {
        return KnowPost.builder()
                .id(postId)
                .creatorId(authorId)
                .publishTime(publishedAt)
                .title("测试知文")
                .tags("[\"Java\",\"Spring\"]")
                .status("published")
                .build();
    }

    private GorseItemInput item(long postId, long authorId, Instant publishedAt) {
        return new GorseItemInput(postId, authorId, publishedAt, "测试知文", java.util.List.of("Java", "Spring"));
    }
}
