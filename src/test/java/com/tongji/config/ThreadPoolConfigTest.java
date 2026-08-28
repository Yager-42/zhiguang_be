package com.tongji.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.outbox.CanalKafkaBridge;
import com.tongji.outbox.CanalOutboxBatchPublisher;
import com.tongji.outbox.OutboxMessageReader;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.task.TaskExecutor;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ThreadPoolConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(ThreadPoolConfig.class, CanalKafkaBridge.class, CanalOutboxBatchPublisher.class, OutboxMessageReader.class)
            .withBean(ProducerFactory.class, () -> new DefaultKafkaProducerFactory<String, String>(Map.of()))
            .withBean(KafkaTemplate.class, () -> new KafkaTemplate<>(mock(ProducerFactory.class)))
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
            CanalKafkaBridge bridge = context.getBean(CanalKafkaBridge.class);

            assertExecutor(context.getBean("taskExecutor", ThreadPoolTaskExecutor.class),
                    "task-", 10, 50, 200, ThreadPoolExecutor.CallerRunsPolicy.class, true, 60);
            assertThat(context).doesNotHaveBean("publishExecutor");
            assertExecutor(context.getBean("canalOutboxExecutor", ThreadPoolTaskExecutor.class),
                    "canal-outbox-", 2, 4, 50, ThreadPoolExecutor.AbortPolicy.class, true, 60);
            assertExecutor(context.getBean("reconciliationExecutor", ThreadPoolTaskExecutor.class),
                    "reconciliation-", 2, 4, 100, ThreadPoolExecutor.CallerRunsPolicy.class, true, 60);
            assertExecutor(context.getBean("commentReadExecutor", ThreadPoolTaskExecutor.class),
                    "comment-read-", 8, 16, 200, ThreadPoolExecutor.CallerRunsPolicy.class, true, 60);
            assertExecutor(context.getBean("commentOutboxExecutor", ThreadPoolTaskExecutor.class),
                    "comment-outbox-", 2, 4, 50, ThreadPoolExecutor.CallerRunsPolicy.class, true, 60);
            assertThat(ReflectionTestUtils.getField(bridge, "taskExecutor")).isSameAs(canalExecutor);
        });
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
}