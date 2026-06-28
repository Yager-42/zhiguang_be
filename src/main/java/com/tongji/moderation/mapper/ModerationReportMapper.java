package com.tongji.moderation.mapper;

import com.tongji.moderation.model.ModerationReport;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface ModerationReportMapper {
    int insert(ModerationReport report);

    ModerationReport findByReporterAndTarget(@Param("reporterUserId") long reporterUserId,
                                             @Param("targetType") String targetType,
                                             @Param("targetId") long targetId);

    ModerationReport findById(@Param("id") long id);

    int markReviewed(@Param("id") long id,
                     @Param("status") String status,
                     @Param("llmProvider") String llmProvider,
                     @Param("llmModel") String llmModel,
                     @Param("llmDecision") String llmDecision,
                     @Param("llmConfidence") BigDecimal llmConfidence,
                     @Param("llmSummary") String llmSummary);

    int markIgnored(@Param("id") long id,
                    @Param("llmProvider") String llmProvider,
                    @Param("llmModel") String llmModel,
                    @Param("llmDecision") String llmDecision,
                    @Param("llmConfidence") BigDecimal llmConfidence,
                    @Param("llmSummary") String llmSummary,
                    @Param("failureCode") String failureCode,
                    @Param("failureReason") String failureReason,
                    @Param("reviewedAt") LocalDateTime reviewedAt);

    int scheduleRetry(@Param("id") long id,
                      @Param("retryCount") int retryCount,
                      @Param("nextRetryAt") LocalDateTime nextRetryAt,
                      @Param("failureCode") String failureCode,
                      @Param("failureReason") String failureReason,
                      @Param("updatedAt") LocalDateTime updatedAt);

    int updateContentAction(@Param("id") long id,
                            @Param("contentActionStatus") String contentActionStatus,
                            @Param("contentActionFailure") String contentActionFailure,
                            @Param("updatedAt") LocalDateTime updatedAt);

    int updateNotificationFailure(@Param("id") long id,
                                  @Param("notificationFailure") String notificationFailure,
                                  @Param("updatedAt") LocalDateTime updatedAt);

    List<ModerationReport> listDueRetries(@Param("now") LocalDateTime now, @Param("limit") int limit);
}
