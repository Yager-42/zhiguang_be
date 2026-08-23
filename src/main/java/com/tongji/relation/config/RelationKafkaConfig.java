package com.tongji.relation.config;

import com.tongji.relation.command.FollowCommandTopics;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * 关注命令 Kafka 配置：主题创建 + 独立监听器工厂（手动 ack、指数重试、DLT 兜底）。
 *
 * <p>分区数 = 消费并行度上限（并发 ≤ 分区）。生产端限流上限 1/s/用户，多用户聚合下
 * 消费端并行度须匹配：默认 16 分区 / 8 并发 ≈ 600 命令/s 消费能力。</p>
 */
@Configuration
public class RelationKafkaConfig {

    @Bean
    public NewTopic relationCommandTopic(@Value("${relation.kafka.partitions:16}") int partitions) {
        return TopicBuilder.name(FollowCommandTopics.COMMAND)
                .partitions(partitions)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic relationCommandDltTopic() {
        return TopicBuilder.name(FollowCommandTopics.DLT)
                .partitions(1)
                .replicas(1)
                .build();
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> relationCommandKafkaListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory,
            @Value("${relation.kafka.consumer-concurrency:8}") int concurrency,
            KafkaTemplate<String, String> kafkaTemplate) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);
        factory.setConcurrency(concurrency);
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate,
                (record, ex) -> new TopicPartition(FollowCommandTopics.DLT, record.partition()));
        factory.setCommonErrorHandler(new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 10)));
        return factory;
    }
}
