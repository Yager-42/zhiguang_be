package com.tongji.promotion.bprime.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.promotion.bprime.config.PromotionBPrimeProperties;
import com.tongji.promotion.bprime.model.PromotionAuctionCommand;
import com.tongji.promotion.bprime.service.PromotionCommandProcessingService;
import org.apache.rocketmq.client.consumer.listener.ConsumeOrderlyContext;
import org.apache.rocketmq.client.consumer.listener.ConsumeOrderlyStatus;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.autoconfigure.RocketMQProperties;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class PromotionCommandRocketMqListenerTest {

    @Test
    void delegatesOrderedBatchToProcessorBeforeAcknowledging() throws Exception {
        PromotionCommandProcessingService processingService = mock(PromotionCommandProcessingService.class);
        PromotionBPrimeProperties properties = new PromotionBPrimeProperties();
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        PromotionCommandRocketMqListener listener = new PromotionCommandRocketMqListener(
                processingService, properties, mock(RocketMQProperties.class), mock(Environment.class), objectMapper);
        PromotionAuctionCommand command = command();
        MessageExt message = new MessageExt();
        message.setBody(objectMapper.writeValueAsBytes(command));

        ConsumeOrderlyStatus status = listener.consumeMessages(
                List.of(message), mock(ConsumeOrderlyContext.class));

        assertThat(status).isEqualTo(ConsumeOrderlyStatus.SUCCESS);
        verify(processingService).processBatch(List.of(command));
    }

    @Test
    void suspendsWholeQueueBatchWhenKafkaDurabilityFails() throws Exception {
        PromotionCommandProcessingService processingService = mock(PromotionCommandProcessingService.class);
        PromotionBPrimeProperties properties = new PromotionBPrimeProperties();
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        PromotionCommandRocketMqListener listener = new PromotionCommandRocketMqListener(
                processingService, properties, mock(RocketMQProperties.class), mock(Environment.class), objectMapper);
        PromotionAuctionCommand command = command();
        MessageExt message = new MessageExt();
        message.setBody(objectMapper.writeValueAsBytes(command));
        ConsumeOrderlyContext context = mock(ConsumeOrderlyContext.class);
        doThrow(new IllegalStateException("kafka unavailable"))
                .when(processingService).processBatch(List.of(command));

        ConsumeOrderlyStatus status = listener.consumeMessages(List.of(message), context);

        assertThat(status).isEqualTo(ConsumeOrderlyStatus.SUSPEND_CURRENT_QUEUE_A_MOMENT);
        verify(context).setSuspendCurrentQueueTimeMillis(properties.getDecisionBatchSuspendMs());
    }

    private PromotionAuctionCommand command() {
        return new PromotionAuctionCommand("cmd-1", "idem-1", "hash", 301L,
                201L, 42L, 1001L, "FEED_TOP_SLOT", 120L, 100L, "OPEN", "BID",
                Instant.parse("2026-06-20T10:05:00Z"));
    }
}
