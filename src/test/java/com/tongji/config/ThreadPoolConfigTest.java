package com.tongji.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.relation.event.RelationEvent;
import com.tongji.outbox.CanalKafkaBridge;
import com.tongji.outbox.CanalOutboxBatchPublisher;
import com.tongji.outbox.OutboxMessageReader;
import com.tongji.relation.consumer.CanalOutboxConsumer;
import com.tongji.relation.processor.RelationEventProcessor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.task.TaskExecutor;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ThreadPoolConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(ThreadPoolConfig.class, CanalKafkaBridge.class, CanalOutboxBatchPublisher.class, OutboxMessageReader.class, CanalOutboxConsumer.class)
            .withBean(ProducerFactory.class, () -> new DefaultKafkaProducerFactory<String, String>(Map.of()))
            .withBean(KafkaTemplate.class, () -> new KafkaTemplate<>(mock(ProducerFactory.class)))
            .withBean(RelationEventProcessor.class, NoOpRelationEventProcessor::new)
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withBean(com.tongji.promotion.bprime.config.PromotionBPrimeProperties.class,
                    com.tongji.promotion.bprime.config.PromotionBPrimeProperties::new)
            .withPropertyValues(
                    "canal.enabled=false",
                    "canal.host=127.0.0.1",
                    "canal.port=11111",
                    "canal.destination=example",
                    "canal.username=",
                    "canal.password=",
                    "canal.filter=.*\\\\..*",
                    "canal.batchSize=100",
                    "canal.intervalMs=1000"
            );

    @Test
    void registersIsolatedExecutorsAndWiresCanalBridgeToCanalExecutor() {
        contextRunner.run(context -> {
            TaskExecutor canalExecutor = context.getBean("canalOutboxExecutor", TaskExecutor.class);
            TaskExecutor relationEventExecutor = context.getBean("relationEventExecutor", TaskExecutor.class);
            CanalKafkaBridge bridge = context.getBean(CanalKafkaBridge.class);
            CanalOutboxConsumer consumer = context.getBean(CanalOutboxConsumer.class);

            assertExecutor(context.getBean("taskExecutor", ThreadPoolTaskExecutor.class),
                    "task-", 10, 50, 200, ThreadPoolExecutor.CallerRunsPolicy.class, true, 60);
            assertExecutor(context.getBean("publishExecutor", ThreadPoolTaskExecutor.class),
                    "publish-", 8, 16, 100, ThreadPoolExecutor.CallerRunsPolicy.class, true, 60);
            assertExecutor(context.getBean("relationEventExecutor", ThreadPoolTaskExecutor.class),
                    "relation-event-", 4, 8, 200, ThreadPoolExecutor.CallerRunsPolicy.class, true, 60);
            assertExecutor(context.getBean("canalOutboxExecutor", ThreadPoolTaskExecutor.class),
                    "canal-outbox-", 2, 4, 50, ThreadPoolExecutor.AbortPolicy.class, true, 60);
            assertExecutor(context.getBean("reconciliationExecutor", ThreadPoolTaskExecutor.class),
                    "reconciliation-", 2, 4, 100, ThreadPoolExecutor.CallerRunsPolicy.class, true, 60);
            assertExecutor(context.getBean("commentReadExecutor", ThreadPoolTaskExecutor.class),
                    "comment-read-", 8, 16, 200, ThreadPoolExecutor.CallerRunsPolicy.class, true, 60);
            assertExecutor(context.getBean("commentOutboxExecutor", ThreadPoolTaskExecutor.class),
                    "comment-outbox-", 2, 4, 50, ThreadPoolExecutor.CallerRunsPolicy.class, true, 60);
            assertThat(ReflectionTestUtils.getField(bridge, "taskExecutor")).isSameAs(canalExecutor);
            assertThat(ReflectionTestUtils.getField(consumer, "taskExecutor")).isSameAs(relationEventExecutor);
        });
    }

    @Test
    void acknowledgesOnlyAfterRelationTasksComplete() throws Exception {
        BlockingRelationEventProcessor processor = new BlockingRelationEventProcessor();
        RecordingAcknowledgment acknowledgment = new RecordingAcknowledgment();
        TaskExecutor asyncExecutor = runnable -> new Thread(runnable, "test-relation-event").start();
        ObjectMapper objectMapper = new ObjectMapper();
        CanalOutboxConsumer consumer = new CanalOutboxConsumer(new OutboxMessageReader(objectMapper), objectMapper, processor, asyncExecutor);
        CountDownLatch consumerFinished = new CountDownLatch(1);

        Thread consumerThread = new Thread(() -> {
            try {
                consumer.onMessage(
                        "{\"table\":\"outbox\",\"type\":\"INSERT\",\"data\":[{\"payload\":\"{\\\"type\\\":\\\"FollowCreated\\\",\\\"fromUserId\\\":1,\\\"toUserId\\\":2,\\\"id\\\":3}\"}]}",
                        acknowledgment
                );
            } finally {
                consumerFinished.countDown();
            }
        }, "test-consumer-thread");
        consumerThread.start();

        assertThat(processor.started.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(acknowledgment.acknowledged()).isFalse();

        processor.allowCompletion.countDown();

        assertThat(processor.completed.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(consumerFinished.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(acknowledgment.awaitAcknowledged(1, TimeUnit.SECONDS)).isTrue();
    }

    private void assertExecutor(ThreadPoolTaskExecutor executor,
                                String threadPrefix,
                                int corePoolSize,
                                int maxPoolSize,
                                int queueCapacity,
                                Class<? extends RejectedExecutionHandler> rejectionPolicyType,
                                boolean waitForShutdown,
                                int awaitTerminationSeconds) {
        assertThat(executor.getThreadNamePrefix()).isEqualTo(threadPrefix);
        assertThat(executor.getCorePoolSize()).isEqualTo(corePoolSize);
        assertThat(executor.getMaxPoolSize()).isEqualTo(maxPoolSize);
        assertThat(executor.getQueueCapacity()).isEqualTo(queueCapacity);
        assertThat(ReflectionTestUtils.getField(executor, "rejectedExecutionHandler"))
                .isInstanceOf(rejectionPolicyType);
        assertThat(ReflectionTestUtils.getField(executor, "waitForTasksToCompleteOnShutdown"))
                .isEqualTo(waitForShutdown);
        assertThat(ReflectionTestUtils.getField(executor, "awaitTerminationMillis"))
                .isEqualTo(awaitTerminationSeconds * 1000L);
    }

    static class NoOpRelationEventProcessor extends RelationEventProcessor {
        NoOpRelationEventProcessor() {
            super(null, null, null);
        }
    }

    static class BlockingRelationEventProcessor extends RelationEventProcessor {
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch allowCompletion = new CountDownLatch(1);
        private final CountDownLatch completed = new CountDownLatch(1);

        BlockingRelationEventProcessor() {
            super(null, null, null);
        }

        @Override
        public void process(RelationEvent evt) {
            started.countDown();
            try {
                allowCompletion.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                completed.countDown();
            }
        }
    }

    static class RecordingAcknowledgment implements Acknowledgment {
        private final AtomicBoolean acknowledged = new AtomicBoolean(false);
        private final AtomicInteger acknowledgeCount = new AtomicInteger();
        private final CountDownLatch acknowledgedLatch = new CountDownLatch(1);

        @Override
        public void acknowledge() {
            acknowledged.set(true);
            acknowledgeCount.incrementAndGet();
            acknowledgedLatch.countDown();
        }

        boolean acknowledged() {
            return acknowledged.get();
        }

        boolean awaitAcknowledged(long timeout, TimeUnit unit) throws InterruptedException {
            return acknowledgedLatch.await(timeout, unit);
        }
    }
}
