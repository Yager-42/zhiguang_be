package com.tongji.promotion.bprime.service;

import com.tongji.promotion.bprime.config.PromotionBPrimeProperties;
import com.tongji.promotion.bprime.redis.PromotionBidFastPathPrecheckRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PromotionBidFastPathPrecheckBatcherTest {

    @Test
    void completesEveryCandidateFromOneBoundedWorkerPipeline() {
        PromotionBidFastPathPrecheckRepository repository = mock(PromotionBidFastPathPrecheckRepository.class);
        when(repository.checkBatch(anyList())).thenAnswer(invocation -> {
            List<?> checks = invocation.getArgument(0);
            return checks.stream()
                    .map(ignored -> new PromotionBidFastPathPrecheckRepository.Result(true, true, false))
                    .toList();
        });
        PromotionBPrimeProperties properties = properties();
        PromotionBidFastPathPrecheckBatcher batcher =
                new PromotionBidFastPathPrecheckBatcher(repository, properties);
        batcher.start();
        try {
            List<CompletableFuture<PromotionBidFastPathPrecheckRepository.Result>> futures = List.of(
                    batcher.checkAsync(301L, "cmd-1"),
                    batcher.checkAsync(301L, "cmd-2"),
                    batcher.checkAsync(301L, "cmd-3"));

            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();

            assertThat(futures).allSatisfy(future -> assertThat(future.join().allowsFastReject()).isTrue());
            verify(repository).checkBatch(anyList());
        } finally {
            batcher.stop();
        }
    }

    @Test
    void disabledBatcherReturnsInconclusiveResult() {
        PromotionBPrimeProperties properties = properties();
        properties.setFastRejectEnabled(false);
        PromotionBidFastPathPrecheckBatcher batcher = new PromotionBidFastPathPrecheckBatcher(
                mock(PromotionBidFastPathPrecheckRepository.class), properties);

        PromotionBidFastPathPrecheckRepository.Result result = batcher.checkAsync(301L, "cmd-1").join();

        assertThat(result.available()).isFalse();
    }

    private PromotionBPrimeProperties properties() {
        PromotionBPrimeProperties properties = new PromotionBPrimeProperties();
        properties.setEnabled(true);
        properties.setFastRejectEnabled(true);
        properties.setFastRejectPrecheckWorkerCount(1);
        properties.setFastRejectPrecheckBatchSize(16);
        properties.setFastRejectPrecheckMaxWaitMicros(5_000L);
        properties.setFastRejectPrecheckQueueCapacity(32);
        return properties;
    }
}
