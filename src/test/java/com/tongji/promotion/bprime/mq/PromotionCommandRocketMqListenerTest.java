package com.tongji.promotion.bprime.mq;

import com.tongji.promotion.bprime.model.PromotionAuctionCommand;
import com.tongji.promotion.bprime.service.PromotionCommandProcessingService;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class PromotionCommandRocketMqListenerTest {

    @Test
    void delegatesToProcessor() {
        PromotionCommandProcessingService processingService = mock(PromotionCommandProcessingService.class);
        PromotionCommandRocketMqListener listener = new PromotionCommandRocketMqListener(processingService);
        PromotionAuctionCommand command = new PromotionAuctionCommand("cmd-1", "idem-1", "hash", 301L,
                201L, 42L, 1001L, "FEED_TOP_SLOT", 120L, "BID", Instant.parse("2026-06-20T10:05:00Z"));

        listener.onMessage(command);

        verify(processingService).process(command);
    }
}
