package com.tongji.comment.config;

import com.tongji.comment.cache.CommentCacheInvalidationListener;
import com.tongji.comment.consumer.CommentCounterConsumer;
import com.tongji.comment.consumer.CommentFeedbackConsumer;
import com.tongji.comment.consumer.CommentRewardConsumer;
import com.tongji.outbox.OutboxTopics;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CommentKafkaConfigTest {

    @Test
    void writeListenerUsesRecordAckAndBoundedConcurrency() {
        CommentKafkaConfig config = new CommentKafkaConfig();
        ConsumerFactory<String, String> consumerFactory = new DefaultKafkaConsumerFactory<>(Map.of());

        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                config.commentWriteKafkaListenerContainerFactory(consumerFactory, 4);
        var container = factory.createContainer("canal-outbox-test");

        assertThat(factory.getContainerProperties().getAckMode()).isEqualTo(ContainerProperties.AckMode.RECORD);
        assertThat(container.getConcurrency()).isEqualTo(4);
    }

    @Test
    void eventListenersUseRecordAckFactory() throws NoSuchMethodException {
        CommentKafkaConfig config = new CommentKafkaConfig();
        ConsumerFactory<String, String> consumerFactory = new DefaultKafkaConsumerFactory<>(Map.of());

        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                config.commentEventKafkaListenerContainerFactory(consumerFactory);

        assertThat(factory.getContainerProperties().getAckMode()).isEqualTo(ContainerProperties.AckMode.RECORD);
        List<Class<?>> listenerTypes = List.of(
                CommentRewardConsumer.class,
                CommentCounterConsumer.class,
                CommentFeedbackConsumer.class,
                CommentCacheInvalidationListener.class);
        Set<String> groupIds = new HashSet<>();
        for (Class<?> listenerType : listenerTypes) {
            KafkaListener listener = listenerType.getDeclaredMethod("onMessage", String.class)
                    .getAnnotation(KafkaListener.class);
            assertThat(listener.topics()).as(listenerType.getSimpleName())
                    .containsExactly(OutboxTopics.CANAL_OUTBOX);
            assertThat(groupIds.add(listener.groupId())).as(listenerType.getSimpleName()).isTrue();
            assertThat(listener.containerFactory())
                    .as(listenerType.getSimpleName())
                    .isEqualTo("commentEventKafkaListenerContainerFactory");
        }

        Set<String> retrySuffixes = new HashSet<>();
        Set<String> dltSuffixes = new HashSet<>();
        for (Class<?> listenerType : List.of(
                CommentRewardConsumer.class,
                CommentCounterConsumer.class,
                CommentFeedbackConsumer.class)) {
            RetryableTopic retry = listenerType.getDeclaredMethod("onMessage", String.class)
                    .getAnnotation(RetryableTopic.class);
            assertThat(retrySuffixes.add(retry.retryTopicSuffix()))
                    .as(listenerType.getSimpleName()).isTrue();
            assertThat(dltSuffixes.add(retry.dltTopicSuffix()))
                    .as(listenerType.getSimpleName()).isTrue();
        }
    }
}
