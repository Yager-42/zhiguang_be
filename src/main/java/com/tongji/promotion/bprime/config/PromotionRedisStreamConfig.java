package com.tongji.promotion.bprime.config;

import com.tongji.promotion.bprime.redis.PromotionAuctionRedisKeys;
import com.tongji.promotion.bprime.redis.PromotionRedisStreamProjector;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * 推广竞价 Stream 的轻量 Pub/Sub 唤醒配置。
 */
@Configuration
@ConditionalOnProperty(name = "promotion.bprime.enabled", havingValue = "true")
public class PromotionRedisStreamConfig {

    @Bean
    public RedisMessageListenerContainer promotionRedisMessageListenerContainer(
            RedisConnectionFactory connectionFactory,
            PromotionRedisStreamProjector projector) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(projector,
                new PatternTopic(PromotionAuctionRedisKeys.publicationPattern()));
        return container;
    }
}
