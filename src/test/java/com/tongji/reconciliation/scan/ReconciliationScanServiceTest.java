package com.tongji.reconciliation.scan;

import com.tongji.comment.mapper.CommentMapper;
import com.tongji.counter.service.CounterService;
import com.tongji.knowpost.mapper.KnowPostMapper;
import com.tongji.llm.rag.RagIndexService;
import com.tongji.reconciliation.mapper.ReconciliationCheckpointMapper;
import com.tongji.reconciliation.model.ReconciliationCheckpoint;
import com.tongji.reconciliation.model.ReconciliationScanType;
import com.tongji.reconciliation.model.ReconciliationTargetType;
import com.tongji.reconciliation.model.ReconciliationTaskType;
import com.tongji.reconciliation.service.ReconciliationService;
import com.tongji.recommendation.gorse.GorseClient;
import com.tongji.recommendation.gorse.GorseProperties;
import com.tongji.search.index.SearchIndexService;
import com.tongji.storage.text.CommentTextRepository;
import com.tongji.storage.text.PostTextRepository;
import com.tongji.user.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;

class ReconciliationScanServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-19T10:15:30Z");

    private ReconciliationCheckpointMapper checkpointMapper;
    private KnowPostMapper knowPostMapper;
    private CommentMapper commentMapper;
    private UserMapper userMapper;
    private ReconciliationService reconciliationService;
    private CounterService counterService;
    private GorseClient gorseClient;
    private GorseProperties gorseProperties;
    private PostTextRepository postTextRepository;
    private CommentTextRepository commentTextRepository;
    private RecordingSearchIndexService searchIndexService;
    private RecordingRagIndexService ragIndexService;

    private AtomicInteger recovered;

    private ReconciliationScanService service;

    @BeforeEach
    void setUp() {
        org.mockito.MockitoAnnotations.openMocks(this);
        checkpointMapper = org.mockito.Mockito.mock(ReconciliationCheckpointMapper.class);
        knowPostMapper = org.mockito.Mockito.mock(KnowPostMapper.class);
        commentMapper = org.mockito.Mockito.mock(CommentMapper.class);
        userMapper = org.mockito.Mockito.mock(UserMapper.class);
        reconciliationService = org.mockito.Mockito.mock(ReconciliationService.class);
        counterService = org.mockito.Mockito.mock(CounterService.class);
        gorseClient = org.mockito.Mockito.mock(GorseClient.class);
        gorseProperties = new GorseProperties();
        gorseProperties.setEnabled(true);
        postTextRepository = org.mockito.Mockito.mock(PostTextRepository.class);
        commentTextRepository = org.mockito.Mockito.mock(CommentTextRepository.class);
        searchIndexService = new RecordingSearchIndexService();
        ragIndexService = new RecordingRagIndexService();
        recovered = new AtomicInteger();
        service = new ReconciliationScanService(
                checkpointMapper,
                knowPostMapper,
                commentMapper,
                userMapper,
                reconciliationService,
                gorseClient,
                gorseProperties,
                new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules(),
                counterService,
                searchIndexService,
                ragIndexService,
                postTextRepository,
                commentTextRepository,
                () -> {
                    recovered.incrementAndGet();
                    return 7;
                },
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void scanPostEsBatchCreatesTasksAndAdvancesCheckpointToLastId() {
        when(checkpointMapper.findByScanType(ReconciliationScanType.POST_ES))
                .thenReturn(ReconciliationCheckpoint.builder()
                        .scanType(ReconciliationScanType.POST_ES)
                        .lastScannedId(100L)
                        .build());
        when(knowPostMapper.listPublishedPostIdsCursor(100L, 1000)).thenReturn(List.of(101L, 105L));
        searchIndexService.setExists(101L, false);
        searchIndexService.setExists(105L, true);
        when(counterService.rebuildCountsFromFacts("knowpost", "105", List.of("like", "fav")))
                .thenReturn(Map.of("like", 3L, "fav", 1L));
        searchIndexService.setCounts(105L, Map.of("like", 3L, "fav", 1L));

        service.scanPostEsBatch();

        verify(reconciliationService).createTaskIfAbsent(
                ReconciliationTaskType.ES_INDEX,
                ReconciliationTargetType.POST,
                101L
        );
        verify(checkpointMapper).updateCheckpoint(ReconciliationScanType.POST_ES, 105L);
    }

    @Test
    void scanPostEsBatchCreatesTaskWhenIndexedCountsDriftFromFacts() {
        when(checkpointMapper.findByScanType(ReconciliationScanType.POST_ES))
                .thenReturn(ReconciliationCheckpoint.builder()
                        .scanType(ReconciliationScanType.POST_ES)
                        .lastScannedId(200L)
                        .build());
        when(knowPostMapper.listPublishedPostIdsCursor(200L, 1000)).thenReturn(List.of(201L));
        searchIndexService.setExists(201L, true);
        when(counterService.rebuildCountsFromFacts("knowpost", "201", List.of("like", "fav")))
                .thenReturn(Map.of("like", 9L, "fav", 2L));
        searchIndexService.setCounts(201L, Map.of("like", 8L, "fav", 2L));

        service.scanPostEsBatch();

        verify(reconciliationService).createTaskIfAbsent(
                ReconciliationTaskType.ES_INDEX,
                ReconciliationTargetType.POST,
                201L
        );
        verify(checkpointMapper).updateCheckpoint(ReconciliationScanType.POST_ES, 201L);
    }

    @Test
    void scanPostRagBatchSkipsHealthyRowsAndOnlyCreatesMissingIndexTasks() {
        when(checkpointMapper.findByScanType(ReconciliationScanType.POST_RAG))
                .thenReturn(ReconciliationCheckpoint.builder()
                        .scanType(ReconciliationScanType.POST_RAG)
                        .lastScannedId(10L)
                        .build());
        when(knowPostMapper.listPublicPublishedPostIdsCursor(10L, 1000)).thenReturn(List.of(11L, 12L));
        ragIndexService.setExists(11L, true);
        ragIndexService.setExists(12L, false);

        service.scanPostRagBatch();

        verify(reconciliationService).createTaskIfAbsent(
                ReconciliationTaskType.RAG_INDEX,
                ReconciliationTargetType.POST,
                12L
        );
        verify(checkpointMapper).updateCheckpoint(ReconciliationScanType.POST_RAG, 12L);
    }

    @Test
    void scanPostRagBatchSkipsPublishedButNonPublicPosts() {
        when(checkpointMapper.findByScanType(ReconciliationScanType.POST_RAG))
                .thenReturn(ReconciliationCheckpoint.builder()
                        .scanType(ReconciliationScanType.POST_RAG)
                        .lastScannedId(10L)
                        .build());
        when(knowPostMapper.listPublishedPostIdsCursor(10L, 1000)).thenReturn(List.of(11L, 12L));
        when(knowPostMapper.listPublicPublishedPostIdsCursor(10L, 1000)).thenReturn(List.of(12L));
        ragIndexService.setExists(12L, false);

        service.scanPostRagBatch();

        verify(knowPostMapper, never()).listPublishedPostIdsCursor(10L, 1000);
        verify(reconciliationService).createTaskIfAbsent(
                ReconciliationTaskType.RAG_INDEX,
                ReconciliationTargetType.POST,
                12L
        );
        assertThat(ragIndexService.queriedPostIds()).containsExactly(12L);
    }

    @Test
    void scanPostEsBatchPropagatesProbeFailureWithoutEnqueueingTasks() {
        when(checkpointMapper.findByScanType(ReconciliationScanType.POST_ES))
                .thenReturn(ReconciliationCheckpoint.builder()
                        .scanType(ReconciliationScanType.POST_ES)
                        .lastScannedId(100L)
                        .build());
        when(knowPostMapper.listPublishedPostIdsCursor(100L, 1000)).thenReturn(List.of(101L));
        searchIndexService.setFailure(101L, new IllegalStateException("es probe failed"));

        assertThatThrownBy(() -> service.scanPostEsBatch())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("es probe failed");

        verifyNoInteractions(reconciliationService);
    }

    @Test
    void scanPostRagBatchResetsCheckpointAfterFullPass() {
        when(checkpointMapper.findByScanType(ReconciliationScanType.POST_RAG))
                .thenReturn(ReconciliationCheckpoint.builder()
                        .scanType(ReconciliationScanType.POST_RAG)
                        .lastScannedId(105L)
                        .build());
        when(knowPostMapper.listPublicPublishedPostIdsCursor(105L, 1000)).thenReturn(List.of());

        service.scanPostRagBatch();

        verify(checkpointMapper).updateCheckpoint(ReconciliationScanType.POST_RAG, 0L);
    }

    @Test
    void scanPostRagBatchPropagatesProbeFailureWithoutEnqueueingTasks() {
        when(checkpointMapper.findByScanType(ReconciliationScanType.POST_RAG))
                .thenReturn(ReconciliationCheckpoint.builder()
                        .scanType(ReconciliationScanType.POST_RAG)
                        .lastScannedId(10L)
                        .build());
        when(knowPostMapper.listPublicPublishedPostIdsCursor(10L, 1000)).thenReturn(List.of(11L));
        ragIndexService.setFailure(11L, new IllegalStateException("rag probe failed"));

        assertThatThrownBy(() -> service.scanPostRagBatch())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("rag probe failed");

        verifyNoInteractions(reconciliationService);
    }

    @Test
    void scanPostGorseBatchCreatesItemUpsertTasksAndAdvancesCheckpoint() {
        when(checkpointMapper.findByScanType(ReconciliationScanType.POST_GORSE))
                .thenReturn(ReconciliationCheckpoint.builder()
                        .scanType(ReconciliationScanType.POST_GORSE)
                        .lastScannedId(20L)
                        .build());
        when(knowPostMapper.listPublishedPostIdsCursor(20L, 1000)).thenReturn(List.of(21L, 22L));
        when(gorseClient.hasItem(21L)).thenReturn(false);
        when(gorseClient.hasItem(22L)).thenReturn(true);

        service.scanPostGorseBatch();

        verify(reconciliationService).createTaskIfAbsent(
                ReconciliationTaskType.GORSE_ITEM_UPSERT,
                ReconciliationTargetType.POST,
                21L
        );
        verify(checkpointMapper).updateCheckpoint(ReconciliationScanType.POST_GORSE, 22L);
    }

    @Test
    void scanPostGorseBatchSkipsWhenGorseDisabled() {
        gorseProperties.setEnabled(false);

        service.scanPostGorseBatch();

        verifyNoInteractions(gorseClient, reconciliationService, checkpointMapper, knowPostMapper);
    }

    @Test
    void scanPostGorseBatchFallsBackToRepairTaskWhenProbeFails() {
        when(checkpointMapper.findByScanType(ReconciliationScanType.POST_GORSE))
                .thenReturn(ReconciliationCheckpoint.builder()
                        .scanType(ReconciliationScanType.POST_GORSE)
                        .lastScannedId(20L)
                        .build());
        when(knowPostMapper.listPublishedPostIdsCursor(20L, 1000)).thenReturn(List.of(21L));
        when(gorseClient.hasItem(21L)).thenThrow(new RuntimeException("gorse down"));

        service.scanPostGorseBatch();

        verify(reconciliationService).createTaskIfAbsent(
                ReconciliationTaskType.GORSE_ITEM_UPSERT,
                ReconciliationTargetType.POST,
                21L
        );
        verify(checkpointMapper).updateCheckpoint(ReconciliationScanType.POST_GORSE, 21L);
    }

    @Test
    void scanCommentCassandraBatchUsesCommentCursorAndUpdatesCheckpoint() {
        when(checkpointMapper.findByScanType(ReconciliationScanType.COMMENT_CASSANDRA))
                .thenReturn(ReconciliationCheckpoint.builder()
                        .scanType(ReconciliationScanType.COMMENT_CASSANDRA)
                        .lastScannedId(80L)
                        .build());
        when(commentMapper.listCommentIdsCursor(80L, 1000)).thenReturn(List.of(81L, 83L));
        when(commentTextRepository.existsById(81L)).thenReturn(true);
        when(commentTextRepository.existsById(83L)).thenReturn(false);

        service.scanCommentCassandraBatch();

        verify(reconciliationService).createDeadTaskIfAbsent(
                ReconciliationTaskType.CASSANDRA_TEXT,
                ReconciliationTargetType.COMMENT,
                83L,
                "Comment 83 cannot be repaired: no durable source body is stored for reconciliation"
        );
        verify(checkpointMapper).updateCheckpoint(ReconciliationScanType.COMMENT_CASSANDRA, 83L);
    }

    @Test
    void scanCommentCassandraBatchSkipsDeletedCommentsMissingText() {
        when(checkpointMapper.findByScanType(ReconciliationScanType.COMMENT_CASSANDRA))
                .thenReturn(ReconciliationCheckpoint.builder()
                        .scanType(ReconciliationScanType.COMMENT_CASSANDRA)
                        .lastScannedId(80L)
                        .build());
        when(commentMapper.listCommentIdsCursor(80L, 1000)).thenReturn(List.of(81L));
        when(commentTextRepository.existsById(81L)).thenReturn(true);

        service.scanCommentCassandraBatch();

        verify(reconciliationService, never()).createDeadTaskIfAbsent(
                ReconciliationTaskType.CASSANDRA_TEXT,
                ReconciliationTargetType.COMMENT,
                82L,
                "Comment 82 cannot be repaired: no durable source body is stored for reconciliation"
        );
        verify(reconciliationService, never()).createDeadTaskIfAbsent(
                ReconciliationTaskType.CASSANDRA_TEXT,
                ReconciliationTargetType.COMMENT,
                81L,
                "Comment 81 cannot be repaired: no durable source body is stored for reconciliation"
        );
        verify(checkpointMapper).updateCheckpoint(ReconciliationScanType.COMMENT_CASSANDRA, 81L);
    }

    @Test
    void scanPostCommentCountBatchCreatesRepairTaskWhenPostCommentCountMightDrift() {
        when(checkpointMapper.findByScanType(ReconciliationScanType.POST_COMMENT_COUNT))
                .thenReturn(ReconciliationCheckpoint.builder()
                        .scanType(ReconciliationScanType.POST_COMMENT_COUNT)
                        .lastScannedId(40L)
                        .build());
        when(knowPostMapper.listPublishedPostIdsCursor(40L, 1000)).thenReturn(List.of(41L, 42L));

        service.scanPostCommentCountBatch();

        verify(reconciliationService).createTaskIfAbsent(
                ReconciliationTaskType.COMMENT_COUNT,
                ReconciliationTargetType.POST,
                41L
        );
        verify(reconciliationService).createTaskIfAbsent(
                ReconciliationTaskType.COMMENT_COUNT,
                ReconciliationTargetType.POST,
                42L
        );
        verify(checkpointMapper).updateCheckpoint(ReconciliationScanType.POST_COMMENT_COUNT, 42L);
    }

    @Test
    void scanCommentReplyCountBatchCreatesRepairTaskWhenReplyCountMightDrift() {
        when(checkpointMapper.findByScanType(ReconciliationScanType.COMMENT_REPLY_COUNT))
                .thenReturn(ReconciliationCheckpoint.builder()
                        .scanType(ReconciliationScanType.COMMENT_REPLY_COUNT)
                        .lastScannedId(80L)
                        .build());
        when(commentMapper.listCommentIdsCursor(80L, 1000)).thenReturn(List.of(81L, 83L));

        service.scanCommentReplyCountBatch();

        verify(reconciliationService).createTaskIfAbsent(
                ReconciliationTaskType.COMMENT_COUNT,
                ReconciliationTargetType.COMMENT,
                81L
        );
        verify(reconciliationService).createTaskIfAbsent(
                ReconciliationTaskType.COMMENT_COUNT,
                ReconciliationTargetType.COMMENT,
                83L
        );
        verify(checkpointMapper).updateCheckpoint(ReconciliationScanType.COMMENT_REPLY_COUNT, 83L);
    }

    @Test
    void scanUserFollowGraphBatchCreatesRepairTaskWhenUserRelationGraphMightDrift() {
        when(checkpointMapper.findByScanType(ReconciliationScanType.USER_FOLLOW_GRAPH))
                .thenReturn(ReconciliationCheckpoint.builder()
                        .scanType(ReconciliationScanType.USER_FOLLOW_GRAPH)
                        .lastScannedId(6L)
                        .build());
        when(userMapper.listUserIdsCursor(6L, 1000)).thenReturn(List.of(7L, 9L));

        service.scanUserFollowGraphBatch();

        verify(reconciliationService).createTaskIfAbsent(
                ReconciliationTaskType.FOLLOW_GRAPH,
                ReconciliationTargetType.USER,
                7L
        );
        verify(reconciliationService).createTaskIfAbsent(
                ReconciliationTaskType.FOLLOW_GRAPH,
                ReconciliationTargetType.USER,
                9L
        );
        verify(checkpointMapper).updateCheckpoint(ReconciliationScanType.USER_FOLLOW_GRAPH, 9L);
    }

    @Test
    void scanPostCassandraBatchCreatesCheckpointRowWhenMissing() {
        when(checkpointMapper.findByScanType(ReconciliationScanType.POST_CASSANDRA)).thenReturn(null);
        when(knowPostMapper.listPublishedPostIdsCursor(0L, 1000)).thenReturn(List.of(9L));
        when(postTextRepository.existsById(9L)).thenReturn(false);

        service.scanPostCassandraBatch();

        ArgumentCaptor<ReconciliationCheckpoint> captor = ArgumentCaptor.forClass(ReconciliationCheckpoint.class);
        verify(checkpointMapper).upsert(captor.capture());
        assertThat(captor.getValue().getScanType()).isEqualTo(ReconciliationScanType.POST_CASSANDRA);
        assertThat(captor.getValue().getLastScannedId()).isZero();
        assertThat(captor.getValue().getUpdatedAt()).isEqualTo(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
        verify(reconciliationService).createTaskIfAbsent(
                ReconciliationTaskType.CASSANDRA_TEXT,
                ReconciliationTargetType.POST,
                9L
        );
        verify(checkpointMapper).updateCheckpoint(ReconciliationScanType.POST_CASSANDRA, 9L);
    }

    @Test
    void recoverStuckPublishingDelegatesToPublishAttemptService() {
        int recoveredCount = service.recoverStuckPublishingPosts();

        assertThat(recoveredCount).isEqualTo(7);
        assertThat(recovered.get()).isEqualTo(1);
    }

    private static final class RecordingSearchIndexService extends SearchIndexService {
        private final Map<Long, Boolean> existing = new HashMap<>();
        private final Map<Long, Map<String, Long>> counts = new HashMap<>();
        private final Map<Long, RuntimeException> failures = new HashMap<>();

        private RecordingSearchIndexService() {
            super(null, null, null, null, null);
        }

        void setExists(long postId, boolean exists) {
            existing.put(postId, exists);
        }

        void setFailure(long postId, RuntimeException failure) {
            failures.put(postId, failure);
        }

        void setCounts(long postId, Map<String, Long> engagementCounts) {
            counts.put(postId, engagementCounts);
        }

        @Override
        public boolean hasKnowPostDocument(long id) {
            RuntimeException failure = failures.get(id);
            if (failure != null) {
                throw failure;
            }
            return existing.getOrDefault(id, false);
        }

        @Override
        public Map<String, Long> readKnowPostEngagementCounts(long id) {
            RuntimeException failure = failures.get(id);
            if (failure != null) {
                throw failure;
            }
            return counts.getOrDefault(id, Map.of());
        }
    }

    private static final class RecordingRagIndexService extends RagIndexService {
        private final Map<Long, Boolean> existing = new HashMap<>();
        private final Map<Long, RuntimeException> failures = new HashMap<>();
        private final List<Long> queriedPostIds = new ArrayList<>();

        private RecordingRagIndexService() {
            super(null, null, null, null, null);
        }

        void setExists(long postId, boolean exists) {
            existing.put(postId, exists);
        }

        void setFailure(long postId, RuntimeException failure) {
            failures.put(postId, failure);
        }

        @Override
        public boolean hasIndexForPost(long postId) {
            queriedPostIds.add(postId);
            RuntimeException failure = failures.get(postId);
            if (failure != null) {
                throw failure;
            }
            return existing.getOrDefault(postId, false);
        }

        List<Long> queriedPostIds() {
            return queriedPostIds;
        }
    }
}
