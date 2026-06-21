package com.tongji.promotion.service;

import com.tongji.common.id.IdNamespace;
import com.tongji.common.id.IdService;
import com.tongji.promotion.config.PaidBoostProperties;
import com.tongji.promotion.mapper.PaidBoostDeliveryMapper;
import com.tongji.promotion.model.PaidBoostCampaign;
import com.tongji.promotion.model.PaidBoostCampaignStatus;
import com.tongji.promotion.model.PaidBoostChannel;
import com.tongji.promotion.model.PaidBoostDelivery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaidBoostDeliveryServiceTest {

    @Mock
    private PaidBoostDeliveryMapper deliveryMapper;

    @Mock
    private IdService idService;

    @Mock
    private Clock clock;

    private PaidBoostDeliveryService service;

    private final PaidBoostProperties properties = new PaidBoostProperties();

    @BeforeEach
    void setUp() {
        service = new PaidBoostDeliveryService(deliveryMapper, idService, properties, clock);
    }

    @Test
    void recordsDeliveryIntoBucketInsteadOfImmediateWalletCapture() {
        when(clock.instant()).thenReturn(Instant.parse("2026-06-21T10:00:05Z"));
        when(idService.nextId(IdNamespace.ADMIN_OPERATION)).thenReturn(11L);

        service.recordDeliveries(PaidBoostChannel.HOME_RECOMMENDATION, 42L,
                List.of(campaign(1L, 2L, 100L, 0L)));

        verify(deliveryMapper).upsertPending(argThat(d ->
                d.getCampaignId() == 1L
                        && d.getViewerUserId() == 42L
                        && d.getDeliveryCount() == 1
                        && d.getCapturedAmount() == 0L
                        && d.getSettleBusinessRef().equals("paid-boost:1:spend:202606211000:42")
        ));
    }

    @Test
    void repeatedDeliveryInSameBucketAggregatesIntoSameBillableFact() {
        // 两次送达都落在 10:00 bucket，settleRef 相同，由 mapper ON DUPLICATE KEY 累加 delivery_count
        when(clock.instant()).thenReturn(
                Instant.parse("2026-06-21T10:00:05Z"),
                Instant.parse("2026-06-21T10:00:25Z")
        );
        PaidBoostCampaign campaign = campaign(1L, 2L, 100L, 0L);

        service.recordDeliveries(PaidBoostChannel.HOME_RECOMMENDATION, 42L, List.of(campaign));
        service.recordDeliveries(PaidBoostChannel.HOME_RECOMMENDATION, 42L, List.of(campaign));

        verify(deliveryMapper, times(2)).upsertPending(argThat(d ->
                d.getCampaignId() == 1L
                        && d.getViewerUserId() == 42L
                        && d.getSettleBusinessRef().equals("paid-boost:1:spend:202606211000:42")
        ));
    }

    @Test
    void skipsCampaignWithExhaustedBudget() {
        when(clock.instant()).thenReturn(Instant.parse("2026-06-21T10:00:05Z"));

        service.recordDeliveries(PaidBoostChannel.HOME_RECOMMENDATION, 42L,
                List.of(campaign(1L, 2L, 100L, 100L)));

        verify(deliveryMapper, times(0)).upsertPending(any(PaidBoostDelivery.class));
    }

    private PaidBoostCampaign campaign(long id, long unitPrice, long budgetTotal, long consumed) {
        return PaidBoostCampaign.builder()
                .id(id).creatorUserId(42L).postId(1000L + id).channel(PaidBoostChannel.HOME_RECOMMENDATION)
                .bidAmount(30L).boostValue(30L).unitPrice(unitPrice).budgetTotal(budgetTotal).budgetConsumed(consumed)
                .reserveBusinessRef("paid-boost:" + id + ":reserve")
                .status(PaidBoostCampaignStatus.ACTIVE)
                .startAt(Instant.parse("2026-06-21T10:00:00Z"))
                .endAt(Instant.parse("2026-06-21T12:00:00Z"))
                .createdAt(Instant.parse("2026-06-21T10:00:00Z"))
                .updatedAt(Instant.parse("2026-06-21T10:00:00Z"))
                .build();
    }
}
