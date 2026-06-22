package com.tongji.promotion.bprime.service;

import com.tongji.common.exception.BusinessException;
import com.tongji.common.exception.ErrorCode;
import com.tongji.common.id.IdNamespace;
import com.tongji.common.id.IdService;
import com.tongji.promotion.api.dto.SubmitPromotionBidCommandResponse;
import com.tongji.promotion.bprime.config.PromotionBPrimeProperties;
import com.tongji.promotion.bprime.mapper.PromotionAuctionCommandMapper;
import com.tongji.promotion.bprime.model.PromotionAuctionCommandRecord;
import com.tongji.promotion.bprime.mq.PromotionCommandMessagePort;
import com.tongji.promotion.mapper.PromotionAuctionWindowMapper;
import com.tongji.promotion.mapper.PromotionCampaignMapper;
import com.tongji.promotion.model.PromotionAuctionWindow;
import com.tongji.promotion.model.PromotionAuctionWindowStatus;
import com.tongji.promotion.model.PromotionCampaign;
import com.tongji.promotion.model.PromotionCampaignStatus;
import com.tongji.promotion.model.PromotionResourceType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PromotionCommandSubmissionServiceTest {

    @Mock
    private PromotionCampaignMapper campaignMapper;
    @Mock
    private PromotionAuctionWindowMapper windowMapper;
    @Mock
    private PromotionAuctionCommandMapper commandMapper;
    @Mock
    private PromotionCommandMessagePort messagePort;
    @Mock
    private IdService idService;

    private PromotionBPrimeProperties properties;
    private PromotionCommandSubmissionService service;

    @BeforeEach
    void setUp() {
        properties = new PromotionBPrimeProperties();
        service = new PromotionCommandSubmissionService(campaignMapper, windowMapper, commandMapper,
                messagePort, properties, idService);
    }

    @Test
    void submitCreatesCommandOnlyWhenBprimeDisabled() {
        Instant now = Instant.parse("2026-06-20T10:05:00Z");
        when(campaignMapper.findById(201L)).thenReturn(campaign());
        when(windowMapper.findOpenWindow(PromotionResourceType.FEED_TOP_SLOT, now)).thenReturn(window());
        when(idService.nextId(IdNamespace.ADMIN_OPERATION)).thenReturn(9001L, 9002L);

        SubmitPromotionBidCommandResponse response = service.submit(42L, 201L, 120L, "idem-1", now);

        ArgumentCaptor<PromotionAuctionCommandRecord> captor = ArgumentCaptor.forClass(PromotionAuctionCommandRecord.class);
        verify(commandMapper).insert(captor.capture());
        verify(messagePort, never()).send(any());
        assertThat(captor.getValue().getCommandId()).isEqualTo("promotion-bprime-9001");
        assertThat(captor.getValue().getStatus()).isEqualTo("SUBMITTED");
        assertThat(response.commandId()).isEqualTo("promotion-bprime-9001");
        assertThat(response.status()).isEqualTo("SUBMITTED");
    }

    @Test
    void submitPublishesAndMarksPublishedWhenEnabled() {
        properties.setEnabled(true);
        Instant now = Instant.parse("2026-06-20T10:05:00Z");
        when(campaignMapper.findById(201L)).thenReturn(campaign());
        when(windowMapper.findOpenWindow(PromotionResourceType.FEED_TOP_SLOT, now)).thenReturn(window());
        when(idService.nextId(IdNamespace.ADMIN_OPERATION)).thenReturn(9001L, 9002L);

        SubmitPromotionBidCommandResponse response = service.submit(42L, 201L, 120L, "idem-1", now);

        verify(messagePort).send(any());
        verify(commandMapper).updateStatus("promotion-bprime-9001", "PUBLISHED");
        assertThat(response.status()).isEqualTo("PUBLISHED");
    }

    @Test
    void submitPublishesAfterTransactionCommitWhenSynchronizationActive() {
        properties.setEnabled(true);
        Instant now = Instant.parse("2026-06-20T10:05:00Z");
        when(campaignMapper.findById(201L)).thenReturn(campaign());
        when(windowMapper.findOpenWindow(PromotionResourceType.FEED_TOP_SLOT, now)).thenReturn(window());
        when(idService.nextId(IdNamespace.ADMIN_OPERATION)).thenReturn(9001L, 9002L);
        TransactionSynchronizationManager.initSynchronization();
        try {
            SubmitPromotionBidCommandResponse response = service.submit(42L, 201L, 120L, "idem-1", now);

            verify(messagePort, never()).send(any());
            verify(commandMapper, never()).updateStatus("promotion-bprime-9001", "PUBLISHED");
            assertThat(response.status()).isEqualTo("SUBMITTED");

            for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
                synchronization.afterCommit();
            }

            verify(messagePort).send(any());
            verify(commandMapper).updateStatus("promotion-bprime-9001", "PUBLISHED");
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void sameIdempotencyAndSameHashReturnsExistingCommand() {
        Instant now = Instant.parse("2026-06-20T10:05:00Z");
        when(campaignMapper.findById(201L)).thenReturn(campaign());
        when(windowMapper.findOpenWindow(PromotionResourceType.FEED_TOP_SLOT, now)).thenReturn(window());
        PromotionAuctionCommandRecord existing = existingCommand(
                service.requestHash(201L, 42L, 301L, 120L, "idem-1"));
        when(commandMapper.findByIdempotency(301L, 42L, "idem-1")).thenReturn(existing);

        SubmitPromotionBidCommandResponse response = service.submit(42L, 201L, 120L, "idem-1", now);

        assertThat(response.commandId()).isEqualTo("cmd-existing");
        verify(commandMapper, never()).insert(any());
    }

    @Test
    void sameIdempotencyDifferentHashRejects() {
        Instant now = Instant.parse("2026-06-20T10:05:00Z");
        when(campaignMapper.findById(201L)).thenReturn(campaign());
        when(windowMapper.findOpenWindow(PromotionResourceType.FEED_TOP_SLOT, now)).thenReturn(window());
        when(commandMapper.findByIdempotency(301L, 42L, "idem-1")).thenReturn(existingCommand("different"));

        assertThatThrownBy(() -> service.submit(42L, 201L, 120L, "idem-1", now))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.BAD_REQUEST);
    }

    private PromotionAuctionCommandRecord existingCommand(String requestHash) {
        return PromotionAuctionCommandRecord.builder()
                .id(1L)
                .commandId("cmd-existing")
                .idempotencyKey("idem-1")
                .requestHash(requestHash)
                .auctionWindowId(301L)
                .campaignId(201L)
                .bidderUserId(42L)
                .postId(1001L)
                .resourceType("FEED_TOP_SLOT")
                .bidAmount(120L)
                .status("SUBMITTED")
                .createdAt(Instant.parse("2026-06-20T10:05:00Z"))
                .updatedAt(Instant.parse("2026-06-20T10:05:00Z"))
                .build();
    }

    private PromotionCampaign campaign() {
        return PromotionCampaign.builder()
                .id(201L)
                .creatorUserId(42L)
                .postId(1001L)
                .resourceType(PromotionResourceType.FEED_TOP_SLOT)
                .status(PromotionCampaignStatus.ACTIVE)
                .startAt(Instant.parse("2026-06-20T10:00:00Z"))
                .endAt(Instant.parse("2026-06-20T12:00:00Z"))
                .build();
    }

    private PromotionAuctionWindow window() {
        return PromotionAuctionWindow.builder()
                .id(301L)
                .resourceType(PromotionResourceType.FEED_TOP_SLOT)
                .windowStartAt(Instant.parse("2026-06-20T10:00:00Z"))
                .windowEndAt(Instant.parse("2026-06-20T11:00:00Z"))
                .slotCount(1)
                .reservePrice(1L)
                .status(PromotionAuctionWindowStatus.OPEN)
                .build();
    }
}
