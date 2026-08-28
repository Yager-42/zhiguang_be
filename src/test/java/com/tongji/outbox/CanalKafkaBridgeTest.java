package com.tongji.outbox;

import com.alibaba.otter.canal.client.CanalConnector;
import com.alibaba.otter.canal.protocol.Message;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.SyncTaskExecutor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;

class CanalKafkaBridgeTest {

    @Test
    void acknowledgesOnlyAfterPublisherSucceeds() throws Exception {
        CanalOutboxBatchPublisher publisher = mock(CanalOutboxBatchPublisher.class);
        CanalConnector connector = mock(CanalConnector.class);
        Message message = new Message(11L, List.of());
        CanalKafkaBridge bridge = bridge(publisher);

        assertThat(bridge.processBatch(connector, message)).isTrue();

        verify(publisher).publish(message);
        verify(connector).ack(11L);
        verify(connector, never()).rollback(11L);
    }

    @Test
    void rollsBackAndNeverAcknowledgesWhenPublisherFails() throws Exception {
        CanalOutboxBatchPublisher publisher = mock(CanalOutboxBatchPublisher.class);
        CanalConnector connector = mock(CanalConnector.class);
        Message message = new Message(12L, List.of());
        doThrow(new IllegalStateException("broker unavailable")).when(publisher).publish(message);
        CanalKafkaBridge bridge = bridge(publisher);

        assertThat(bridge.processBatch(connector, message)).isFalse();

        verify(connector).rollback(12L);
        verify(connector, never()).ack(12L);
    }

    @Test
    void retriesTheSameBatchAfterRollbackAndAcknowledgesOnlyTheSuccessfulAttempt() throws Exception {
        CanalOutboxBatchPublisher publisher = mock(CanalOutboxBatchPublisher.class);
        CanalConnector connector = mock(CanalConnector.class);
        Message message = new Message(13L, List.of());
        doThrow(new IllegalStateException("broker unavailable"))
                .doNothing()
                .when(publisher).publish(message);
        CanalKafkaBridge bridge = bridge(publisher);

        assertThat(bridge.processBatch(connector, message)).isFalse();
        assertThat(bridge.processBatch(connector, message)).isTrue();

        verify(publisher, times(2)).publish(message);
        verify(connector).rollback(13L);
        verify(connector).ack(13L);
    }

    @Test
    void retryDelayUsesBoundedExponentialFullJitter() {
        CanalKafkaBridge bridge = bridge(mock(CanalOutboxBatchPublisher.class));

        assertDelayWithinCap(bridge, 1, 100L);
        assertDelayWithinCap(bridge, 2, 200L);
        assertDelayWithinCap(bridge, 3, 400L);
        assertDelayWithinCap(bridge, 4, 800L);
        assertDelayWithinCap(bridge, 20, 800L);
    }

    private static void assertDelayWithinCap(CanalKafkaBridge bridge, int failures, long cap) {
        for (int sample = 0; sample < 100; sample++) {
            assertThat(bridge.batchRetryDelayMs(failures)).isBetween(1L, cap);
        }
    }

    private static CanalKafkaBridge bridge(CanalOutboxBatchPublisher publisher) {
        return new CanalKafkaBridge(
                publisher,
                new SyncTaskExecutor(),
                false,
                "127.0.0.1",
                11111,
                "example",
                "",
                "",
                "outbox",
                100,
                0L,
                5_000L,
                100L,
                800L
        );
    }
}
