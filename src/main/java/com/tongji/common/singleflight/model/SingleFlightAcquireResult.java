package com.tongji.common.singleflight.model;

public record SingleFlightAcquireResult(
        SingleFlightAction action,
        Long ownerToken,
        SingleFlightStatus status,
        boolean retryable,
        SingleFlightErrorType errorType,
        String errorCode
) {
    public static SingleFlightAcquireResult ownerNew(long ownerToken) {
        return new SingleFlightAcquireResult(SingleFlightAction.OWNER_NEW, ownerToken, null, false, null, null);
    }

    public static SingleFlightAcquireResult ownerTakeover(long ownerToken) {
        return new SingleFlightAcquireResult(SingleFlightAction.OWNER_TAKEOVER, ownerToken, null, false, null, null);
    }

    public static SingleFlightAcquireResult followerWait(Long ownerToken) {
        return new SingleFlightAcquireResult(SingleFlightAction.FOLLOWER_WAIT, ownerToken, null, false, null, null);
    }

    public static SingleFlightAcquireResult replaySuccess() {
        return new SingleFlightAcquireResult(SingleFlightAction.REPLAY_SUCCESS, null, SingleFlightStatus.SUCCEEDED, false, null, null);
    }

    public static SingleFlightAcquireResult replayFailure(boolean retryable, SingleFlightErrorType errorType, String errorCode) {
        return new SingleFlightAcquireResult(SingleFlightAction.REPLAY_FAILURE, null, SingleFlightStatus.FAILED, retryable, errorType, errorCode);
    }
}
