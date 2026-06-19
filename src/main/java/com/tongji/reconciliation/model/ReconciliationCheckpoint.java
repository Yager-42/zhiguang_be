package com.tongji.reconciliation.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReconciliationCheckpoint {
    private String scanType;
    private Long lastScannedId;
    private LocalDateTime updatedAt;
}
