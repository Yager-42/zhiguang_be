package com.tongji.relation.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.common.resilience.GuardResult;
import com.tongji.common.resilience.GuardedOperation;
import com.tongji.common.resilience.ResilienceGuard;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.common.PartitionInfo;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RelationKafkaMetadataWarmupTest {

    @Test
    void warmsEveryCommandPartitionThroughDeliveryGuard() {
        KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        PassthroughResilienceGuard resilienceGuard = new PassthroughResilienceGuard();
        when(kafkaTemplate.partitionsFor("relation-command")).thenReturn(List.of(
                new PartitionInfo("relation-command", 0, null, null, null),
                new PartitionInfo("relation-command", 1, null, null, null)));
        when(kafkaTemplate.send(org.mockito.ArgumentMatchers.eq("relation-command"),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.eq("startup-warmup"),
                org.mockito.ArgumentMatchers.contains("\"warmup\":true")))
                .thenReturn(CompletableFuture.completedFuture(null));
        RelationKafkaMetadataWarmup warmup = new RelationKafkaMetadataWarmup(
                kafkaTemplate, new ObjectMapper(), meterRegistry, resilienceGuard);

        warmup.run(new DefaultApplicationArguments());

        verify(kafkaTemplate).partitionsFor("relation-command");
        verify(kafkaTemplate).send(org.mockito.ArgumentMatchers.eq("relation-command"),
                org.mockito.ArgumentMatchers.eq(0), org.mockito.ArgumentMatchers.eq("startup-warmup"),
                org.mockito.ArgumentMatchers.contains("\"warmup\":true"));
        verify(kafkaTemplate).send(org.mockito.ArgumentMatchers.eq("relation-command"),
                org.mockito.ArgumentMatchers.eq(1), org.mockito.ArgumentMatchers.eq("startup-warmup"),
                org.mockito.ArgumentMatchers.contains("\"warmup\":true"));
        assertThat(resilienceGuard.resourceName).isEqualTo("relation:command-delivery");
        assertThat(meterRegistry.get("relation.command.metadata.warmup")
                .tag("result", "success").timer().count()).isEqualTo(1);
    }

    @Test
    void keepsApplicationStartupAvailableWhenKafkaIsTemporarilyUnavailable() {
        KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        PassthroughResilienceGuard resilienceGuard = new PassthroughResilienceGuard();
        when(kafkaTemplate.partitionsFor("relation-command")).thenThrow(new IllegalStateException("broker down"));
        RelationKafkaMetadataWarmup warmup = new RelationKafkaMetadataWarmup(
                kafkaTemplate, new ObjectMapper(), meterRegistry, resilienceGuard);

        assertThatCode(() -> warmup.run(new DefaultApplicationArguments())).doesNotThrowAnyException();
        assertThat(meterRegistry.get("relation.command.metadata.warmup")
                .tag("result", "failure").timer().count()).isEqualTo(1);
    }

    private static final class PassthroughResilienceGuard implements ResilienceGuard {
        private String resourceName;

        @Override
        public <T> GuardResult<T> execute(String resourceName,
                                          GuardedOperation<T> operation,
                                          Supplier<T> fallbackSupplier,
                                          Predicate<Throwable> systemFailureClassifier) {
            this.resourceName = resourceName;
            try {
                return GuardResult.success(operation.execute());
            } catch (Exception exception) {
                systemFailureClassifier.test(exception);
                return GuardResult.fallback(fallbackSupplier.get(), exception);
            }
        }
    }
}
