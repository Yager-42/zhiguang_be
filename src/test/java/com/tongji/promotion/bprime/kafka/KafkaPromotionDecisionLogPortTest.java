package com.tongji.promotion.bprime.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.promotion.bprime.config.PromotionBPrimeProperties;
import com.tongji.promotion.bprime.model.PromotionAuctionDecision;
import com.tongji.promotion.bprime.model.PromotionAuctionDecisionLogEnvelope;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KafkaPromotionDecisionLogPortTest {

    @Test
    void sendsEnvelopeWithAuctionWindowKey() {
        KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
        PromotionBPrimeProperties properties = new PromotionBPrimeProperties();
        properties.setDecisionTopic("decision-topic");
        KafkaPromotionDecisionLogPort port = new KafkaPromotionDecisionLogPort(
                kafkaTemplate, new ObjectMapper().findAndRegisterModules(), properties);
        when(kafkaTemplate.send(org.mockito.ArgumentMatchers.eq("decision-topic"),
                org.mockito.ArgumentMatchers.eq("301"), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

        port.append(decision());

        verify(kafkaTemplate).send(org.mockito.ArgumentMatchers.eq("decision-topic"),
                org.mockito.ArgumentMatchers.eq("301"), argThat(value -> {
                    try {
                        PromotionAuctionDecisionLogEnvelope envelope = new ObjectMapper().findAndRegisterModules()
                                .readValue(value, PromotionAuctionDecisionLogEnvelope.class);
                        return envelope.schemaVersion() == 1
                                && "AUCTION_DECISION".equals(envelope.eventType())
                                && "BID_ACCEPTED".equals(envelope.decision().type())
                                && envelope.decisionHash() != null
                                && !envelope.decisionHash().isBlank();
                    } catch (Exception e) {
                        return false;
                    }
                }));
    }

    @Test
    void throwsWhenKafkaFutureFails() {
        KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
        PromotionBPrimeProperties properties = new PromotionBPrimeProperties();
        properties.setDecisionTopic("decision-topic");
        KafkaPromotionDecisionLogPort port = new KafkaPromotionDecisionLogPort(
                kafkaTemplate, new ObjectMapper().findAndRegisterModules(), properties);
        CompletableFuture<SendResult<String, String>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new RuntimeException("boom"));
        when(kafkaTemplate.send(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString())).thenReturn(failed);

        assertThatThrownBy(() -> port.append(decision()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to append promotion decision batch to Kafka");
    }

    private PromotionAuctionDecision decision() {
        return new PromotionAuctionDecision("d-1", "cmd-1", "hash", 301L, 201L, 42L, 1001L,
                "FEED_TOP_SLOT", "BID_ACCEPTED", true, null, 120L, List.of(), List.of(),
                Instant.parse("2026-06-20T10:05:00Z"));
    }
}
