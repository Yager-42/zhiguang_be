package com.tongji.reconciliation.scan;

import com.tongji.comment.mapper.CommentMapper;
import com.tongji.knowpost.mapper.KnowPostMapper;
import com.tongji.knowpost.model.KnowPostDetailRow;
import com.tongji.promotion.bprime.config.PromotionBPrimeProperties;
import com.tongji.promotion.mapper.PromotionAuctionWindowMapper;
import com.tongji.promotion.model.PromotionAuctionWindow;
import com.tongji.promotion.model.PromotionAuctionWindowStatus;
import com.tongji.reconciliation.executor.FollowInboxReconciler.FollowInboxPayload;
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
import com.tongji.counter.service.CounterService;
import com.tongji.user.mapper.UserMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class ReconciliationScanService {

    private static final int BATCH_SIZE = 1000;

    private final ReconciliationCheckpointMapper checkpointMapper;
    private final KnowPostMapper knowPostMapper;
    private final CommentMapper commentMapper;
    private final UserMapper userMapper;
    private final ReconciliationService reconciliationService;
    private final GorseClient gorseClient;
    private final GorseProperties gorseProperties;
    private final ObjectMapper objectMapper;
    private final CounterService counterService;
    private final SearchIndexService searchIndexService;
    private final PostTextArchiveRepository postTextArchiveRepository;
    private final CommentTextRepository commentTextRepository;
    private final PromotionAuctionWindowMapper promotionAuctionWindowMapper;
    private final PromotionAuctionCompensationService promotionAuctionCompensationService;
    private final PromotionBPrimeProperties promotionBPrimeProperties;
    private final Clock clock;

    @Autowired
    public ReconciliationScanService(ReconciliationCheckpointMapper checkpointMapper,
                                     KnowPostMapper knowPostMapper,
                                     CommentMapper commentMapper,
                                     UserMapper userMapper,
                                     @Lazy ReconciliationService reconciliationService,
                                     GorseClient gorseClient,
                                     GorseProperties gorseProperties,
                                     ObjectMapper objectMapper,
                                     CounterService counterService,
                                     SearchIndexService searchIndexService,
                                     PostTextArchiveRepository postTextArchiveRepository,
                                     CommentTextRepository commentTextRepository,
                                     PromotionAuctionWindowMapper promotionAuctionWindowMapper,
                                     PromotionAuctionCompensationService promotionAuctionCompensationService,
                                     PromotionBPrimeProperties promotionBPrimeProperties) {
        this(
                checkpointMapper,
                knowPostMapper,
                commentMapper,
                userMapper,
                reconciliationService,
                gorseClient,
                gorseProperties,
                objectMapper,
                counterService,
                searchIndexService,
                postTextArchiveRepository,
                commentTextRepository,
                promotionAuctionWindowMapper,
                promotionAuctionCompensationService,
                promotionBPrimeProperties,
                Clock.systemDefaultZone()
        );
    }

    ReconciliationScanService(ReconciliationCheckpointMapper checkpointMapper,
                              KnowPostMapper knowPostMapper,
                              CommentMapper commentMapper,
                              UserMapper userMapper,
                              ReconciliationService reconciliationService,
                              GorseClient gorseClient,
                              GorseProperties gorseProperties,
                              ObjectMapper objectMapper,
                              CounterService counterService,
                              SearchIndexService searchIndexService,
                              PostTextArchiveRepository postTextArchiveRepository,
                              CommentTextRepository commentTextRepository,
                              PromotionAuctionWindowMapper promotionAuctionWindowMapper,
                              PromotionAuctionCompensationService promotionAuctionCompensationService,
                              PromotionBPrimeProperties promotionBPrimeProperties,
                              Clock clock) {
        this.checkpointMapper = checkpointMapper;
        this.knowPostMapper = knowPostMapper;
        this.commentMapper = commentMapper;
        this.userMapper = userMapper;
        this.reconciliationService = reconciliationService;
        this.gorseClient = gorseClient;
        this.gorseProperties = gorseProperties;
        this.objectMapper = objectMapper;
        this.counterService = counterService;
        this.searchIndexService = searchIndexService;
        this.postTextArchiveRepository = postTextArchiveRepository;
        this.commentTextRepository = commentTextRepository;
        this.promotionAuctionWindowMapper = promotionAuctionWindowMapper;
        this.promotionAuctionCompensationService = promotionAuctionCompensationService;
        this.promotionBPrimeProperties = promotionBPrimeProperties;
        this.clock = clock;
    }

    public void scanPostEsBatch() {
        scanPostBatch(
                ReconciliationScanType.POST_ES,
                this::postEsMissingOrDrifted,
                ReconciliationTaskType.ES_INDEX
        );
    }

    public void scanPostGorseBatch() {
        if (!gorseProperties.isEnabled()) {
            return;
        }
        scanIds(
                ReconciliationScanType.POST_GORSE,
                checkpoint -> knowPostMapper.listPublishedPostIdsCursor(checkpoint, BATCH_SIZE),
                id -> {
                    boolean missing;
                    try {
                        missing = !gorseClient.hasItem(id);
                    } catch (RuntimeException probeFailure) {
                        missing = true;
                    }
                    if (missing) {
                        reconciliationService.createTaskIfAbsent(
                                ReconciliationTaskType.GORSE_ITEM_UPSERT,
                                ReconciliationTargetType.POST,
                                id
                        );
                    }
                }
        );
    }

    public void scanPostCassandraBatch() {
        scanPostBatch(
                ReconciliationScanType.POST_CASSANDRA,
                id -> !postTextArchiveRepository.existsById(id),
                ReconciliationTaskType.CASSANDRA_TEXT
        );
    }

    public void scanCommentCassandraBatch() {
        scanIds(
                ReconciliationScanType.COMMENT_CASSANDRA,
                checkpoint -> commentMapper.listCommentIdsCursor(checkpoint, BATCH_SIZE),
                id -> {
                    if (!commentTextRepository.existsById(id)) {
                        reconciliationService.createDeadTaskIfAbsent(
                                ReconciliationTaskType.CASSANDRA_TEXT,
                                ReconciliationTargetType.COMMENT,
                                id,
                                "Comment " + id + " cannot be repaired: no durable source body is stored for reconciliation"
                        );
                    }
                }
        );
    }

    public void scanPostCommentCountBatch() {
        scanIds(
                ReconciliationScanType.POST_COMMENT_COUNT,
                checkpoint -> knowPostMapper.listPublishedPostIdsCursor(checkpoint, BATCH_SIZE),
                id -> reconciliationService.createTaskIfAbsent(
                        ReconciliationTaskType.COMMENT_COUNT,
                        ReconciliationTargetType.POST,
                        id
                )
        );
    }

    public void scanCommentReplyCountBatch() {
        scanIds(
                ReconciliationScanType.COMMENT_REPLY_COUNT,
                checkpoint -> commentMapper.listCommentIdsCursor(checkpoint, BATCH_SIZE),
                id -> reconciliationService.createTaskIfAbsent(
                        ReconciliationTaskType.COMMENT_COUNT,
                        ReconciliationTargetType.COMMENT,
                        id
                )
        );
    }

    public void scanUserFollowGraphBatch() {
        scanIds(
                ReconciliationScanType.USER_FOLLOW_GRAPH,
                checkpoint -> userMapper.listUserIdsCursor(checkpoint, BATCH_SIZE),
                id -> reconciliationService.createTaskIfAbsent(
                        ReconciliationTaskType.FOLLOW_GRAPH,
                        ReconciliationTargetType.USER,
                        id
                )
        );
    }


    public FollowInboxTaskSpec buildFollowInboxTaskSpec(long postId) {
        KnowPostDetailRow row = knowPostMapper.findDetailById(postId);
        if (row == null || row.getCreatorId() == null || row.getPublishTime() == null) {
            return null;
        }
        try {
            return new FollowInboxTaskSpec(
                    row.getCreatorId(),
                    objectMapper.writeValueAsString(new FollowInboxPayload(
                            postId,
                            row.getCreatorId(),
                            row.getPublishTime().toString(),
                            false
                    ))
            );
        } catch (Exception e) {
            throw new IllegalStateException("Unable to serialize follow inbox payload for post " + postId, e);
        }
    }

    public record FollowInboxTaskSpec(
            long authorId,
            String payload
    ) {
    }

    private void scanPostBatch(String scanType,
                               java.util.function.Predicate<Long> missingCheck,
                               String taskType) {
        scanIds(
                scanType,
                checkpoint -> knowPostMapper.listPublishedPostIdsCursor(checkpoint, BATCH_SIZE),
                id -> {
                    if (missingCheck.test(id)) {
                        reconciliationService.createTaskIfAbsent(taskType, ReconciliationTargetType.POST, id);
                    }
                }
        );
    }

    public void scanPromotionSettledWindowBatch() {
        ReconciliationCheckpoint checkpoint = currentCheckpointRow(ReconciliationScanType.PROMOTION_SETTLED_WINDOW);
        Instant lookbackStart = Instant.now(clock)
                .minus(java.time.Duration.ofSeconds(promotionBPrimeProperties.getSettledCompensationLookbackSeconds()));
        List<PromotionAuctionWindow> windows = promotionAuctionWindowMapper.listSettledWindowsCursor(
                checkpoint.getLastScannedAt(),
                checkpoint.getLastScannedId() == null ? 0L : checkpoint.getLastScannedId(),
                lookbackStart,
                BATCH_SIZE
        );
        if (windows.isEmpty()) {
            return;
        }
        for (PromotionAuctionWindow window : windows) {
            promotionAuctionCompensationService.scanWindow(window, reconciliationService);
        }
        PromotionAuctionWindow last = windows.getLast();
        checkpointMapper.updateTimeCheckpoint(
                ReconciliationScanType.PROMOTION_SETTLED_WINDOW,
                last.getSettledAt(),
                last.getId()
        );
    }

    public List<com.tongji.reconciliation.model.ReconciliationTask> rerunPromotionAuctionWindow(long windowId) {
        PromotionAuctionWindow window = promotionAuctionWindowMapper.findById(windowId);
        if (window == null || window.getStatus() != PromotionAuctionWindowStatus.SETTLED) {
            return List.of();
        }
        return promotionAuctionCompensationService.reconcileWindow(window, reconciliationService);
    }

    private void scanIds(String scanType,
                         java.util.function.Function<Long, List<Long>> loader,
                         java.util.function.Consumer<Long> taskCreator) {
        long checkpoint = currentCheckpoint(scanType);
        List<Long> ids = loader.apply(checkpoint);
        if (ids.isEmpty()) {
            checkpointMapper.updateCheckpoint(scanType, 0L);
            return;
        }
        for (Long id : ids) {
            taskCreator.accept(id);
        }
        checkpointMapper.updateCheckpoint(scanType, ids.get(ids.size() - 1));
    }

    private long currentCheckpoint(String scanType) {
        ReconciliationCheckpoint checkpoint = currentCheckpointRow(scanType);
        return checkpoint.getLastScannedId() == null ? 0L : checkpoint.getLastScannedId();
    }

    private ReconciliationCheckpoint currentCheckpointRow(String scanType) {
        ReconciliationCheckpoint checkpoint = checkpointMapper.findByScanType(scanType);
        if (checkpoint != null) {
            return checkpoint;
        }
        checkpointMapper.upsert(ReconciliationCheckpoint.builder()
                .scanType(scanType)
                .lastScannedId(0L)
                .updatedAt(LocalDateTime.now(clock))
                .build());
        return ReconciliationCheckpoint.builder()
                .scanType(scanType)
                .lastScannedId(0L)
                .updatedAt(LocalDateTime.now(clock))
                .build();
    }

    private boolean postEsMissingOrDrifted(long postId) {
        if (!searchIndexService.hasKnowPostDocument(postId)) {
            return true;
        }
        var factCounts = counterService.rebuildCountsFromFacts("knowpost", String.valueOf(postId), List.of("like", "fav"));
        var indexedCounts = searchIndexService.readKnowPostEngagementCounts(postId);
        return factCounts.getOrDefault("like", 0L).longValue() != indexedCounts.getOrDefault("like", 0L).longValue()
                || factCounts.getOrDefault("fav", 0L).longValue() != indexedCounts.getOrDefault("fav", 0L).longValue();
    }
}
