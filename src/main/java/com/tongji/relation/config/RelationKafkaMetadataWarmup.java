package com.tongji.relation.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.common.resilience.GuardResult;
import com.tongji.common.resilience.ResilienceGuard;
import com.tongji.relation.command.FollowCommandEvent;
import com.tongji.relation.command.FollowCommandTopics;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 在应用启动阶段预写关系命令主题的全部分区，避免首个关注请求承担 Kafka 分区冷启动耗时。
 *
 * <p>预热失败只记录告警，不改变现有启动可用性；实际关注请求仍会同步等待 broker 确认并保持 fail-closed。</p>
 */
@Component
@ConditionalOnProperty(prefix = "relation.kafka", name = "metadata-warmup-enabled",
        havingValue = "true", matchIfMissing = true)
public class RelationKafkaMetadataWarmup implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RelationKafkaMetadataWarmup.class);
    private static final String DELIVERY_RESOURCE = "relation:command-delivery";

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final ResilienceGuard resilienceGuard;

    public RelationKafkaMetadataWarmup(KafkaTemplate<String, String> kafkaTemplate,
                                       ObjectMapper objectMapper,
                                       MeterRegistry meterRegistry,
                                       ResilienceGuard resilienceGuard) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
        this.resilienceGuard = resilienceGuard;
    }

    /**
     * 向每个业务分区发送可识别的 no-op 标记，完成元数据、Producer ID 和分区序列初始化。
     *
     * @param args Spring Boot 启动参数，本预热过程不读取参数
     */
    @Override
    public void run(ApplicationArguments args) {
        long startedAt = System.nanoTime();
        String result = "success";
        try {
            GuardResult<Integer> delivery = resilienceGuard.execute(
                    DELIVERY_RESOURCE,
                    () -> {
                        var partitions = kafkaTemplate.partitionsFor(FollowCommandTopics.COMMAND);
                        String payload = objectMapper.writeValueAsString(FollowCommandEvent.warmupMarker());
                        CompletableFuture<?>[] deliveries = partitions.stream()
                                .map(partition -> kafkaTemplate.send(
                                        FollowCommandTopics.COMMAND,
                                        partition.partition(),
                                        "startup-warmup",
                                        payload
                                ))
                                .toArray(CompletableFuture[]::new);
                        CompletableFuture.allOf(deliveries).get(10, TimeUnit.SECONDS);
                        return partitions.size();
                    },
                    () -> 0,
                    throwable -> true
            );
            if (delivery.fallbackApplied()) {
                if (delivery.failure() instanceof InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                    throw interruptedException;
                }
                throw new IllegalStateException("relation command warmup delivery failed", delivery.failure());
            }
            log.info("relation command delivery path warmed, partitions={}", delivery.value());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            result = "failure";
            log.warn("relation command topic metadata warmup interrupted; first request will retry", exception);
        } catch (Exception exception) {
            result = "failure";
            log.warn("relation command topic metadata warmup failed; first request will retry", exception);
        } finally {
            meterRegistry.timer("relation.command.metadata.warmup", "result", result)
                    .record(System.nanoTime() - startedAt, TimeUnit.NANOSECONDS);
        }
    }
}
