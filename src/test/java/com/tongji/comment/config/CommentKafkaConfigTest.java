package com.tongji.comment.config;

import com.tongji.comment.cache.CommentCacheInvalidationListener;
import com.tongji.comment.consumer.CommentCounterConsumer;
import com.tongji.comment.consumer.CommentFeedbackConsumer;
import com.tongji.comment.consumer.CommentRewardConsumer;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CommentKafkaConfigTest {

    @Test
    void topicsUseConfiguredNamesAndPartitions() {
        CommentKafkaConfig config = new CommentKafkaConfig();

        NewTopic write = config.commentWriteTopic("comment-write-test", 8);
        NewTopic events = config.commentEventTopic("comment-events-test", 6);

        assertThat(write.name()).isEqualTo("comment-write-test");
        assertThat(write.numPartitions()).isEqualTo(8);
        assertThat(events.name()).isEqualTo("comment-events-test");
        assertThat(events.numPartitions()).isEqualTo(6);
    }

    @Test
    void writeListenerUsesRecordAckAndBoundedConcurrency() {
        CommentKafkaConfig config = new CommentKafkaConfig();
        ConsumerFactory<String, String> consumerFactory = new DefaultKafkaConsumerFactory<>(Map.of());

        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                config.commentWriteKafkaListenerContainerFactory(consumerFactory, 4);
        var container = factory.createContainer("comment-write-test");

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
        for (Class<?> listenerType : List.of(
                CommentRewardConsumer.class,
                CommentCounterConsumer.class,
                CommentFeedbackConsumer.class,
                CommentCacheInvalidationListener.class)) {
            KafkaListener listener = listenerType.getDeclaredMethod("onMessage", String.class)
                    .getAnnotation(KafkaListener.class);
            assertThat(listener.containerFactory())
                    .as(listenerType.getSimpleName())
                    .isEqualTo("commentEventKafkaListenerContainerFactory");
        }
    }
}
