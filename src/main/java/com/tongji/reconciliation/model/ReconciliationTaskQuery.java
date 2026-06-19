package com.tongji.reconciliation.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReconciliationTaskQuery {
    private String status;
    private String taskType;
    private String targetType;
    private Long targetId;
    private Integer limit;
    private Integer offset;
}
