package com.tongji.reconciliation.mapper;

import com.tongji.reconciliation.model.ReconciliationTask;
import com.tongji.reconciliation.model.ReconciliationTaskQuery;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface ReconciliationTaskMapper {
    int insert(ReconciliationTask task);

    ReconciliationTask findActiveByDedupeScope(@Param("dedupeScope") String dedupeScope);

    ReconciliationTask findById(@Param("id") Long id);

    List<ReconciliationTask> pollPending(@Param("limit") int limit);

    int markRunning(@Param("id") Long id);

    int markSucceeded(@Param("id") Long id,
                      @Param("executionDurationMs") long executionDurationMs);

    int markPendingRetry(@Param("id") Long id,
                         @Param("retryCount") int retryCount,
                         @Param("nextExecuteAt") LocalDateTime nextExecuteAt,
                         @Param("executionDurationMs") long executionDurationMs,
                         @Param("lastError") String lastError);

    int markDead(@Param("id") Long id,
                 @Param("executionDurationMs") long executionDurationMs,
                 @Param("lastError") String lastError);

    int resetDeadToPending(@Param("id") Long id);

    List<ReconciliationTask> findStuckRunning(@Param("staleBefore") LocalDateTime staleBefore,
                                              @Param("limit") int limit);

    int resetRunningToPending(@Param("id") Long id);

    boolean existsActiveTask(@Param("taskType") String taskType,
                             @Param("targetType") String targetType,
                             @Param("targetId") Long targetId);

    boolean existsByStatus(@Param("taskType") String taskType,
                           @Param("targetType") String targetType,
                           @Param("targetId") Long targetId,
                           @Param("status") String status);

    List<ReconciliationTask> query(ReconciliationTaskQuery query);
}
