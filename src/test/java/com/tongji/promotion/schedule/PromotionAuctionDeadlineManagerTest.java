package com.tongji.promotion.schedule;

import com.tongji.promotion.bprime.availability.PromotionAuctionAvailabilityGate;
import com.tongji.promotion.bprime.model.PromotionAuctionDecision;
import com.tongji.promotion.bprime.redis.PromotionAuctionUnavailableException;
import com.tongji.promotion.bprime.redis.PromotionRedisCloseOutcome;
import com.tongji.promotion.bprime.redis.PromotionRedisWindowCloser;
import com.tongji.promotion.mapper.PromotionAuctionWindowMapper;
import com.tongji.promotion.model.PromotionAuctionWindow;
import com.tongji.promotion.model.PromotionAuctionWindowStatus;
import com.tongji.promotion.model.PromotionResourceType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.Answer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PromotionAuctionDeadlineManagerTest {

    private static final Instant SCHEDULER_NOW = Instant.parse("2026-09-03T10:00:00Z");

    @Mock
    private PromotionAuctionWindowMapper windowMapper;
    @Mock
    private PromotionRedisWindowCloser windowCloser;
    @Mock
    private PromotionAuctionAvailabilityGate availabilityGate;
    @Mock
    private ThreadPoolTaskScheduler scheduler;
    @Mock
    private ScheduledFuture<?> firstFuture;
    @Mock
    private ScheduledFuture<?> secondFuture;
    @Mock
    private ScheduledFuture<?> thirdFuture;
    @Mock
    private ScheduledFuture<?> fourthFuture;

    private AtomicLong redisNowEpochMs;
    private PromotionAuctionDeadlineManager manager;

    @BeforeEach
    void setUp() {
        redisNowEpochMs = new AtomicLong(SCHEDULER_NOW.toEpochMilli());
        lenient().when(scheduler.getClock()).thenReturn(Clock.fixed(SCHEDULER_NOW, ZoneOffset.UTC));
        manager = new PromotionAuctionDeadlineManager(
                windowMapper, windowCloser, availabilityGate, scheduler, redisNowEpochMs::get);
    }

    @Test
    void futureDeadlineUsesRedisRelativeDelay() {
        stubSchedulerReturns(firstFuture);
        PromotionAuctionWindow window = window(301L, SCHEDULER_NOW.plusSeconds(7));

        manager.schedule(window);

        ArgumentCaptor<Instant> triggerAt = ArgumentCaptor.forClass(Instant.class);
        verify(scheduler).schedule(any(Runnable.class), triggerAt.capture());
        assertThat(triggerAt.getValue()).isEqualTo(SCHEDULER_NOW.plusSeconds(7));
        verify(windowCloser, never()).close(301L);
    }

    @Test
    void elapsedDeadlineRunsCloseAtOnce() {
        stubSchedulerReturns(firstFuture);
        PromotionAuctionWindow window = window(301L, SCHEDULER_NOW.minusMillis(1));
        when(windowCloser.close(301L)).thenReturn(new PromotionRedisCloseOutcome.AlreadyTerminal());

        manager.schedule(window);

        ArgumentCaptor<Runnable> scheduledTask = ArgumentCaptor.forClass(Runnable.class);
        ArgumentCaptor<Instant> triggerAt = ArgumentCaptor.forClass(Instant.class);
        verify(scheduler).schedule(scheduledTask.capture(), triggerAt.capture());
        assertThat(triggerAt.getValue()).isEqualTo(SCHEDULER_NOW);
        scheduledTask.getValue().run();
        verify(windowCloser).close(301L);
    }

    @Test
    void notDueUsesRedisOutcomeToReschedule() {
        stubSchedulerReturns(firstFuture, secondFuture);
        PromotionAuctionWindow window = window(301L, SCHEDULER_NOW.plusSeconds(2));
        when(windowCloser.close(301L)).thenReturn(new PromotionRedisCloseOutcome.NotDue(
                SCHEDULER_NOW.plusSeconds(3).toEpochMilli(),
                SCHEDULER_NOW.plusSeconds(11).toEpochMilli()));

        manager.schedule(window);
        ArgumentCaptor<Runnable> initialTask = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).schedule(initialTask.capture(), any(Instant.class));
        initialTask.getValue().run();

        ArgumentCaptor<Instant> triggerTimes = ArgumentCaptor.forClass(Instant.class);
        verify(scheduler, times(2)).schedule(any(Runnable.class), triggerTimes.capture());
        assertThat(triggerTimes.getAllValues().get(1)).isEqualTo(SCHEDULER_NOW.plusSeconds(8));
    }

    @Test
    void duplicateStaticDeadlineKeepsOneScheduledFuture() {
        stubSchedulerReturns(firstFuture);
        PromotionAuctionWindow window = window(301L, SCHEDULER_NOW.plusSeconds(7));

        manager.schedule(window);
        redisNowEpochMs.addAndGet(1_000L);
        manager.schedule(window);

        verify(scheduler).schedule(any(Runnable.class), any(Instant.class));
        verify(firstFuture, never()).cancel(false);
    }

    @Test
    void changedDeadlineForRegisteredWindowIsRejected() {
        stubSchedulerReturns(firstFuture);
        manager.schedule(window(301L, SCHEDULER_NOW.plusSeconds(7)));

        assertThatThrownBy(() -> manager.schedule(window(301L, SCHEDULER_NOW.plusSeconds(8))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("immutable");

        verify(scheduler).schedule(any(Runnable.class), any(Instant.class));
        verify(firstFuture, never()).cancel(false);
    }

    @Test
    void schedulerRejectionDoesNotPublishResidualTask() {
        when(scheduler.schedule(any(Runnable.class), any(Instant.class)))
                .thenThrow(new RejectedExecutionException("scheduler stopped"))
                .thenAnswer(invocation -> firstFuture);
        PromotionAuctionWindow window = window(301L, SCHEDULER_NOW.plusSeconds(7));

        assertThatThrownBy(() -> manager.schedule(window))
                .isInstanceOf(RejectedExecutionException.class)
                .hasMessageContaining("scheduler stopped");
        manager.schedule(window);

        verify(scheduler, times(2)).schedule(any(Runnable.class), any(Instant.class));
    }

    @Test
    void redisTimeFailureLeavesNoScheduledTask() {
        PromotionAuctionDeadlineManager unavailableManager = new PromotionAuctionDeadlineManager(
                windowMapper,
                windowCloser,
                availabilityGate,
                scheduler,
                () -> {
                    throw new PromotionAuctionUnavailableException("Redis TIME unavailable");
                });

        assertThatThrownBy(() -> unavailableManager.schedule(
                window(301L, SCHEDULER_NOW.plusSeconds(7))))
                .isInstanceOf(PromotionAuctionUnavailableException.class)
                .hasMessageContaining("Redis TIME unavailable");

        verify(scheduler, never()).schedule(any(Runnable.class), any(Instant.class));
    }

    @Test
    void cancelRemovesFutureAndAllowsFreshRegistration() {
        stubSchedulerReturns(firstFuture, secondFuture);
        PromotionAuctionWindow window = window(301L, SCHEDULER_NOW.plusSeconds(7));

        manager.schedule(window);
        manager.cancel(301L);
        manager.cancel(301L);
        manager.schedule(window);

        verify(firstFuture).cancel(false);
        verify(scheduler, times(2)).schedule(any(Runnable.class), any(Instant.class));
    }

    @Test
    void closedAndAlreadyTerminalOutcomesRemoveTheirTasks() {
        stubSchedulerReturns(firstFuture, secondFuture, thirdFuture, fourthFuture);
        PromotionAuctionWindow closedWindow = window(301L, SCHEDULER_NOW);
        PromotionAuctionWindow terminalWindow = window(302L, SCHEDULER_NOW);
        when(windowCloser.close(301L)).thenReturn(
                new PromotionRedisCloseOutcome.Closed(org.mockito.Mockito.mock(PromotionAuctionDecision.class)));
        when(windowCloser.close(302L)).thenReturn(new PromotionRedisCloseOutcome.AlreadyTerminal());

        manager.schedule(closedWindow);
        manager.schedule(terminalWindow);
        ArgumentCaptor<Runnable> tasks = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler, times(2)).schedule(tasks.capture(), any(Instant.class));
        tasks.getAllValues().forEach(Runnable::run);

        manager.schedule(closedWindow);
        manager.schedule(terminalWindow);
        verify(scheduler, times(4)).schedule(any(Runnable.class), any(Instant.class));
    }

    @Test
    void closeFailureKeepsTaskRegisteredForBoundedBackoffRetry() {
        stubSchedulerReturns(firstFuture, secondFuture);
        PromotionAuctionWindow window = window(301L, SCHEDULER_NOW);
        when(windowCloser.close(301L)).thenThrow(new IllegalStateException("Redis unavailable"));

        manager.schedule(window);
        ArgumentCaptor<Runnable> initialTask = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).schedule(initialTask.capture(), any(Instant.class));
        initialTask.getValue().run();

        ArgumentCaptor<Instant> triggerTimes = ArgumentCaptor.forClass(Instant.class);
        verify(scheduler, times(2)).schedule(any(Runnable.class), triggerTimes.capture());
        assertThat(triggerTimes.getAllValues().get(1)).isEqualTo(SCHEDULER_NOW.plusSeconds(1));
        manager.schedule(window);
        verify(scheduler, times(2)).schedule(any(Runnable.class), any(Instant.class));
    }

    @Test
    void rescheduleRejectionPausesTrafficAndRetiresOnlyTheLocalTask() {
        PromotionAuctionWindow window = window(301L, SCHEDULER_NOW);
        when(windowCloser.close(301L)).thenReturn(new PromotionRedisCloseOutcome.NotDue(
                SCHEDULER_NOW.plusSeconds(1).toEpochMilli(),
                SCHEDULER_NOW.plusSeconds(11).toEpochMilli()));
        Answer<ScheduledFuture<?>> initialSchedule = invocation -> firstFuture;
        when(scheduler.schedule(any(Runnable.class), any(Instant.class)))
                .thenAnswer(initialSchedule)
                .thenThrow(new RejectedExecutionException("scheduler stopped"));

        manager.schedule(window);
        ArgumentCaptor<Runnable> initialTask = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).schedule(initialTask.capture(), any(Instant.class));

        assertThatThrownBy(() -> initialTask.getValue().run())
                .isInstanceOf(RejectedExecutionException.class)
                .hasMessageContaining("scheduler stopped");

        verify(availabilityGate).pause();
        manager.cancel(301L);
        verify(firstFuture, never()).cancel(false);
    }

    @Test
    void recoverOpenWindowsSchedulesEveryMysqlOpenWindow() {
        stubSchedulerReturns(firstFuture, secondFuture);
        PromotionAuctionWindow first = window(301L, SCHEDULER_NOW.plusSeconds(3));
        PromotionAuctionWindow second = window(302L, SCHEDULER_NOW.plusSeconds(4));
        when(windowMapper.listActiveWindows()).thenReturn(List.of(first, second));

        manager.recoverOpenWindows();

        verify(windowMapper).listActiveWindows();
        verify(scheduler, times(2)).schedule(any(Runnable.class), any(Instant.class));
        verify(windowCloser, never()).close(anyLong());
        verifyNoMoreInteractions(windowMapper);
    }

    @Test
    void shutdownCancelsTasksAndRejectsNewRegistration() {
        stubSchedulerReturns(firstFuture, secondFuture);
        PromotionAuctionWindow first = window(301L, SCHEDULER_NOW.plusSeconds(3));
        PromotionAuctionWindow second = window(302L, SCHEDULER_NOW.plusSeconds(4));
        manager.schedule(first);
        manager.schedule(second);

        manager.shutdown();

        verify(firstFuture).cancel(false);
        verify(secondFuture).cancel(false);
        assertThatThrownBy(() -> manager.schedule(first))
                .isInstanceOf(RejectedExecutionException.class)
                .hasMessageContaining("shut down");
        verify(scheduler, times(2)).schedule(any(Runnable.class), any(Instant.class));
    }

    private void stubSchedulerReturns(ScheduledFuture<?>... futures) {
        AtomicInteger index = new AtomicInteger();
        when(scheduler.schedule(any(Runnable.class), any(Instant.class)))
                .thenAnswer(invocation -> futures[Math.min(index.getAndIncrement(), futures.length - 1)]);
    }

    private PromotionAuctionWindow window(long id, Instant deadline) {
        return PromotionAuctionWindow.builder()
                .id(id)
                .resourceType(PromotionResourceType.FEED_TOP_SLOT)
                .windowStartAt(deadline.minusSeconds(60))
                .windowEndAt(deadline)
                .slotCount(1)
                .reservePrice(1L)
                .status(PromotionAuctionWindowStatus.OPEN)
                .createdAt(deadline.minusSeconds(60))
                .updatedAt(deadline.minusSeconds(60))
                .build();
    }
}
