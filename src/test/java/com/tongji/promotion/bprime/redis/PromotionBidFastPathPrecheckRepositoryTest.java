package com.tongji.promotion.bprime.redis;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PromotionBidFastPathPrecheckRepositoryTest {

    @Test
    @SuppressWarnings("unchecked")
    void openWindowAndMissingDedupeAllowsFastReject() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        when(redisTemplate.executePipelined(any(RedisCallback.class)))
                .thenReturn(List.of("OPEN".getBytes(StandardCharsets.UTF_8), false));

        PromotionBidFastPathPrecheckRepository.Result result =
                new PromotionBidFastPathPrecheckRepository(redisTemplate).checkBatch(
                        List.of(new PromotionBidFastPathPrecheckRepository.Check(301L, "cmd-1"))).getFirst();

        assertThat(result.available()).isTrue();
        assertThat(result.allowsFastReject()).isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void duplicateAndClosedWindowNeverAllowFastReject() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        when(redisTemplate.executePipelined(any(RedisCallback.class)))
                .thenReturn(List.of("CLOSED".getBytes(StandardCharsets.UTF_8), true));

        PromotionBidFastPathPrecheckRepository.Result result =
                new PromotionBidFastPathPrecheckRepository(redisTemplate).checkBatch(
                        List.of(new PromotionBidFastPathPrecheckRepository.Check(301L, "cmd-1"))).getFirst();

        assertThat(result.available()).isTrue();
        assertThat(result.allowsFastReject()).isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void malformedPipelineResultIsInconclusive() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        when(redisTemplate.executePipelined(any(RedisCallback.class))).thenReturn(List.of());

        PromotionBidFastPathPrecheckRepository.Result result =
                new PromotionBidFastPathPrecheckRepository(redisTemplate).checkBatch(
                        List.of(new PromotionBidFastPathPrecheckRepository.Check(301L, "cmd-1"))).getFirst();

        assertThat(result.available()).isFalse();
        assertThat(result.allowsFastReject()).isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void batchReadsSharedWindowStatusOnceAndPreservesDedupeOrder() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        when(redisTemplate.executePipelined(any(RedisCallback.class)))
                .thenReturn(List.of("OPEN".getBytes(StandardCharsets.UTF_8), false, true));
        PromotionBidFastPathPrecheckRepository repository =
                new PromotionBidFastPathPrecheckRepository(redisTemplate);

        List<PromotionBidFastPathPrecheckRepository.Result> results = repository.checkBatch(List.of(
                new PromotionBidFastPathPrecheckRepository.Check(301L, "cmd-1"),
                new PromotionBidFastPathPrecheckRepository.Check(301L, "cmd-2")));

        assertThat(results).hasSize(2);
        assertThat(results.get(0).allowsFastReject()).isTrue();
        assertThat(results.get(1).duplicate()).isTrue();
        assertThat(results.get(1).allowsFastReject()).isFalse();
    }
}
