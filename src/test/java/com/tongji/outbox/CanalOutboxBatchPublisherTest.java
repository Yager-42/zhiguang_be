package com.tongji.outbox;

import com.alibaba.otter.canal.protocol.CanalEntry;
import com.alibaba.otter.canal.protocol.Message;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.ByteString;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CanalOutboxBatchPublisherTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @SuppressWarnings("unchecked")
    void publishesCompleteOutboxRowAndWaitsForKafka() throws Exception {
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        CompletableFuture<SendResult<String, String>> future = CompletableFuture.completedFuture(mock(SendResult.class));
        when(kafka.send(org.mockito.ArgumentMatchers.eq(OutboxTopics.CANAL_OUTBOX), org.mockito.ArgumentMatchers.anyString())).thenReturn(future);
        CanalOutboxBatchPublisher publisher = new CanalOutboxBatchPublisher(kafka, objectMapper, 1_000L);

        publisher.publish(message(column("id", "77"),
                column("aggregate_type", "following"),
                column("aggregate_id", "9"),
                column("type", "FollowCreated"),
                column("payload", "{\"type\":\"FollowCreated\"}"),
                column("created_at", "2026-06-18 10:15:30.000")));

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(kafka).send(org.mockito.ArgumentMatchers.eq(OutboxTopics.CANAL_OUTBOX), json.capture());
        JsonNode row = objectMapper.readTree(json.getValue()).path("data").get(0);
        assertThat(row.path("id").asText()).isEqualTo("77");
        assertThat(row.path("aggregate_type").asText()).isEqualTo("following");
        assertThat(row.path("aggregate_id").asText()).isEqualTo("9");
        assertThat(row.path("type").asText()).isEqualTo("FollowCreated");
        assertThat(row.path("payload").asText()).isEqualTo("{\"type\":\"FollowCreated\"}");
    }

    @Test
    @SuppressWarnings("unchecked")
    void propagatesKafkaFailureSoCallerCannotAcknowledgeBatch() {
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        CompletableFuture<SendResult<String, String>> future = new CompletableFuture<>();
        future.completeExceptionally(new IllegalStateException("broker unavailable"));
        when(kafka.send(org.mockito.ArgumentMatchers.eq(OutboxTopics.CANAL_OUTBOX), org.mockito.ArgumentMatchers.anyString())).thenReturn(future);
        CanalOutboxBatchPublisher publisher = new CanalOutboxBatchPublisher(kafka, objectMapper, 1_000L);

        assertThatThrownBy(() -> publisher.publish(message(column("payload", "{}"))))
                .hasRootCauseMessage("broker unavailable");
    }

    private static Message message(CanalEntry.Column... columns) {
        CanalEntry.RowData row = CanalEntry.RowData.newBuilder()
                .addAllAfterColumns(List.of(columns))
                .build();
        CanalEntry.RowChange change = CanalEntry.RowChange.newBuilder()
                .setEventType(CanalEntry.EventType.INSERT)
                .addRowDatas(row)
                .build();
        CanalEntry.Entry entry = CanalEntry.Entry.newBuilder()
                .setEntryType(CanalEntry.EntryType.ROWDATA)
                .setHeader(CanalEntry.Header.newBuilder().setTableName("outbox").build())
                .setStoreValue(ByteString.copyFrom(change.toByteArray()))
                .build();
        return new Message(11L, List.of(entry));
    }

    private static CanalEntry.Column column(String name, String value) {
        return CanalEntry.Column.newBuilder().setName(name).setValue(value).build();
    }
}
