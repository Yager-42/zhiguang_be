package com.tongji.common.singleflight;

import com.tongji.common.singleflight.model.SingleFlightAcquireResult;
import com.tongji.common.singleflight.model.SingleFlightErrorType;
import com.tongji.common.singleflight.model.SingleFlightMetaSnapshot;
import com.tongji.common.singleflight.model.SingleFlightPolicy;
import com.tongji.common.singleflight.model.SingleFlightStoredResult;

public interface SingleFlightCoordinatorRepository {

    SingleFlightAcquireResult acquireOrJoin(String stage, String requestKey, String ownerId, SingleFlightPolicy policy);

    boolean markRunning(String requestKey, String ownerId, Long ownerToken, long runningTtlMillis);

    boolean heartbeat(String requestKey, String ownerId, Long ownerToken, long runningTtlMillis);

    boolean storeResult(String requestKey, String ownerId, Long ownerToken, SingleFlightStoredResult storedResult, long resultTtlMillis);

    boolean finishSuccess(String requestKey, String ownerId, Long ownerToken, long resultTtlMillis);

    boolean finishFailure(String requestKey, String ownerId, Long ownerToken, SingleFlightErrorType errorType,
                          String errorCode, boolean retryable, long ttlMillis);

    SingleFlightStoredResult getStoredResult(String requestKey);

    SingleFlightMetaSnapshot getMeta(String requestKey);
}
