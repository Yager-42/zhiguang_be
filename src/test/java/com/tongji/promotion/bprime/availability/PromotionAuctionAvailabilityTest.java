package com.tongji.promotion.bprime.availability;

import com.tongji.common.exception.BusinessException;
import com.tongji.common.exception.ErrorCode;
import com.tongji.promotion.bprime.config.PromotionBPrimeProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.ApplicationEventPublisher;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class PromotionAuctionAvailabilityTest {

    @Test
    void startsPausedUntilStartupRecoveryAcceptsTraffic() {
        PromotionAuctionAvailability availability = availability();

        assertThatThrownBy(availability::requireAvailable)
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> assertThat(((BusinessException) exception).getErrorCode())
                        .isEqualTo(ErrorCode.PROMOTION_AUCTION_PAUSED));

        availability.acceptAfterStartupRecovery();

        assertThat(availability.isAvailable()).isTrue();
        assertThat(availability.allowsProjection()).isTrue();
    }

    @Test
    void pausedProcessCannotReopenWithoutFullStartup() {
        PromotionAuctionAvailability availability = availability();
        availability.acceptAfterStartupRecovery();
        availability.pause();

        availability.acceptAfterStartupRecovery();

        assertThat(availability.isAvailable()).isFalse();
        assertThat(availability.allowsProjection()).isTrue();
        assertThatThrownBy(availability::requireAvailable)
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> assertThat(((BusinessException) exception).getErrorCode())
                        .isEqualTo(ErrorCode.PROMOTION_AUCTION_PAUSED));
    }

    @Test
    void pausedGateReassertsRefusingTrafficAfterExternalAcceptingEventWithoutRecursion() {
        ReadinessEventPublisher publisher = new ReadinessEventPublisher();
        PromotionAuctionAvailability availability = availability(publisher);
        publisher.listenTo(availability);
        availability.pause();
        publisher.clear();

        AvailabilityChangeEvent.publish(publisher, this, ReadinessState.ACCEPTING_TRAFFIC);

        assertThat(publisher.readinessStates()).containsExactly(
                ReadinessState.ACCEPTING_TRAFFIC,
                ReadinessState.REFUSING_TRAFFIC
        );
        assertThat(availability.isAvailable()).isFalse();
    }

    @Test
    void acceptingGateDoesNotRejectBootReadinessAfterStartupRecovery() {
        ReadinessEventPublisher publisher = new ReadinessEventPublisher();
        PromotionAuctionAvailability availability = availability(publisher);
        publisher.listenTo(availability);
        publisher.clear();

        availability.acceptAfterStartupRecovery();
        AvailabilityChangeEvent.publish(publisher, this, ReadinessState.ACCEPTING_TRAFFIC);

        assertThat(publisher.readinessStates()).containsExactly(
                ReadinessState.ACCEPTING_TRAFFIC,
                ReadinessState.ACCEPTING_TRAFFIC
        );
        assertThat(availability.isAvailable()).isTrue();
    }

    private PromotionAuctionAvailability availability() {
        return availability(mock(ApplicationEventPublisher.class));
    }

    private PromotionAuctionAvailability availability(ApplicationEventPublisher publisher) {
        PromotionBPrimeProperties properties = new PromotionBPrimeProperties();
        properties.setEnabled(true);
        return new PromotionAuctionAvailability(publisher, properties);
    }

    private static final class ReadinessEventPublisher implements ApplicationEventPublisher {
        private final List<AvailabilityChangeEvent<?>> events = new ArrayList<>();
        private PromotionAuctionAvailability availability;

        @Override
        public void publishEvent(Object event) {
            if (event instanceof AvailabilityChangeEvent<?> availabilityChangeEvent) {
                events.add(availabilityChangeEvent);
                if (availability != null) {
                    availability.reassertRefusingTraffic(availabilityChangeEvent);
                }
            }
        }

        void listenTo(PromotionAuctionAvailability availability) {
            this.availability = availability;
        }

        void clear() {
            events.clear();
        }

        List<ReadinessState> readinessStates() {
            return events.stream()
                    .map(event -> (ReadinessState) event.getState())
                    .toList();
        }
    }
}
