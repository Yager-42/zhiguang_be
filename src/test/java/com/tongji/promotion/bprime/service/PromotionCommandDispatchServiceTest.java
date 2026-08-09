package com.tongji.promotion.bprime.service;

import com.tongji.promotion.bprime.config.PromotionBPrimeProperties;
import com.tongji.promotion.bprime.mapper.PromotionAuctionCommandMapper;
import com.tongji.promotion.bprime.metrics.PromotionPerformanceMetrics;
import com.tongji.promotion.bprime.model.PromotionAuctionCommandRecord;
import com.tongji.promotion.bprime.mq.PromotionCommandMessagePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PromotionCommandDispatchServiceTest {

    private PromotionAuctionCommandMapper commandMapper;
    private PromotionCommandMessagePort messagePort;
    private PromotionBPrimeProperties properties;
    private PromotionPerformanceMetrics performanceMetrics;
    private PromotionCommandDispatchService service;

    @BeforeEach
    void setUp() {
        commandMapper = mock(PromotionAuctionCommandMapper.class);
        messagePort = mock(PromotionCommandMessagePort.class);
        properties = new PromotionBPrimeProperties();
        properties.setEnabled(true);
        performanceMetrics = mock(PromotionPerformanceMetrics.class);
        service = new PromotionCommandDispatchService(
                commandMapper, messagePort, properties, new SyncTaskExecutor(), performanceMetrics);
    }

    @Test
    void dispatchesOnlyAfterTransactionCommitWithoutClaimingFreshCommand() {
        PromotionAuctionCommandRecord command = command();
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.dispatchAfterCommit(command);

            verify(messagePort, never()).send(any());
            for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
                synchronization.afterCommit();
            }

            verify(messagePort).send(command.toCommand());
            verify(commandMapper, never()).claimForPublishingBatch(any(), any(), any());
            verify(commandMapper).markPublishedBatch(List.of("cmd-1"));
            verify(performanceMetrics).recordIngressAccepted();
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void brokerFailureLeavesFreshCommandSubmittedForRecovery() {
        PromotionAuctionCommandRecord command = command();
        doThrow(new IllegalStateException("broker unavailable")).when(messagePort).send(any());

        service.dispatchAfterCommit(command);

        verify(commandMapper, never()).claimForPublishingBatch(any(), any(), any());
        verify(commandMapper, never()).markPublishedBatch(any());
    }

    @Test
    void recoveryQueuesPersistedCommands() {
        PromotionAuctionCommandRecord command = command();
        when(commandMapper.listPublishable(any(Instant.class), any(Instant.class), eq(100)))
                .thenReturn(List.of(command));
        when(commandMapper.claimForPublishingBatch(
                eq(List.of("cmd-1")), any(Instant.class), any(Instant.class))).thenReturn(1);

        service.recoverPublishableCommands();

        verify(messagePort).send(command.toCommand());
        verify(commandMapper).markPublishedBatch(List.of("cmd-1"));
    }

    private PromotionAuctionCommandRecord command() {
        return PromotionAuctionCommandRecord.builder()
                .id(1L)
                .commandId("cmd-1")
                .idempotencyKey("idem-1")
                .requestHash("hash-1")
                .auctionWindowId(301L)
                .campaignId(201L)
                .bidderUserId(42L)
                .postId(1001L)
                .resourceType("FEED_TOP_SLOT")
                .bidAmount(120L)
                .reservePrice(100L)
                .windowStatus("OPEN")
                .status("SUBMITTED")
                .createdAt(Instant.parse("2026-06-20T10:05:00Z"))
                .updatedAt(Instant.parse("2026-06-20T10:05:00Z"))
                .build();
    }
}
