package com.tongji.search.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.reconciliation.model.ReconciliationTargetType;
import com.tongji.reconciliation.model.ReconciliationTaskType;
import com.tongji.reconciliation.service.ReconciliationService;
import com.tongji.search.index.SearchIndexService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class CanalOutboxConsumerSearchTest {

    @Mock
    private SearchIndexService searchIndexService;
    @Mock
    private ReconciliationService reconciliationService;
    @Mock
    private Acknowledgment acknowledgment;

    private CanalOutboxConsumerSearch consumer;

    @BeforeEach
    void setUp() {
        consumer = new CanalOutboxConsumerSearch(new ObjectMapper(), searchIndexService, reconciliationService);
    }

    @Test
    void contentPublishedFailureCreatesReconciliationTaskAndAcknowledges() {
        doThrow(new IllegalStateException("es down")).when(searchIndexService).upsertKnowPostStrict(101L);

        consumer.onMessage(canalMessage(contentPublishedRow(101L, 7L)), acknowledgment);

        verify(searchIndexService).upsertKnowPostStrict(101L);
        verify(reconciliationService).createTaskIfAbsent(
                ReconciliationTaskType.ES_INDEX,
                ReconciliationTargetType.POST,
                101L
        );
        verify(acknowledgment).acknowledge();
    }

    @Test
    void metadataUpsertStillUsesLegacyPath() {
        consumer.onMessage(canalMessage(metadataUpsertRow(202L)), acknowledgment);

        verify(searchIndexService).upsertKnowPost(202L);
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

    private String metadataUpsertRow(long postId) {
        return """
                {"payload":"{\\"entity\\":\\"knowpost\\",\\"op\\":\\"upsert\\",\\"id\\":%d}"}
                """.formatted(postId);
    }
}
