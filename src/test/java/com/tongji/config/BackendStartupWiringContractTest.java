package com.tongji.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.common.id.IdService;
import com.tongji.common.id.segment.SegmentAllocator;
import com.tongji.common.id.segment.SegmentIdGenerator;
import com.tongji.common.id.segment.SegmentIdProperties;
import com.tongji.knowpost.manager.PublishAttemptService;
import com.tongji.knowpost.manager.PublishValidationHelper;
import com.tongji.knowpost.mapper.KnowPostMapper;
import com.tongji.knowpost.publish.PublishOutboxWriter;
import com.tongji.knowpost.publish.PublishAttemptMapper;
import com.tongji.promotion.bprime.realtime.NoopPromotionAuctionRealtimePublisher;
import com.tongji.promotion.bprime.realtime.PromotionAuctionRealtimePublisher;
import com.tongji.recommendation.gorse.GorseClient;
import com.tongji.recommendation.gorse.GorseProperties;
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
                .withBean(PublishOutboxWriter.class, () -> mock(PublishOutboxWriter.class))
                .withBean(PublishAttemptService.class)
                .withBean(GorseProperties.class)
                .withBean(GorseClient.class)
                .withBean(ObjectMapper.class);

        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(SegmentIdGenerator.class);
            assertThat(context).hasSingleBean(PublishAttemptService.class);
            assertThat(context).hasSingleBean(GorseClient.class);
        });
    }

    @Test
    void restTemplateAndNoopBprimeBeansLoadWhenPropertyMissing() {
        ApplicationContextRunner contextRunner = new ApplicationContextRunner()
                .withBean(RestTemplateBuilder.class, RestTemplateBuilder::new)
                .withUserConfiguration(
                        RestTemplateConfig.class,
                        NoopPromotionAuctionRealtimePublisher.class
                );

        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(RestTemplate.class);
            assertThat(context).hasSingleBean(PromotionAuctionRealtimePublisher.class);
            assertThat(context.getBean(PromotionAuctionRealtimePublisher.class)).isInstanceOf(NoopPromotionAuctionRealtimePublisher.class);
        });
    }

    @Test
    void noopBprimeBeansDoNotLoadWhenBprimeEnabled() {
        ApplicationContextRunner contextRunner = new ApplicationContextRunner()
                .withPropertyValues("promotion.bprime.enabled=true")
                .withUserConfiguration(
                        NoopPromotionAuctionRealtimePublisher.class
                );

        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean(PromotionAuctionRealtimePublisher.class);
        });
    }
}
