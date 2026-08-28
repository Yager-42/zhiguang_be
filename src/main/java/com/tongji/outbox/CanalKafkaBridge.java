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
import java.util.concurrent.ThreadLocalRandom;

/**
 * 管理 Canal 连接，并仅在整批 Outbox 变化全部被 Kafka 接受后确认位点。
 *
 * <p>批次失败先 rollback，再执行有上限的指数退避与 full jitter；成功确认后重置连续失败次数。</p>
 *
 * @since 2026-08-28
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
    private final long reconnectDelayMs;
    private final long batchRetryInitialMs;
    private final long batchRetryMaxMs;

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
                            @Value("${canal.intervalMs}") long intervalMs,
                            @Value("${canal.reconnectDelayMs:5000}") long reconnectDelayMs,
                            @Value("${canal.batch-retry-initial-ms:250}") long batchRetryInitialMs,
                            @Value("${canal.batch-retry-max-ms:30000}") long batchRetryMaxMs) {
        if (batchRetryInitialMs <= 0L || batchRetryMaxMs < batchRetryInitialMs) {
            throw new IllegalArgumentException("Canal batch retry range is invalid");
        }
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
        this.reconnectDelayMs = reconnectDelayMs;
        this.batchRetryInitialMs = batchRetryInitialMs;
        this.batchRetryMaxMs = batchRetryMaxMs;
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
            while (running) {
                try {
                    connector = createConnector();
                    connector.connect();
                    connector.subscribe(filter);
                    connector.rollback();
                    log.info("Canal bridge connected: destination={} filter={}", destination, filter);
                    consume();
                } catch (Exception exception) {
                    if (running) {
                        log.error("Canal bridge error, reconnecting in {} ms", reconnectDelayMs, exception);
                    }
                } finally {
                    disconnect();
                    connector = null;
                }
                if (running) {
                    sleep(reconnectDelayMs);
                }
            }
        } finally {
            running = false;
        }
    }

    private void consume() throws Exception {
        int consecutiveFailures = 0;
        while (running) {
            Message message = connector.getWithoutAck(batchSize);
            long batchId = message.getId();
            if (batchId == -1 || message.getEntries() == null || message.getEntries().isEmpty()) {
                sleep(intervalMs);
                continue;
            }
            if (processBatch(connector, message)) {
                consecutiveFailures = 0;
                continue;
            }
            consecutiveFailures = Math.min(consecutiveFailures + 1, 63);
            sleep(batchRetryDelayMs(consecutiveFailures));
        }
    }

    boolean processBatch(CanalConnector connector, Message message) {
        long batchId = message.getId();
        try {
            batchPublisher.publish(message);
            connector.ack(batchId);
            return true;
        } catch (Exception exception) {
            connector.rollback(batchId);
            log.error("Canal outbox batch failed and was rolled back: batchId={}", batchId, exception);
            return false;
        }
    }

    long batchRetryDelayMs(int consecutiveFailures) {
        if (consecutiveFailures <= 0) {
            throw new IllegalArgumentException("consecutiveFailures must be positive");
        }
        long cap = batchRetryInitialMs;
        for (int failure = 1; failure < consecutiveFailures && cap < batchRetryMaxMs; failure++) {
            cap = cap > batchRetryMaxMs / 2L ? batchRetryMaxMs : cap * 2L;
        }
        return ThreadLocalRandom.current().nextLong(cap) + 1L;
    }

    CanalConnector createConnector() {
        return CanalConnectors.newSingleConnector(
                new InetSocketAddress(host, port),
                destination,
                username,
                password
        );
    }

    private void sleep(long delayMs) {
        try {
            Thread.sleep(delayMs);
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
