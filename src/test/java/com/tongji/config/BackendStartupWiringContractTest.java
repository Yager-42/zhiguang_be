package com.tongji.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.common.id.IdService;
import com.tongji.common.id.segment.SegmentAllocator;
import com.tongji.common.id.segment.SegmentIdGenerator;
import com.tongji.common.id.segment.SegmentIdProperties;
import com.tongji.common.resilience.ResilienceGuard;
import com.tongji.knowpost.manager.PublishAttemptService;
import com.tongji.knowpost.manager.PublishValidationHelper;
import com.tongji.knowpost.mapper.KnowPostMapper;
import com.tongji.knowpost.publish.ContentPublishedPublisher;
import com.tongji.knowpost.publish.PublishAttemptMapper;
import com.tongji.promotion.bprime.mq.NoopPromotionCommandMessagePort;
import com.tongji.promotion.bprime.mq.PromotionCommandMessagePort;
import com.tongji.promotion.bprime.realtime.NoopPromotionAuctionRealtimePublisher;
import com.tongji.promotion.bprime.realtime.PromotionAuctionRealtimePublisher;
import com.tongji.promotion.bprime.service.PromotionDecisionProjectionService;
import com.tongji.recommendation.gorse.GorseClient;
import com.tongji.recommendation.gorse.GorseProperties;
import com.tongji.reconciliation.executor.PromotionDecisionProjectionReconciler;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class BackendStartupWiringContractTest {

    @Test
    void multiConstructorStartupBeansCanBeInstantiatedBySpring() {
        ApplicationContextRunner contextRunner = new ApplicationContextRunner()
                .withBean(SegmentAllocator.class, () -> mock(SegmentAllocator.class))
                .withBean(SegmentIdProperties.class)
                .withBean(SegmentIdGenerator.class)
                .withBean(KnowPostMapper.class, () -> mock(KnowPostMapper.class))
                .withBean(PublishAttemptMapper.class, () -> mock(PublishAttemptMapper.class))
                .withBean(PublishValidationHelper.class, () -> mock(PublishValidationHelper.class))
                .withBean(IdService.class, () -> mock(IdService.class))
                .withBean(ContentPublishedPublisher.class, () -> mock(ContentPublishedPublisher.class))
                .withBean(ResilienceGuard.class, () -> mock(ResilienceGuard.class))
                .withBean(PublishAttemptService.class)
                .withBean(GorseProperties.class)
                .withBean(GorseClient.class)
                .withBean(PromotionDecisionProjectionService.class, () -> mock(PromotionDecisionProjectionService.class))
                .withBean(ObjectMapper.class)
                .withBean(PromotionDecisionProjectionReconciler.class);

        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(SegmentIdGenerator.class);
            assertThat(context).hasSingleBean(PublishAttemptService.class);
            assertThat(context).hasSingleBean(GorseClient.class);
            assertThat(context).hasSingleBean(PromotionDecisionProjectionReconciler.class);
        });
    }

    @Test
    void restTemplateAndNoopBprimeBeansLoadWhenPropertyMissing() {
        ApplicationContextRunner contextRunner = new ApplicationContextRunner()
                .withBean(RestTemplateBuilder.class, RestTemplateBuilder::new)
                .withUserConfiguration(
                        RestTemplateConfig.class,
                        NoopPromotionCommandMessagePort.class,
                        NoopPromotionAuctionRealtimePublisher.class
                );

        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(RestTemplate.class);
            assertThat(context).hasSingleBean(PromotionCommandMessagePort.class);
            assertThat(context).hasSingleBean(PromotionAuctionRealtimePublisher.class);
            assertThat(context.getBean(PromotionCommandMessagePort.class)).isInstanceOf(NoopPromotionCommandMessagePort.class);
            assertThat(context.getBean(PromotionAuctionRealtimePublisher.class)).isInstanceOf(NoopPromotionAuctionRealtimePublisher.class);
        });
    }

    @Test
    void noopBprimeBeansDoNotLoadWhenBprimeEnabled() {
        ApplicationContextRunner contextRunner = new ApplicationContextRunner()
                .withPropertyValues("promotion.bprime.enabled=true")
                .withUserConfiguration(
                        NoopPromotionCommandMessagePort.class,
                        NoopPromotionAuctionRealtimePublisher.class
                );

        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean(PromotionCommandMessagePort.class);
            assertThat(context).doesNotHaveBean(PromotionAuctionRealtimePublisher.class);
        });
    }
}