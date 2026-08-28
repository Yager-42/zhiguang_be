package com.tongji.knowpost.publish;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;

/**
 * 发布关键消费组的有界并发和逐记录确认配置。
 *
 * @since 2026-08-28
 */
@Configuration
public class PublishKafkaConfig {

    /**
     * 创建直接执行发布工作流的 Listener Container Factory。
     *
     * @param consumerFactory 应用共享的字符串消费者工厂
     * @param concurrency 发布分区内允许的最大并发消费者数
     * @return 使用 RECORD ack 的发布监听器工厂
     */
    @Bean("publishKafkaListenerContainerFactory")
    public ConcurrentKafkaListenerContainerFactory<String, String> publishKafkaListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory,
            @Value("${publish.kafka.consumer-concurrency:1}") int concurrency) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        factory.setConcurrency(concurrency);
        return factory;
    }
}
