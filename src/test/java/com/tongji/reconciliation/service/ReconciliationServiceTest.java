package com.tongji.reconciliation.service;

import com.tongji.common.id.IdNamespace;
import com.tongji.common.id.IdService;
import com.tongji.reconciliation.mapper.ReconciliationTaskMapper;
import com.tongji.reconciliation.model.ReconciliationTask;
import com.tongji.reconciliation.model.ReconciliationTaskQuery;
import com.tongji.reconciliation.model.ReconciliationTaskStatus;
import com.tongji.reconciliation.model.ReconciliationTaskType;
import com.tongji.reconciliation.model.ReconciliationTargetType;
import com.tongji.reconciliation.scan.ReconciliationScanService;
import com.tongji.reconciliation.service.impl.ReconciliationServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReconciliationServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-18T10:15:30Z");

    @Mock
    private ReconciliationTaskMapper taskMapper;
    @Mock
    private IdService idService;
    @Mock
    private ReconciliationScanService scanService;

    private ReconciliationService service;

    @BeforeEach
    void setUp() {
        org.mockito.MockitoAnnotations.openMocks(this);
        service = new ReconciliationServiceImpl(taskMapper, idService, scanService, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void createTaskUsesReconciliationTaskNamespaceAndInsertsPendingTask() {
        when(idService.nextId(IdNamespace.RECONCILIATION_TASK)).thenReturn(9001L);

        ReconciliationTask task = service.createTask(
                ReconciliationTaskType.ES_INDEX,
                ReconciliationTargetType.POST,
                42L
        );

        assertThat(task.getId()).isEqualTo(9001L);
        assertThat(task.getStatus()).isEqualTo(ReconciliationTaskStatus.PENDING);
        assertThat(task.getRetryCount()).isZero();
        assertThat(task.getNextExecuteAt()).isEqualTo(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
        assertThat(task.getDedupeScope()).isEqualTo("es_index:post:42");
        assertThat(task.getTaskPayload()).isNull();
        verify(idService).nextId(IdNamespace.RECONCILIATION_TASK);
        ArgumentCaptor<ReconciliationTask> captor = ArgumentCaptor.forClass(ReconciliationTask.class);
        verify(taskMapper).insert(captor.capture());
        assertThat(captor.getValue()).usingRecursiveComparison().isEqualTo(task);
    }

    @Test
    void createTaskIfAbsentReturnsNoopWhenActiveTaskExists() {
        when(taskMapper.findActiveByDedupeScope("es_index:post:42"))
                .thenReturn(ReconciliationTask.builder().id(1L).build());

        ReconciliationTask task = service.createTaskIfAbsent(
                ReconciliationTaskType.ES_INDEX,
                ReconciliationTargetType.POST,
                42L
        );

        assertThat(task).isNull();
        verify(idService, never()).nextId(IdNamespace.RECONCILIATION_TASK);
        verify(taskMapper, never()).insert(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void createTaskIfAbsentCreatesWhenOnlySucceededOrDeadTasksExist() {
        when(taskMapper.findActiveByDedupeScope("rag_index:post:42")).thenReturn(null);
        when(idService.nextId(IdNamespace.RECONCILIATION_TASK)).thenReturn(9002L);

        ReconciliationTask task = service.createTaskIfAbsent(
                ReconciliationTaskType.RAG_INDEX,
                ReconciliationTargetType.POST,
                42L
        );

        assertThat(task.getId()).isEqualTo(9002L);
        verify(taskMapper).insert(task);
    }

    @Test
    void createTaskIfAbsentWithPayloadUsesPayloadAwareDedupeScope() {
        when(taskMapper.findActiveByDedupeScope(any())).thenReturn(null);
        when(idService.nextId(IdNamespace.RECONCILIATION_TASK)).thenReturn(9003L);

        ReconciliationTask task = service.createTaskIfAbsent(
                ReconciliationTaskType.GORSE_FEEDBACK,
                ReconciliationTargetType.POST,
                42L,
                "{\"feedbackType\":\"like\",\"userId\":7,\"itemId\":\"42\"}"
        );

        assertThat(task.getTaskPayload()).contains("\"feedbackType\":\"like\"");
        assertThat(task.getDedupeScope()).startsWith("gorse_feedback:post:42:");
    }

    @Test
    void retryTaskResetsDeadTaskToCurrentExecutablePendingTask() {
        ReconciliationTask reset = ReconciliationTask.builder()
                .id(77L)
                .status(ReconciliationTaskStatus.PENDING)
                .retryCount(0)
                .nextExecuteAt(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC))
                .build();
        when(taskMapper.resetDeadToPending(77L)).thenReturn(1);
        when(taskMapper.findById(77L)).thenReturn(reset);

        ReconciliationTask task = service.retryTask(77L);

        InOrder inOrder = inOrder(taskMapper);
        inOrder.verify(taskMapper).resetDeadToPending(77L);
        inOrder.verify(taskMapper).findById(77L);
        assertThat(task).isSameAs(reset);
    }

    @Test
    void retryTaskReturnsNullWhenTaskWasNotDead() {
        when(taskMapper.resetDeadToPending(77L)).thenReturn(0);

        ReconciliationTask task = service.retryTask(77L);

        assertThat(task).isNull();
        verify(taskMapper, never()).findById(77L);
    }

    @Test
    void rerunTargetSchedulesOnlyStableTasksForPostAndComment() {
        when(idService.nextId(IdNamespace.RECONCILIATION_TASK))
                .thenReturn(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L);
        when(scanService.buildFollowInboxTaskSpec(42L))
                .thenReturn(new ReconciliationScanService.FollowInboxTaskSpec(
                        7L,
                        "{\"postId\":42,\"authorId\":7,\"publishedAt\":\"2026-06-18T10:15:30Z\",\"largeAuthor\":false}"
                ));
        when(scanService.buildFollowInboxTaskSpec(81L)).thenReturn(null);

        List<ReconciliationTask> postTasks = service.rerunTarget(ReconciliationTargetType.POST, 42L);
        List<ReconciliationTask> commentTasks = service.rerunTarget(ReconciliationTargetType.COMMENT, 81L);
        List<ReconciliationTask> userTasks = service.rerunTarget(ReconciliationTargetType.USER, 7L);

        assertThat(postTasks).extracting(ReconciliationTask::getTaskType)
                .containsExactly(
                        ReconciliationTaskType.ES_INDEX,
                        ReconciliationTaskType.RAG_INDEX,
                        ReconciliationTaskType.GORSE_ITEM_UPSERT,
                        ReconciliationTaskType.CASSANDRA_TEXT,
                        ReconciliationTaskType.COMMENT_COUNT,
                        ReconciliationTaskType.FOLLOW_INBOX
                );
        assertThat(commentTasks).extracting(ReconciliationTask::getTaskType)
                .containsExactly(
                        ReconciliationTaskType.CASSANDRA_TEXT,
                        ReconciliationTaskType.COMMENT_COUNT
                );
        assertThat(userTasks).extracting(ReconciliationTask::getTaskType)
                .containsExactly(ReconciliationTaskType.FOLLOW_GRAPH);
        assertThat(postTasks.getLast().getTargetType()).isEqualTo(ReconciliationTargetType.USER);
        assertThat(postTasks.getLast().getTargetId()).isEqualTo(7L);
    }

    @Test
    void queryDelegatesToMapperQueryObject() {
        ReconciliationTaskQuery query = ReconciliationTaskQuery.builder()
                .status(ReconciliationTaskStatus.DEAD)
                .targetType(ReconciliationTargetType.POST)
                .limit(20)
                .offset(0)
                .build();
        List<ReconciliationTask> rows = List.of(ReconciliationTask.builder().id(8L).build());
        when(taskMapper.query(query)).thenReturn(rows);

        assertThat(service.query(query)).isSameAs(rows);
    }
}
