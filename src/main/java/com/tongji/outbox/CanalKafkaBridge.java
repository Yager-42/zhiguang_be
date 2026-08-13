package com.tongji.outbox;

import com.alibaba.otter.canal.client.CanalConnector;
import com.alibaba.otter.canal.client.CanalConnectors;
import com.alibaba.otter.canal.protocol.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

import java.net.InetSocketAddress;

/**
 * Owns the Canal connection lifecycle and acknowledges a batch only after Kafka accepts every
 * relevant outbox change in that batch.
 */
@Service
public class CanalKafkaBridge implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(CanalKafkaBridge.class);

    private final CanalOutboxBatchPublisher batchPublisher;
    private final TaskExecutor taskExecutor;
    private final boolean enabled;
    private final String host;
    private final int port;
    private final String destination;
    private final String username;
    private final String password;
    private final String filter;
    private final int batchSize;
    private final long intervalMs;

    private volatile boolean running;
    private volatile CanalConnector connector;

    public CanalKafkaBridge(CanalOutboxBatchPublisher batchPublisher,
                            @Qualifier("canalOutboxExecutor") TaskExecutor taskExecutor,
                            @Value("${canal.enabled}") boolean enabled,
                            @Value("${canal.host}") String host,
                            @Value("${canal.port}") int port,
                            @Value("${canal.destination}") String destination,
                            @Value("${canal.username}") String username,
                            @Value("${canal.password}") String password,
                            @Value("${canal.filter}") String filter,
                            @Value("${canal.batchSize}") int batchSize,
                            @Value("${canal.intervalMs}") long intervalMs) {
        this.batchPublisher = batchPublisher;
        this.taskExecutor = taskExecutor;
        this.enabled = enabled;
        this.host = host;
        this.port = port;
        this.destination = destination;
        this.username = username;
        this.password = password;
        this.filter = filter;
        this.batchSize = batchSize;
        this.intervalMs = intervalMs;
    }

    @Override
    public void start() {
        if (!enabled || running) {
            return;
        }
        running = true;
        taskExecutor.execute(this::runLoop);
    }

    void runLoop() {
        try {
            connector = createConnector();
            connector.connect();
            connector.subscribe(filter);
            connector.rollback();
            while (running) {
                Message message = connector.getWithoutAck(batchSize);
                long batchId = message.getId();
                if (batchId == -1 || message.getEntries() == null || message.getEntries().isEmpty()) {
                    sleep();
                    continue;
                }
                processBatch(connector, message);
            }
        } catch (Exception exception) {
            log.error("Canal bridge error", exception);
        } finally {
            disconnect();
            running = false;
        }
    }

    void processBatch(CanalConnector connector, Message message) {
        long batchId = message.getId();
        try {
            batchPublisher.publish(message);
            connector.ack(batchId);
        } catch (Exception exception) {
            connector.rollback(batchId);
            log.error("Canal outbox batch failed and was rolled back: batchId={}", batchId, exception);
        }
    }

    CanalConnector createConnector() {
        return CanalConnectors.newSingleConnector(
                new InetSocketAddress(host, port),
                destination,
                username,
                password
        );
    }

    private void sleep() {
        try {
            Thread.sleep(intervalMs);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            running = false;
        }
    }

    private void disconnect() {
        CanalConnector current = connector;
        if (current == null) {
            return;
        }
        try {
            current.disconnect();
        } catch (Exception exception) {
            log.warn("Canal disconnect failed: destination={} error={}", destination, exception.getMessage());
        }
    }

    @Override
    public void stop() {
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
