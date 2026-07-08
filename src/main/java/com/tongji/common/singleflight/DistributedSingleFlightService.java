package com.tongji.common.singleflight;

import com.fasterxml.jackson.core.type.TypeReference;
import com.tongji.common.singleflight.model.SingleFlightAcquireResult;
import com.tongji.common.singleflight.model.SingleFlightAction;
import com.tongji.common.singleflight.model.SingleFlightErrorType;
import com.tongji.common.singleflight.model.SingleFlightFailure;
import com.tongji.common.singleflight.model.SingleFlightMetaSnapshot;
import com.tongji.common.singleflight.model.SingleFlightMode;
import com.tongji.common.singleflight.model.SingleFlightOwnerContext;
import com.tongji.common.singleflight.model.SingleFlightPolicy;
import com.tongji.common.singleflight.model.SingleFlightStatus;
import com.tongji.common.singleflight.model.SingleFlightStoredResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

@Service
@Slf4j
public class DistributedSingleFlightService {

    private static final int MAX_ATTEMPTS = 3;

    private final SingleFlightProperties properties;
    private final LocalSingleFlightService localSingleFlightService;
    private final SingleFlightCoordinatorRepository coordinatorRepository;
    private final SingleFlightNotificationService notificationService;
    private final SingleFlightHeartbeatManager heartbeatManager;
    private final SingleFlightResultCodec resultCodec;
    private final SingleFlightLocalReplayCache localReplayCache;

    public DistributedSingleFlightService(SingleFlightProperties properties,
                                          LocalSingleFlightService localSingleFlightService,
                                          SingleFlightCoordinatorRepository coordinatorRepository,
                                          SingleFlightNotificationService notificationService,
                                          SingleFlightHeartbeatManager heartbeatManager,
                                          SingleFlightResultCodec resultCodec,
                                          SingleFlightLocalReplayCache localReplayCache) {
        this.properties = properties;
        this.localSingleFlightService = localSingleFlightService;
        this.coordinatorRepository = coordinatorRepository;
        this.notificationService = notificationService;
        this.heartbeatManager = heartbeatManager;
        this.resultCodec = resultCodec;
        this.localReplayCache = localReplayCache;
    }

    public <T> T execute(String stage, String requestKey, TypeReference<T> resultType, Supplier<T> supplier) {
        Objects.requireNonNull(resultType, "resultType must not be null");
        Objects.requireNonNull(supplier, "supplier must not be null");
        requireText(stage, "stage");
        requireText(requestKey, "requestKey");

        SingleFlightPolicy policy = properties.resolvePolicy(stage);
        SingleFlightMode mode = properties.mode(stage);
        if (!properties.isEnabled(stage) || mode == SingleFlightMode.DISABLED) {
            return supplier.get();
        }
        if (mode == SingleFlightMode.LOCAL) {
            return localSingleFlightService.execute(stage, requestKey, supplier);
        }
        return executeDistributed(stage, requestKey, resultType, supplier, policy, mode == SingleFlightMode.HYBRID);
    }

    private <T> T executeDistributed(String stage,
                                     String requestKey,
                                     TypeReference<T> resultType,
                                     Supplier<T> supplier,
                                     SingleFlightPolicy policy,
                                     boolean fallbackOnAcquireFailure) {
        T localReplay = localReplayCache.get(stage, requestKey);
        if (localReplay != null) {
            return localReplay;
        }

        String repositoryKey = repositoryKey(stage, requestKey);
        long deadlineMillis = System.currentTimeMillis() + policy.followerMaxWaitMillis();
        int attempts = 0;
        while (attempts < MAX_ATTEMPTS) {
            attempts++;
            SingleFlightAcquireResult acquireResult;
            try {
                acquireResult = coordinatorRepository.acquireOrJoin(stage, repositoryKey, nodeId(), policy);
            } catch (RuntimeException exception) {
                if (fallbackOnAcquireFailure) {
                    return localSingleFlightService.execute(stage, requestKey, supplier);
                }
                throw exception;
            }
            if (acquireResult == null || acquireResult.action() == null) {
                return localSingleFlightService.execute(stage, requestKey, supplier);
            }
            SingleFlightAction action = acquireResult.action();
            if (action == SingleFlightAction.OWNER_NEW || action == SingleFlightAction.OWNER_TAKEOVER) {
                return ownerExecute(stage, requestKey, repositoryKey, acquireResult.ownerToken(), resultType, supplier, policy);
            }
            if (action == SingleFlightAction.REPLAY_SUCCESS) {
                T replay = tryReadSuccessReplay(stage, requestKey, repositoryKey, resultType, policy);
                if (replay != null) {
                    return replay;
                }
                continue;
            }
            if (action == SingleFlightAction.REPLAY_FAILURE) {
                throw replayFailure(acquireResult);
            }
            if (action == SingleFlightAction.FOLLOWER_WAIT) {
                T followerReplay = followerWait(stage, requestKey, repositoryKey, resultType, policy, deadlineMillis);
                if (followerReplay != null) {
                    return followerReplay;
                }
            }
        }
        throw new CompletionException(new RejectedExecutionException("distributed single-flight max attempts exceeded"));
    }

    private <T> T ownerExecute(String stage,
                               String requestKey,
                               String repositoryKey,
                               Long ownerToken,
                               TypeReference<T> resultType,
                               Supplier<T> supplier,
                               SingleFlightPolicy policy) {
        long runningTtlMillis = policy.runningTtlMillis();
        boolean markedRunning = coordinatorRepository.markRunning(repositoryKey, nodeId(), ownerToken, runningTtlMillis);
        if (!markedRunning) {
            return followerWait(stage, requestKey, repositoryKey, resultType, policy,
                    System.currentTimeMillis() + policy.followerMaxWaitMillis());
        }

        SingleFlightOwnerContext ownerContext = new SingleFlightOwnerContext(stage, requestKey, nodeId(), ownerToken, policy);
        String heartbeatTaskKey = heartbeatManager.start(ownerContext,
                () -> coordinatorRepository.heartbeat(repositoryKey, nodeId(), ownerToken, runningTtlMillis));
        try {
            T result = supplier.get();
            SingleFlightStoredResult storedResult = resultCodec.serialize(result, ownerToken, policy);
            long resultTtlMillis = policy.resultTtlMillis();
            if (!coordinatorRepository.storeResult(repositoryKey, nodeId(), ownerToken, storedResult, resultTtlMillis)) {
                throw new IllegalStateException("failed to store distributed single-flight result");
            }
            if (!coordinatorRepository.finishSuccess(repositoryKey, nodeId(), ownerToken, resultTtlMillis)) {
                T replay = tryReadSuccessReplay(stage, requestKey, repositoryKey, resultType, policy);
                if (replay != null) {
                    return replay;
                }
                throw new IllegalStateException("failed to finish distributed single-flight success state");
            }
            publishTerminalEvent(repositoryKey, "owner_succeeded", SingleFlightStatus.SUCCEEDED,
                    ownerToken, null, false, resultTtlMillis);
            localReplayCache.put(stage, requestKey, result, policy);
            return result;
        } catch (Throwable throwable) {
            SingleFlightFailure failure = classifyFailure(throwable);
            long failedResultTtlMillis = policy.failedResultTtlMillis();
            try {
                coordinatorRepository.finishFailure(
                        repositoryKey,
                        nodeId(),
                        ownerToken,
                        failure.errorType(),
                        failure.errorCode(),
                        failure.retryable(),
                        failedResultTtlMillis
                );
            } catch (RuntimeException exception) {
                log.warn("failed to finish distributed single-flight failure state, requestKey={}, error={}",
                        repositoryKey, exception.toString());
            }
            publishTerminalEvent(repositoryKey, "owner_failed", SingleFlightStatus.FAILED,
                    ownerToken, failure.errorType(), failure.retryable(), failedResultTtlMillis);
            throw rethrow(throwable);
        } finally {
            heartbeatManager.stop(heartbeatTaskKey);
        }
    }

    private void publishTerminalEvent(String repositoryKey,
                                      String event,
                                      SingleFlightStatus status,
                                      Long ownerToken,
                                      SingleFlightErrorType errorType,
                                      boolean retryable,
                                      long streamTtlMillis) {
        try {
            notificationService.publish(repositoryKey, event, status, ownerToken, errorType, retryable, streamTtlMillis);
        } catch (RuntimeException exception) {
            log.warn("failed to publish distributed single-flight terminal event, requestKey={}, event={}, error={}",
                    repositoryKey, event, exception.toString());
        }
    }

    private <T> T followerWait(String stage,
                               String requestKey,
                               String repositoryKey,
                               TypeReference<T> resultType,
                               SingleFlightPolicy policy,
                               long deadlineMillis) {
        long nextPollAt = System.currentTimeMillis();
        while (System.currentTimeMillis() < deadlineMillis) {
            String eventOffset = notificationService.currentEventOffset(repositoryKey);
            T replay = tryReadSuccessReplay(stage, requestKey, repositoryKey, resultType, policy);
            if (replay != null) {
                return replay;
            }
            SingleFlightMetaSnapshot metaSnapshot = coordinatorRepository.getMeta(repositoryKey);
            if (metaSnapshot != null
                    && metaSnapshot.status() == SingleFlightStatus.FAILED
                    && !metaSnapshot.retryable()) {
                throw new IllegalStateException("distributed single-flight previous failure: "
                        + (metaSnapshot.errorCode() == null ? "FAILED" : metaSnapshot.errorCode()));
            }
            long remainingMillis = deadlineMillis - System.currentTimeMillis();
            if (remainingMillis <= 0) {
                return null;
            }
            notificationService.waitForTerminalEvent(repositoryKey, eventOffset,
                    Math.min(policy.streamBlockTimeoutMillis(), remainingMillis));
            if (System.currentTimeMillis() >= nextPollAt) {
                T polledReplay = tryReadSuccessReplay(stage, requestKey, repositoryKey, resultType, policy);
                if (polledReplay != null) {
                    return polledReplay;
                }
                nextPollAt = System.currentTimeMillis() + policy.pollFallbackIntervalMillis();
            }
        }
        return null;
    }

    private <T> T tryReadSuccessReplay(String stage,
                                       String requestKey,
                                       String repositoryKey,
                                       TypeReference<T> resultType,
                                       SingleFlightPolicy policy) {
        T localReplay = localReplayCache.get(stage, requestKey);
        if (localReplay != null) {
            return localReplay;
        }
        SingleFlightMetaSnapshot metaSnapshot = coordinatorRepository.getMeta(repositoryKey);
        if (metaSnapshot == null || metaSnapshot.status() != SingleFlightStatus.SUCCEEDED) {
            return null;
        }
        SingleFlightStoredResult storedResult = coordinatorRepository.getStoredResult(repositoryKey);
        if (storedResult == null) {
            return null;
        }
        T replay = resultCodec.deserialize(storedResult, resultType);
        localReplayCache.put(stage, requestKey, replay, policy);
        return replay;
    }

    private RuntimeException replayFailure(SingleFlightAcquireResult acquireResult) {
        String message = "distributed single-flight replay failure";
        if (acquireResult.errorCode() != null && !acquireResult.errorCode().isBlank()) {
            message = message + ": " + acquireResult.errorCode();
        }
        if (acquireResult.retryable()) {
            return new CompletionException(new RejectedExecutionException(message));
        }
        return new IllegalStateException(message);
    }

    private RuntimeException rethrow(Throwable throwable) {
        if (throwable instanceof RuntimeException runtimeException) {
            return runtimeException;
        }
        return new CompletionException(throwable);
    }

    private SingleFlightFailure classifyFailure(Throwable throwable) {
        Throwable cause = throwable;
        while (cause instanceof CompletionException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        if (cause instanceof TimeoutException) {
            return new SingleFlightFailure(SingleFlightErrorType.TIMEOUT, "TIMEOUT", true);
        }
        if (cause instanceof RejectedExecutionException) {
            return new SingleFlightFailure(SingleFlightErrorType.OVERLOAD, "OVERLOADED", true);
        }
        if (cause instanceof IllegalArgumentException) {
            return new SingleFlightFailure(SingleFlightErrorType.VALIDATION, "VALIDATION", false);
        }
        return new SingleFlightFailure(SingleFlightErrorType.UNEXPECTED, "UNEXPECTED", false);
    }

    private String nodeId() {
        return Holder.NODE_ID;
    }

    private void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private String repositoryKey(String stage, String requestKey) {
        return stage + ":" + requestKey;
    }

    private static final class Holder {
        private static final String NODE_ID = resolveNodeId();

        private static String resolveNodeId() {
            try {
                return InetAddress.getLocalHost().getHostName() + "@" + ManagementFactory.getRuntimeMXBean().getName();
            } catch (UnknownHostException exception) {
                return ManagementFactory.getRuntimeMXBean().getName();
            }
        }
    }
}
