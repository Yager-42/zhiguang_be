package com.tongji.promotion.bprime.service;

import com.tongji.promotion.bprime.availability.PromotionAuctionAvailabilityGate;
import com.tongji.promotion.model.PromotionAuctionWindow;
import com.tongji.promotion.model.PromotionAuctionWindowStatus;
import com.tongji.promotion.model.PromotionResourceType;
import com.tongji.promotion.schedule.PromotionAuctionDeadlineManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class PromotionAuctionWindowRedisInitializerTest {

    private static final long WINDOW_ID = 301L;

    @Mock private PromotionAuctionHotStateLifecycle hotStateLifecycle;
    @Mock private PromotionAuctionDeadlineManager deadlineManager;
    @Mock private PromotionAuctionAvailabilityGate availabilityGate;

    private PromotionAuctionWindowRedisInitializer initializer;
    private PromotionAuctionWindow window;

    @BeforeEach
    void setUp() {
        initializer = new PromotionAuctionWindowRedisInitializer(hotStateLifecycle, deadlineManager, availabilityGate);
        window = PromotionAuctionWindow.builder()
                .id(WINDOW_ID)
                .resourceType(PromotionResourceType.FEED_TOP_SLOT)
                .windowStartAt(Instant.parse("2026-06-20T10:00:00Z"))
                .windowEndAt(Instant.parse("2026-06-20T11:00:00Z"))
                .slotCount(1)
                .reservePrice(1L)
                .status(PromotionAuctionWindowStatus.OPEN)
                .build();
    }

    @Test
    void schedulesDeadlineOnlyAfterHotStateActivation() {
        initializer.initialize(new PromotionAuctionWindowCreatedEvent(window));

        InOrder ordered = inOrder(hotStateLifecycle, deadlineManager, availabilityGate);
        ordered.verify(hotStateLifecycle).activateWindow(window);
        ordered.verify(deadlineManager).schedule(window);
        verify(availabilityGate, never()).pause();
    }

    @Test
    void scheduleFailurePreservesRecoveryStateAndPausesTraffic() {
        IllegalStateException scheduleFailure = new IllegalStateException("scheduler rejected");
        doThrow(scheduleFailure).when(deadlineManager).schedule(window);

        Throwable thrown = catchThrowable(() -> initializer.initialize(new PromotionAuctionWindowCreatedEvent(window)));

        assertThat(thrown).isSameAs(scheduleFailure);
        InOrder ordered = inOrder(hotStateLifecycle, deadlineManager, availabilityGate);
        ordered.verify(hotStateLifecycle).activateWindow(window);
        ordered.verify(deadlineManager).schedule(window);
        ordered.verify(availabilityGate).pause();
    }

    @Test
    void activationFailurePausesBeforeDeadlineRegistration() {
        IllegalStateException activationFailure = new IllegalStateException("active Stream registration failed");
        doThrow(activationFailure).when(hotStateLifecycle).activateWindow(window);

        Throwable thrown = catchThrowable(() -> initializer.initialize(new PromotionAuctionWindowCreatedEvent(window)));

        assertThat(thrown).isSameAs(activationFailure);
        verify(availabilityGate).pause();
        verify(deadlineManager, never()).schedule(window);
    }

}
