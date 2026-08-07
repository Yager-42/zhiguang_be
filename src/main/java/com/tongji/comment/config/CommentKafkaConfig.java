package com.tongji.comment.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;

@Configuration
public class CommentKafkaConfig {

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> commentWriteKafkaListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory,
            @Value("${comment.kafka.consumer-concurrency:8}") int concurrency) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        factory.setConcurrency(concurrency);
        return factory;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> commentEventKafkaListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        return factory;
    }

    @Bean
    public NewTopic commentWriteTopic(
            @Value("${comment.kafka.write-topic:comment-write}") String topic,
            @Value("${comment.kafka.write-partitions:8}") int partitions) {
        return TopicBuilder.name(topic)
                .partitions(partitions)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic commentEventTopic(
            @Value("${comment.kafka.event-topic:comment-events}") String topic,
            @Value("${comment.kafka.event-partitions:8}") int partitions) {
        return TopicBuilder.name(topic)
                .partitions(partitions)
                .replicas(1)
                .build();
    }

}
