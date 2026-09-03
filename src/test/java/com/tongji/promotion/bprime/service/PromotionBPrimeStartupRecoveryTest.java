package com.tongji.promotion.bprime.service;

import com.tongji.promotion.bprime.availability.PromotionAuctionAvailabilityGate;
import com.tongji.promotion.schedule.PromotionAuctionDeadlineManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.ApplicationArguments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class PromotionBPrimeStartupRecoveryTest {

    @Mock private PromotionAuctionHotStateLifecycle hotStateLifecycle;
    @Mock private PromotionAuctionDeadlineManager deadlineManager;
    @Mock private PromotionAuctionAvailabilityGate availabilityGate;
    @Mock private ApplicationArguments applicationArguments;

    @Test
    void startupRecoversRegistryThenDeadlinesBeforeAcceptingTraffic() {
        PromotionBPrimeStartupRecovery recovery = new PromotionBPrimeStartupRecovery(
                hotStateLifecycle, deadlineManager, availabilityGate);

        recovery.run(applicationArguments);

        org.mockito.InOrder ordered = org.mockito.Mockito.inOrder(
                hotStateLifecycle, deadlineManager, availabilityGate);
        ordered.verify(hotStateLifecycle).recoverActiveWindows();
        ordered.verify(deadlineManager).recoverOpenWindows();
        ordered.verify(availabilityGate).acceptAfterStartupRecovery();
    }

    @Test
    void recoveryFailurePausesAndPropagatesStartupFailure() {
        IllegalStateException recoveryFailure = new IllegalStateException("Redis TIME unavailable");
        doThrow(recoveryFailure).when(hotStateLifecycle).recoverActiveWindows();
        PromotionBPrimeStartupRecovery recovery = new PromotionBPrimeStartupRecovery(
                hotStateLifecycle, deadlineManager, availabilityGate);

        Throwable thrown = catchThrowable(() -> recovery.run(applicationArguments));

        assertThat(thrown).isSameAs(recoveryFailure);
        verify(availabilityGate).pause();
        org.mockito.Mockito.verifyNoInteractions(deadlineManager);
    }
}
