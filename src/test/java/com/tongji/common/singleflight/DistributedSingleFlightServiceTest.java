package com.tongji.common.singleflight;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.common.singleflight.model.SingleFlightAcquireResult;
import com.tongji.common.singleflight.model.SingleFlightErrorType;
import com.tongji.common.singleflight.model.SingleFlightMetaSnapshot;
import com.tongji.common.singleflight.model.SingleFlightPolicy;
import com.tongji.common.singleflight.model.SingleFlightStatus;
import com.tongji.common.singleflight.model.SingleFlightStoredResult;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DistributedSingleFlightServiceTest {

    private static final TypeReference<Map<String, Long>> MAP_TYPE = new TypeReference<>() {
    };

    private final SingleFlightProperties properties = new SingleFlightProperties();
    private final RecordingCoordinatorRepository repository = new RecordingCoordinatorRepository();
    private final SingleFlightResultCodec codec = new SingleFlightResultCodec(new ObjectMapper().findAndRegisterModules());
    private final SingleFlightHeartbeatManager heartbeatManager = new SingleFlightHeartbeatManager();
    private final DistributedSingleFlightService service = new DistributedSingleFlightService(
            properties,
            new LocalSingleFlightService(),
            repository,
            new NoopNotificationService(),
            heartbeatManager,
            codec,
            new SingleFlightLocalReplayCache()
    );

    @Test
    void ownerStoresResultAndLocalReplayAvoidsSecondSupplierCall() {
        repository.nextAcquireResult = SingleFlightAcquireResult.ownerNew(1L);
        AtomicInteger supplierCalls = new AtomicInteger();

        Map<String, Long> first = service.execute("counter-sds", "knowpost:1:like",
                MAP_TYPE,
                () -> {
                    supplierCalls.incrementAndGet();
                    return mapOf("like", 3L);
                });
        Map<String, Long> second = service.execute("counter-sds", "knowpost:1:like",
                MAP_TYPE,
                () -> {
                    supplierCalls.incrementAndGet();
                    return mapOf("like", 9L);
                });

        assertThat(first).containsEntry("like", 3L);
        assertThat(second).containsEntry("like", 3L);
        assertThat(supplierCalls).hasValue(1);
        assertThat(repository.lastAcquireRequestKey).isEqualTo("counter-sds:knowpost:1:like");
        assertThat(repository.lastStoreResultRequestKey).isEqualTo("counter-sds:knowpost:1:like");
        assertThat(repository.storeResultCalls).isEqualTo(1);
        assertThat(repository.finishSuccessCalls).isEqualTo(1);
    }

    @Test
    void followerReadsStoredSuccessReplayWithoutCallingSupplier() {
        repository.nextAcquireResult = SingleFlightAcquireResult.followerWait(7L);
        repository.metaSnapshot = new SingleFlightMetaSnapshot(
                "counter-sds",
                SingleFlightStatus.SUCCEEDED,
                "owner",
                7L,
                System.currentTimeMillis(),
                false,
                null,
                null
        );
        repository.storedResult = codec.serialize(mapOf("like", 5L), 7L, defaultPolicy());
        AtomicInteger supplierCalls = new AtomicInteger();

        Map<String, Long> result = service.execute("counter-sds", "knowpost:2:like",
                MAP_TYPE,
                () -> {
                    supplierCalls.incrementAndGet();
                    return mapOf("like", 0L);
                });

        assertThat(result).containsEntry("like", 5L);
        assertThat(supplierCalls).hasValue(0);
    }

    @Test
    void ownerFailureStoresNonRetryableFailure() {
        repository.nextAcquireResult = SingleFlightAcquireResult.ownerNew(3L);

        assertThatThrownBy(() -> service.execute("counter-sds", "bad",
                MAP_TYPE,
                () -> {
                    throw new IllegalArgumentException("bad key");
                }))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("bad key");

        assertThat(repository.finishFailureCalls).isEqualTo(1);
        assertThat(repository.lastErrorType).isEqualTo(SingleFlightErrorType.VALIDATION);
        assertThat(repository.lastRetryable).isFalse();
    }

    @Test
    void terminalPublishFailureDoesNotMaskOwnerSuccess() {
        DistributedSingleFlightService publishFailingService = new DistributedSingleFlightService(
                properties,
                new LocalSingleFlightService(),
                repository,
                new ThrowingNotificationService(),
                heartbeatManager,
                codec,
                new SingleFlightLocalReplayCache()
        );
        repository.nextAcquireResult = SingleFlightAcquireResult.ownerNew(12L);

        Map<String, Long> result = publishFailingService.execute("counter-sds", "notify-fails",
                MAP_TYPE,
                () -> mapOf("like", 6L));

        assertThat(result).containsEntry("like", 6L);
        assertThat(repository.finishSuccessCalls).isEqualTo(1);
        assertThat(repository.finishFailureCalls).isZero();
    }

    @Test
    void rejectsBlankStageAndRequestKey() {
        assertThatThrownBy(() -> service.execute("", "key", MAP_TYPE, Map::of))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("stage must not be blank");
        assertThatThrownBy(() -> service.execute("stage", " ", MAP_TYPE, Map::of))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("requestKey must not be blank");
    }

    @Test
    void disabledModeRunsSupplierWithoutRepositoryAccess() {
        properties.setEnabled(false);
        AtomicInteger supplierCalls = new AtomicInteger();

        Map<String, Long> result = service.execute("counter-sds", "knowpost:3:like",
                MAP_TYPE,
                () -> {
                    supplierCalls.incrementAndGet();
                    return mapOf("like", 8L);
                });

        assertThat(result).containsEntry("like", 8L);
        assertThat(supplierCalls).hasValue(1);
        assertThat(repository.acquireCalls).isZero();
    }

    @Test
    void stageDisabledRunsSupplierWithoutRepositoryAccess() {
        SingleFlightProperties.SingleFlightPolicyDefaults stagePolicy = new SingleFlightProperties.SingleFlightPolicyDefaults();
        stagePolicy.setEnabled(false);
        properties.getStages().put("counter-sds", stagePolicy);
        AtomicInteger supplierCalls = new AtomicInteger();

        Map<String, Long> result = service.execute("counter-sds", "stage-disabled",
                MAP_TYPE,
                () -> {
                    supplierCalls.incrementAndGet();
                    return mapOf("like", 4L);
                });

        assertThat(result).containsEntry("like", 4L);
        assertThat(supplierCalls).hasValue(1);
        assertThat(repository.acquireCalls).isZero();
    }

    @Test
    void localModeRunsSupplierWithoutRepositoryAccess() {
        properties.setMode("local");
        AtomicInteger supplierCalls = new AtomicInteger();

        Map<String, Long> result = service.execute("counter-sds", "knowpost:local:like",
                MAP_TYPE,
                () -> {
                    supplierCalls.incrementAndGet();
                    return mapOf("like", 10L);
                });

        assertThat(result).containsEntry("like", 10L);
        assertThat(supplierCalls).hasValue(1);
        assertThat(repository.acquireCalls).isZero();
    }

    @Test
    void stageLocalModeRunsSupplierWithoutRepositoryAccess() {
        SingleFlightProperties.SingleFlightPolicyDefaults stagePolicy = new SingleFlightProperties.SingleFlightPolicyDefaults();
        stagePolicy.setMode("local");
        properties.getStages().put("counter-sds", stagePolicy);
        AtomicInteger supplierCalls = new AtomicInteger();

        Map<String, Long> result = service.execute("counter-sds", "stage-local",
                MAP_TYPE,
                () -> {
                    supplierCalls.incrementAndGet();
                    return mapOf("like", 14L);
                });

        assertThat(result).containsEntry("like", 14L);
        assertThat(supplierCalls).hasValue(1);
        assertThat(repository.acquireCalls).isZero();
    }

    @Test
    void hybridModeFallsBackToLocalWhenDistributedCoordinatorFails() {
        properties.setMode("hybrid");
        repository.throwOnAcquire = true;
        AtomicInteger supplierCalls = new AtomicInteger();

        Map<String, Long> result = service.execute("counter-sds", "knowpost:4:like",
                MAP_TYPE,
                () -> {
                    supplierCalls.incrementAndGet();
                    return mapOf("like", 11L);
                });

        assertThat(result).containsEntry("like", 11L);
        assertThat(supplierCalls).hasValue(1);
        assertThat(repository.acquireCalls).isEqualTo(1);
    }

    @Test
    void hybridModeDoesNotFallbackToLocalForReplayFailure() {
        properties.setMode("hybrid");
        repository.nextAcquireResult = SingleFlightAcquireResult.replayFailure(false, SingleFlightErrorType.VALIDATION, "bad");
        AtomicInteger supplierCalls = new AtomicInteger();

        assertThatThrownBy(() -> service.execute("counter-sds", "hybrid-replay-failure",
                MAP_TYPE,
                () -> {
                    supplierCalls.incrementAndGet();
                    return mapOf("like", 12L);
                }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bad");

        assertThat(supplierCalls).hasValue(0);
    }

    @Test
    void hybridModeDoesNotFallbackToLocalAfterOwnerSupplierRanAndStoreFailed() {
        properties.setMode("hybrid");
        repository.nextAcquireResult = SingleFlightAcquireResult.ownerNew(15L);
        repository.failStoreResult = true;
        AtomicInteger supplierCalls = new AtomicInteger();

        assertThatThrownBy(() -> service.execute("counter-sds", "hybrid-store-fails",
                MAP_TYPE,
                () -> {
                    supplierCalls.incrementAndGet();
                    return mapOf("like", 15L);
                }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("failed to store distributed single-flight result");

        assertThat(supplierCalls).hasValue(1);
    }

    @Test
    void ownerTakeoverExecutesSupplierAndStoresResult() {
        repository.nextAcquireResult = SingleFlightAcquireResult.ownerTakeover(9L);

        Map<String, Long> result = service.execute("counter-sds", "knowpost:takeover:like",
                MAP_TYPE,
                () -> mapOf("like", 13L));

        assertThat(result).containsEntry("like", 13L);
        assertThat(repository.storeResultCalls).isEqualTo(1);
        assertThat(repository.finishSuccessCalls).isEqualTo(1);
    }

    @Test
    void retryableOwnerFailureStoresRetryableFailure() {
        repository.nextAcquireResult = SingleFlightAcquireResult.ownerNew(5L);

        assertThatThrownBy(() -> service.execute("counter-sds", "overload",
                MAP_TYPE,
                () -> {
                    throw new RejectedExecutionException("busy");
                }))
                .isInstanceOf(RejectedExecutionException.class)
                .hasMessage("busy");

        assertThat(repository.finishFailureCalls).isEqualTo(1);
        assertThat(repository.lastErrorType).isEqualTo(SingleFlightErrorType.OVERLOAD);
        assertThat(repository.lastRetryable).isTrue();
    }

    private Map<String, Long> mapOf(String key, Long value) {
        Map<String, Long> map = new LinkedHashMap<>();
        map.put(key, value);
        return map;
    }

    private SingleFlightPolicy defaultPolicy() {
        return properties.resolvePolicy("counter-sds");
    }

    private static final class NoopNotificationService implements SingleFlightNotificationService {
        @Override
        public void publish(String requestKey, String event, SingleFlightStatus status, Long ownerToken,
                            SingleFlightErrorType errorType, boolean retryable, long streamTtlMillis) {
        }

        @Override
        public String currentEventOffset(String requestKey) {
            return "$";
        }

        @Override
        public void waitForTerminalEvent(String requestKey, String afterEventOffset, long blockTimeoutMillis) {
        }
    }

    private static final class ThrowingNotificationService implements SingleFlightNotificationService {
        @Override
        public void publish(String requestKey, String event, SingleFlightStatus status, Long ownerToken,
                            SingleFlightErrorType errorType, boolean retryable, long streamTtlMillis) {
            throw new IllegalStateException("stream unavailable");
        }

        @Override
        public String currentEventOffset(String requestKey) {
            return "$";
        }

        @Override
        public void waitForTerminalEvent(String requestKey, String afterEventOffset, long blockTimeoutMillis) {
        }
    }

    private static final class RecordingCoordinatorRepository implements SingleFlightCoordinatorRepository {
        private SingleFlightAcquireResult nextAcquireResult = SingleFlightAcquireResult.ownerNew(1L);
        private SingleFlightStoredResult storedResult;
        private SingleFlightMetaSnapshot metaSnapshot;
        private int storeResultCalls;
        private int finishSuccessCalls;
        private int finishFailureCalls;
        private SingleFlightErrorType lastErrorType;
        private boolean lastRetryable;
        private String lastAcquireRequestKey;
        private String lastStoreResultRequestKey;
        private int acquireCalls;
        private boolean throwOnAcquire;
        private boolean failStoreResult;

        @Override
        public SingleFlightAcquireResult acquireOrJoin(String stage, String requestKey, String ownerId, SingleFlightPolicy policy) {
            acquireCalls++;
            if (throwOnAcquire) {
                throw new IllegalStateException("redis unavailable");
            }
            lastAcquireRequestKey = requestKey;
            return nextAcquireResult;
        }

        @Override
        public boolean markRunning(String requestKey, String ownerId, Long ownerToken, long runningTtlMillis) {
            return true;
        }

        @Override
        public boolean heartbeat(String requestKey, String ownerId, Long ownerToken, long runningTtlMillis) {
            return true;
        }

        @Override
        public boolean storeResult(String requestKey, String ownerId, Long ownerToken, SingleFlightStoredResult storedResult, long resultTtlMillis) {
            if (failStoreResult) {
                return false;
            }
            this.storedResult = storedResult;
            lastStoreResultRequestKey = requestKey;
            storeResultCalls++;
            return true;
        }

        @Override
        public boolean finishSuccess(String requestKey, String ownerId, Long ownerToken, long resultTtlMillis) {
            this.metaSnapshot = new SingleFlightMetaSnapshot(
                    "counter-sds",
                    SingleFlightStatus.SUCCEEDED,
                    ownerId,
                    ownerToken,
                    System.currentTimeMillis(),
                    false,
                    null,
                    null
            );
            finishSuccessCalls++;
            return true;
        }

        @Override
        public boolean finishFailure(String requestKey, String ownerId, Long ownerToken,
                                     SingleFlightErrorType errorType, String errorCode,
                                     boolean retryable, long ttlMillis) {
            lastErrorType = errorType;
            lastRetryable = retryable;
            finishFailureCalls++;
            return true;
        }

        @Override
        public SingleFlightStoredResult getStoredResult(String requestKey) {
            return storedResult;
        }

        @Override
        public SingleFlightMetaSnapshot getMeta(String requestKey) {
            return metaSnapshot;
        }
    }
}
