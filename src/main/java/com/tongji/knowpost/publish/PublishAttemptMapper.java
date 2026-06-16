package com.tongji.knowpost.publish;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;

@Mapper
public interface PublishAttemptMapper {
    int insert(PublishAttempt attempt);

    PublishAttempt findByIdempotencyKey(@Param("creatorId") Long creatorId,
                                        @Param("postId") Long postId,
                                        @Param("idempotentKey") String idempotentKey);

    PublishAttempt findById(@Param("attemptId") Long attemptId);

    int markSucceeded(@Param("attemptId") Long attemptId);

    int markFailed(@Param("attemptId") Long attemptId,
                   @Param("failedStep") String failedStep,
                   @Param("errorMessage") String errorMessage);

    PublishAttempt findStatusById(@Param("attemptId") Long attemptId);

    int restartFailedAttempt(@Param("attemptId") Long attemptId);

    List<PublishAttempt> findStuckPublishingAttempts(@Param("updatedBefore") Instant updatedBefore);

    int updateDerivedFailureFallback(@Param("attemptId") Long attemptId,
                                     @Param("taskType") String taskType,
                                     @Param("targetType") String targetType,
                                     @Param("targetId") Long targetId,
                                     @Param("failureReason") String failureReason,
                                     @Param("nextRetryAt") Instant nextRetryAt);
}
