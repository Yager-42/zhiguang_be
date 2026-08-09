package com.tongji.promotion.bprime.config;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.util.backoff.FixedBackOff;

import java.util.HashMap;
import java.util.Map;

@Configuration
@ConditionalOnProperty(name = "promotion.bprime.enabled", havingValue = "true")
public class PromotionBPrimeKafkaConfig {

    @Bean(name = "promotionDecisionProducerFactory")
    public ProducerFactory<String, String> promotionDecisionProducerFactory(KafkaProperties properties) {
        Map<String, Object> producerProperties = new HashMap<>(properties.buildProducerProperties());
        producerProperties.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
        return new DefaultKafkaProducerFactory<>(producerProperties, new StringSerializer(), new StringSerializer());
    }

    @Bean(name = "promotionDecisionKafkaTemplate")
    public KafkaTemplate<String, String> promotionDecisionKafkaTemplate(
            @Qualifier("promotionDecisionProducerFactory") ProducerFactory<String, String> producerFactory) {
        return new KafkaTemplate<>(producerFactory);
    }

    @Bean(name = "promotionDecisionBatchKafkaListenerContainerFactory")
    public ConcurrentKafkaListenerContainerFactory<String, String> promotionDecisionBatchKafkaListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory, PromotionBPrimeProperties promotionProperties) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setBatchListener(true);
        factory.setConcurrency(promotionProperties.getProjectionConcurrency());
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);
        factory.setCommonErrorHandler(retryForeverErrorHandler());
        return factory;
    }

    @Bean(name = "promotionDecisionKafkaListenerContainerFactory")
    public ConcurrentKafkaListenerContainerFactory<String, String> promotionDecisionKafkaListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);
        factory.setCommonErrorHandler(retryForeverErrorHandler());
        return factory;
    }

    /**
     * 为实时 fanout 提供独立 Kafka consumer 线程，允许与 decision/projection 拆进程部署。
     *
     * @param consumerFactory Kafka consumer 工厂
     * @param promotionProperties 推广竞价容量配置
     * @return 使用手动 ACK 的 fanout listener container 工厂
     */
    @Bean(name = "promotionDecisionFanoutKafkaListenerContainerFactory")
    public ConcurrentKafkaListenerContainerFactory<String, String>
            promotionDecisionFanoutKafkaListenerContainerFactory(
                    ConsumerFactory<String, String> consumerFactory,
                    PromotionBPrimeProperties promotionProperties) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setConcurrency(promotionProperties.getFanoutConcurrency());
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);
        factory.setCommonErrorHandler(retryForeverErrorHandler());
        return factory;
    }

    private DefaultErrorHandler retryForeverErrorHandler() {
        return new DefaultErrorHandler(new FixedBackOff(1000L, FixedBackOff.UNLIMITED_ATTEMPTS));
    }
}
