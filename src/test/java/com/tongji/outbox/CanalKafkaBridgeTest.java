package com.tongji.outbox;

import com.alibaba.otter.canal.client.CanalConnector;
import com.alibaba.otter.canal.protocol.Message;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.SyncTaskExecutor;

import java.util.List;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class CanalKafkaBridgeTest {

    @Test
    void acknowledgesOnlyAfterPublisherSucceeds() throws Exception {
        CanalOutboxBatchPublisher publisher = mock(CanalOutboxBatchPublisher.class);
        CanalConnector connector = mock(CanalConnector.class);
        Message message = new Message(11L, List.of());
        CanalKafkaBridge bridge = bridge(publisher);

        bridge.processBatch(connector, message);

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

        bridge.processBatch(connector, message);

        verify(connector).rollback(12L);
        verify(connector, never()).ack(12L);
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
                0L
        );
    }
}
