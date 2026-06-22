package com.tongji.promotion.bprime.mq;

import com.tongji.promotion.bprime.config.PromotionBPrimeProperties;
import com.tongji.promotion.bprime.model.PromotionAuctionCommand;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RocketMqPromotionCommandMessagePortTest {

    private RocketMQTemplate rocketMQTemplate;
    private RocketMqPromotionCommandMessagePort port;

    @BeforeEach
    void setUp() {
        rocketMQTemplate = mock(RocketMQTemplate.class);
        PromotionBPrimeProperties properties = new PromotionBPrimeProperties();
        properties.setCommandTopic("commands-topic");
        port = new RocketMqPromotionCommandMessagePort(rocketMQTemplate, properties);
    }

    @Test
    void sendsOrderlyByAuctionWindowId() {
        PromotionAuctionCommand command = command();
        SendResult result = new SendResult();
        result.setSendStatus(SendStatus.SEND_OK);
        when(rocketMQTemplate.syncSendOrderly("commands-topic", command, "301")).thenReturn(result);

        port.send(command);

        verify(rocketMQTemplate).syncSendOrderly("commands-topic", command, "301");
    }

    @Test
    void throwsWhenSendStatusIsNotOk() {
        PromotionAuctionCommand command = command();
        SendResult result = new SendResult();
        result.setSendStatus(SendStatus.FLUSH_DISK_TIMEOUT);
        when(rocketMQTemplate.syncSendOrderly("commands-topic", command, "301")).thenReturn(result);

        assertThatThrownBy(() -> port.send(command))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to send promotion command to RocketMQ");
    }

    private PromotionAuctionCommand command() {
        return new PromotionAuctionCommand("cmd-1", "idem-1", "hash", 301L, 201L, 42L,
                1001L, "FEED_TOP_SLOT", 120L, "BID", Instant.parse("2026-06-20T10:05:00Z"));
    }
}
