package com.tongji.reconciliation.api;

import com.tongji.common.exception.BusinessException;
import com.tongji.common.exception.ErrorCode;
import com.tongji.common.web.GlobalExceptionHandler;
import com.tongji.reconciliation.model.ReconciliationTask;
import com.tongji.reconciliation.model.ReconciliationTaskQuery;
import com.tongji.reconciliation.model.ReconciliationTaskStatus;
import com.tongji.reconciliation.model.ReconciliationTaskType;
import com.tongji.reconciliation.model.ReconciliationTargetType;
import com.tongji.reconciliation.service.ReconciliationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ReconciliationControllerTest {

    private ReconciliationService reconciliationService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        reconciliationService = Mockito.mock(ReconciliationService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new ReconciliationController(reconciliationService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void listReturnsFilteredTasks() throws Exception {
        when(reconciliationService.query(Mockito.any())).thenReturn(List.of(task(11L, ReconciliationTaskType.ES_INDEX)));

        mockMvc.perform(get("/api/v1/reconciliation/tasks")
                        .queryParam("status", ReconciliationTaskStatus.DEAD)
                        .queryParam("targetType", ReconciliationTargetType.POST)
                        .queryParam("targetId", "101"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(11L))
                .andExpect(jsonPath("$[0].taskType").value(ReconciliationTaskType.ES_INDEX))
                .andExpect(jsonPath("$[0].targetType").value(ReconciliationTargetType.POST))
                .andExpect(jsonPath("$[0].targetId").value(101L));

        ArgumentCaptor<ReconciliationTaskQuery> captor = ArgumentCaptor.forClass(ReconciliationTaskQuery.class);
        verify(reconciliationService).query(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(ReconciliationTaskStatus.DEAD);
        assertThat(captor.getValue().getTargetType()).isEqualTo(ReconciliationTargetType.POST);
        assertThat(captor.getValue().getTargetId()).isEqualTo(101L);
    }

    @Test
    void detailReturnsTask() throws Exception {
        when(reconciliationService.findById(11L)).thenReturn(task(11L, ReconciliationTaskType.ES_INDEX));

        mockMvc.perform(get("/api/v1/reconciliation/tasks/{id}", 11L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(11L))
                .andExpect(jsonPath("$.taskType").value(ReconciliationTaskType.ES_INDEX));
    }

    @Test
    void detailReturnsNotFoundWhenTaskMissing() throws Exception {
        when(reconciliationService.findById(99L))
                .thenThrow(new BusinessException(ErrorCode.BAD_REQUEST, "reconciliation task not found"));

        mockMvc.perform(get("/api/v1/reconciliation/tasks/{id}", 99L))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.BAD_REQUEST.getCode()));
    }

    @Test
    void retryReturnsResetTask() throws Exception {
        when(reconciliationService.retryTask(11L)).thenReturn(task(11L, ReconciliationTaskType.ES_INDEX));

        mockMvc.perform(post("/api/v1/reconciliation/tasks/{id}/retry", 11L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(11L))
                .andExpect(jsonPath("$.status").value(ReconciliationTaskStatus.PENDING));
    }

    @Test
    void retryReturnsBadRequestWhenTaskIsNotRetryable() throws Exception {
        when(reconciliationService.retryTask(11L)).thenReturn(null);

        mockMvc.perform(post("/api/v1/reconciliation/tasks/{id}/retry", 11L))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("reconciliation task not retryable"));
    }

    @Test
    void rerunReturnsCreatedTasks() throws Exception {
        when(reconciliationService.rerunTarget(ReconciliationTargetType.POST, 101L))
                .thenReturn(List.of(
                        task(21L, ReconciliationTaskType.ES_INDEX),
                        task(22L, ReconciliationTaskType.GORSE_ITEM_UPSERT)
                ));

        mockMvc.perform(post("/api/v1/reconciliation/targets/{type}/{id}/rerun", ReconciliationTargetType.POST, 101L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(21L))
                .andExpect(jsonPath("$[1].taskType").value(ReconciliationTaskType.GORSE_ITEM_UPSERT));
    }

    private ReconciliationTask task(long id, String taskType) {
        return ReconciliationTask.builder()
                .id(id)
                .taskType(taskType)
                .targetType(ReconciliationTargetType.POST)
                .targetId(101L)
                .status(ReconciliationTaskStatus.PENDING)
                .retryCount(0)
                .nextExecuteAt(LocalDateTime.of(2026, 6, 18, 10, 15, 30))
                .createdAt(LocalDateTime.of(2026, 6, 18, 10, 15, 30))
                .updatedAt(LocalDateTime.of(2026, 6, 18, 10, 15, 30))
                .build();
    }
}
