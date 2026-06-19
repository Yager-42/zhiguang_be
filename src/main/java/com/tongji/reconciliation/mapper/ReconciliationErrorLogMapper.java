package com.tongji.reconciliation.mapper;

import com.tongji.reconciliation.model.ReconciliationErrorLog;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ReconciliationErrorLogMapper {
    int insert(ReconciliationErrorLog errorLog);
}
