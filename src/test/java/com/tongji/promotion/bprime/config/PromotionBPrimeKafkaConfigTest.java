package com.tongji.promotion.bprime.config;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;

import static org.assertj.core.api.Assertions.assertThat;

class PromotionBPrimeKafkaConfigTest {

    @Test
    void preservesLingerForAsynchronousDecisionBatching() {
        KafkaProperties properties = new KafkaProperties();
        properties.getProducer().getProperties().put(ProducerConfig.LINGER_MS_CONFIG, "5");

        DefaultKafkaProducerFactory<?, ?> producerFactory = (DefaultKafkaProducerFactory<?, ?>)
                new PromotionBPrimeKafkaConfig().promotionDecisionProducerFactory(properties);

        assertThat(String.valueOf(producerFactory.getConfigurationProperties().get(ProducerConfig.LINGER_MS_CONFIG)))
                .isEqualTo("5");
        assertThat(producerFactory.getConfigurationProperties()
                .get(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION)).isEqualTo(5);
    }
}
