package com.tongji.reconciliation.mapper;

import com.tongji.reconciliation.model.ReconciliationCheckpoint;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;

@Mapper
public interface ReconciliationCheckpointMapper {
    ReconciliationCheckpoint findByScanType(@Param("scanType") String scanType);

    int upsert(ReconciliationCheckpoint checkpoint);

    int updateCheckpoint(@Param("scanType") String scanType, @Param("lastScannedId") Long lastScannedId);

    int updateTimeCheckpoint(@Param("scanType") String scanType,
                             @Param("lastScannedAt") Instant lastScannedAt,
                             @Param("lastScannedId") Long lastScannedId);
}
