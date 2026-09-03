package com.tongji.promotion.bprime.availability;

import com.tongji.common.exception.BusinessException;
import com.tongji.common.exception.ErrorCode;
import com.tongji.promotion.bprime.config.PromotionBPrimeProperties;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * bprime 收单可用性的不可逆进程闸门。
 *
 * <p>实例必须完成启动围栏和全部 deadline 恢复后才可收单。运行期失败只能转为拒绝，
 * 不能在当前进程重新开放。</p>
 */
@Component
public class PromotionAuctionAvailability implements PromotionAuctionAvailabilityGate {

    private final ApplicationEventPublisher eventPublisher;
    private final AtomicReference<State> state;
    private final AtomicBoolean startupFenceComplete = new AtomicBoolean();

    public PromotionAuctionAvailability(ApplicationEventPublisher eventPublisher,
                                        PromotionBPrimeProperties properties) {
        this.eventPublisher = eventPublisher;
        this.state = new AtomicReference<>(properties.isEnabled() ? State.STARTING : State.ACCEPTING);
        if (properties.isEnabled()) {
            AvailabilityChangeEvent.publish(eventPublisher, this, ReadinessState.REFUSING_TRAFFIC);
        }
    }

    @Override
    public void requireAvailable() {
        if (!isAvailable()) {
            throw new BusinessException(ErrorCode.PROMOTION_AUCTION_PAUSED);
        }
    }

    @Override
    public void acceptAfterStartupRecovery() {
        if (state.compareAndSet(State.STARTING, State.ACCEPTING)) {
            startupFenceComplete.set(true);
            AvailabilityChangeEvent.publish(eventPublisher, this, ReadinessState.ACCEPTING_TRAFFIC);
        }
    }

    @Override
    public void pause() {
        State previous = state.getAndSet(State.PAUSED);
        if (previous != State.PAUSED) {
            AvailabilityChangeEvent.publish(eventPublisher, this, ReadinessState.REFUSING_TRAFFIC);
        }
    }

    /**
     * Spring Boot publishes its final accepting readiness event after application runners.
     * A paused auction gate must immediately restore its refusing readiness state.
     */
    @EventListener
    public void reassertRefusingTraffic(AvailabilityChangeEvent<?> event) {
        if (event.getState() == ReadinessState.ACCEPTING_TRAFFIC && state.get() == State.PAUSED) {
            AvailabilityChangeEvent.publish(eventPublisher, this, ReadinessState.REFUSING_TRAFFIC);
        }
    }

    @Override
    public boolean allowsProjection() {
        return startupFenceComplete.get();
    }

    @Override
    public boolean isAvailable() {
        return state.get() == State.ACCEPTING;
    }

    private enum State {
        STARTING,
        ACCEPTING,
        PAUSED
    }
}
