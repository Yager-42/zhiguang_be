package com.tongji.reconciliation.api;

import com.tongji.common.exception.BusinessException;
import com.tongji.common.exception.ErrorCode;
import com.tongji.reconciliation.model.ReconciliationTask;
import com.tongji.reconciliation.model.ReconciliationTaskQuery;
import com.tongji.reconciliation.service.ReconciliationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/reconciliation")
public class ReconciliationController {

    private final ReconciliationService reconciliationService;

    public ReconciliationController(ReconciliationService reconciliationService) {
        this.reconciliationService = reconciliationService;
    }

    @GetMapping("/tasks")
    public List<ReconciliationTaskResponse> list(@RequestParam(value = "status", required = false) String status,
                                                 @RequestParam(value = "taskType", required = false) String taskType,
                                                 @RequestParam(value = "targetType", required = false) String targetType,
                                                 @RequestParam(value = "targetId", required = false) Long targetId,
                                                 @RequestParam(value = "limit", defaultValue = "20") int limit,
                                                 @RequestParam(value = "offset", defaultValue = "0") int offset) {
        return reconciliationService.query(ReconciliationTaskQuery.builder()
                        .status(status)
                        .taskType(taskType)
                        .targetType(targetType)
                        .targetId(targetId)
                        .limit(limit)
                        .offset(offset)
                        .build())
                .stream()
                .map(ReconciliationTaskResponse::from)
                .toList();
    }

    @GetMapping("/tasks/{id}")
    public ReconciliationTaskResponse detail(@PathVariable("id") long id) {
        ReconciliationTask task = reconciliationService.findById(id);
        if (task == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "reconciliation task not found");
        }
        return ReconciliationTaskResponse.from(task);
    }

    @PostMapping("/tasks/{id}/retry")
    public ReconciliationTaskResponse retry(@PathVariable("id") long id) {
        ReconciliationTask task = reconciliationService.retryTask(id);
        if (task == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "reconciliation task not retryable");
        }
        return ReconciliationTaskResponse.from(task);
    }

    @PostMapping("/targets/{type}/{id}/rerun")
    public List<ReconciliationTaskResponse> rerun(@PathVariable("type") String type,
                                                  @PathVariable("id") long id) {
        return reconciliationService.rerunTarget(type, id).stream()
                .map(ReconciliationTaskResponse::from)
                .toList();
    }
}
