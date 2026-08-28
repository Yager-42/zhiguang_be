package com.tongji.reconciliation.scan;

import com.tongji.comment.mapper.CommentMapper;
import com.tongji.counter.service.CounterService;
import com.tongji.knowpost.mapper.KnowPostMapper;
import com.tongji.promotion.bprime.config.PromotionBPrimeProperties;
import com.tongji.promotion.mapper.PromotionAuctionWindowMapper;
import com.tongji.promotion.model.PromotionAuctionWindow;
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
import com.tongji.storage.text.PostTextArchiveRepository;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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
    private PostTextArchiveRepository postTextArchiveRepository;
    private CommentTextRepository commentTextRepository;
    private PromotionAuctionWindowMapper promotionAuctionWindowMapper;
    private PromotionAuctionCompensationService promotionAuctionCompensationService;
    private PromotionBPrimeProperties promotionBPrimeProperties;
    private RecordingSearchIndexService searchIndexService;
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
        postTextArchiveRepository = org.mockito.Mockito.mock(PostTextArchiveRepository.class);
        commentTextRepository = org.mockito.Mockito.mock(CommentTextRepository.class);
        promotionAuctionWindowMapper = org.mockito.Mockito.mock(PromotionAuctionWindowMapper.class);
        promotionAuctionCompensationService = org.mockito.Mockito.mock(PromotionAuctionCompensationService.class);
        promotionBPrimeProperties = new PromotionBPrimeProperties();
        searchIndexService = new RecordingSearchIndexService();
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
                postTextArchiveRepository,
                commentTextRepository,
                promotionAuctionWindowMapper,
                promotionAuctionCompensationService,
                promotionBPrimeProperties,
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
        when(postTextArchiveRepository.existsById(9L)).thenReturn(false);

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
    void scanPromotionSettledWindowBatchKeepsCheckpointWhenNoNewWindows() {
        Instant checkpointAt = NOW.minusSeconds(60);
        when(checkpointMapper.findByScanType(ReconciliationScanType.PROMOTION_SETTLED_WINDOW))
                .thenReturn(ReconciliationCheckpoint.builder()
                        .scanType(ReconciliationScanType.PROMOTION_SETTLED_WINDOW)
                        .lastScannedAt(checkpointAt)
                        .lastScannedId(9L)
                        .build());
        when(promotionAuctionWindowMapper.listSettledWindowsCursor(
                checkpointAt,
                9L,
                NOW.minusSeconds(promotionBPrimeProperties.getSettledCompensationLookbackSeconds()),
                1000
        )).thenReturn(List.of());

        service.scanPromotionSettledWindowBatch();

        verifyNoInteractions(promotionAuctionCompensationService);
        verify(checkpointMapper, never()).updateTimeCheckpoint(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyLong()
        );
    }

    @Test
    void scanPromotionSettledWindowBatchScansSettledWindowsAndAdvancesTimeCursor() {
        Instant checkpointAt = NOW.minusSeconds(120);
        PromotionAuctionWindow first = PromotionAuctionWindow.builder()
                .id(10L)
                .settledAt(NOW.minusSeconds(90))
                .build();
        PromotionAuctionWindow second = PromotionAuctionWindow.builder()
                .id(11L)
                .settledAt(NOW.minusSeconds(30))
                .build();
        when(checkpointMapper.findByScanType(ReconciliationScanType.PROMOTION_SETTLED_WINDOW))
                .thenReturn(ReconciliationCheckpoint.builder()
                        .scanType(ReconciliationScanType.PROMOTION_SETTLED_WINDOW)
                        .lastScannedAt(checkpointAt)
                        .lastScannedId(9L)
                        .build());
        when(promotionAuctionWindowMapper.listSettledWindowsCursor(
                checkpointAt,
                9L,
                NOW.minusSeconds(promotionBPrimeProperties.getSettledCompensationLookbackSeconds()),
                1000
        )).thenReturn(List.of(first, second));

        service.scanPromotionSettledWindowBatch();

        verify(promotionAuctionCompensationService).scanWindow(first, reconciliationService);
        verify(promotionAuctionCompensationService).scanWindow(second, reconciliationService);
        verify(checkpointMapper).updateTimeCheckpoint(
                ReconciliationScanType.PROMOTION_SETTLED_WINDOW,
                second.getSettledAt(),
                second.getId()
        );
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
}
