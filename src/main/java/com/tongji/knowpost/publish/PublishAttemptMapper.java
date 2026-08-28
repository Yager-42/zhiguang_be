package com.tongji.knowpost.publish;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;

@Mapper
public interface PublishAttemptMapper {
    int insert(PublishAttempt attempt);

    PublishAttempt findByIdempotencyKey(@Param("creatorId") Long creatorId,
                                        @Param("postId") Long postId,
                                        @Param("idempotentKey") String idempotentKey);

    PublishAttempt findById(@Param("attemptId") Long attemptId);

    int markSucceeded(@Param("attemptId") Long attemptId,
                      @Param("runVersion") Integer runVersion,
                      @Param("publishedAt") Instant publishedAt);

    int markFailed(@Param("attemptId") Long attemptId,
                   @Param("runVersion") Integer runVersion,
                   @Param("failedStep") String failedStep,
                   @Param("errorMessage") String errorMessage);

    PublishAttempt findStatusById(@Param("attemptId") Long attemptId);

    int restartFailedAttempt(@Param("attemptId") Long attemptId,
                             @Param("expectedRunVersion") Integer expectedRunVersion);
}
