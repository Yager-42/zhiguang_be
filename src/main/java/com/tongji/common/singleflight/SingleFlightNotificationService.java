package com.tongji.common.singleflight;

import com.tongji.common.singleflight.model.SingleFlightErrorType;
import com.tongji.common.singleflight.model.SingleFlightStatus;

public interface SingleFlightNotificationService {

    void publish(String requestKey, String event, SingleFlightStatus status, Long ownerToken,
                 SingleFlightErrorType errorType, boolean retryable, long streamTtlMillis);

    String currentEventOffset(String requestKey);

    void waitForTerminalEvent(String requestKey, String afterEventOffset, long blockTimeoutMillis);
}
