package com.tongji.recommendation.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.llm.rag.RagIndexService;
import com.tongji.reconciliation.model.ReconciliationTargetType;
import com.tongji.reconciliation.model.ReconciliationTaskType;
import com.tongji.reconciliation.service.ReconciliationService;
import com.tongji.relation.outbox.OutboxTopics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ContentPublishedRagConsumerTest {

    @Mock
    private RagIndexService ragIndexService;
    @Mock
    private ReconciliationService reconciliationService;
    @Mock
    private Acknowledgment acknowledgment;

    private ContentPublishedRagConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new ContentPublishedRagConsumer(new ObjectMapper(), ragIndexService, reconciliationService);
    }

    @Test
    void ragFailureCreatesReconciliationTaskAndAcknowledges() {
        doThrow(new IllegalStateException("rag down")).when(ragIndexService).ensureIndexedStrict(101L);

        consumer.onMessage(canalMessage(contentPublishedRow(101L, 7L)), acknowledgment);

        verify(ragIndexService).ensureIndexedStrict(101L);
        verify(reconciliationService).createTaskIfAbsent(
                ReconciliationTaskType.RAG_INDEX,
                ReconciliationTargetType.POST,
                101L
        );
        verify(acknowledgment).acknowledge();
    }

    private String canalMessage(String row) {
        return """
                {"table":"outbox","type":"INSERT","data":[%s]}
                """.formatted(row);
    }

    private String contentPublishedRow(long postId, long authorId) {
        return """
                {"payload":"{\\"eventType\\":\\"content_published\\",\\"postId\\":%d,\\"authorId\\":%d,\\"publishedAt\\":\\"2026-06-18T10:15:30Z\\"}"}
                """.formatted(postId, authorId);
    }
}
