package com.tongji.reconciliation.executor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.comment.mapper.CommentMapper;
import com.tongji.counter.service.CounterService;
import com.tongji.counter.service.UserCounterService;
import com.tongji.knowpost.mapper.KnowPostMapper;
import com.tongji.knowpost.model.KnowPost;
import com.tongji.knowpost.model.KnowPostDetailRow;
import com.tongji.recommendation.feed.TimelineExecutor;
import com.tongji.recommendation.gorse.GorseClient;
import com.tongji.recommendation.gorse.GorseItemInput;
import com.tongji.recommendation.gorse.GorseItemInputFactory;
import com.tongji.reconciliation.model.ReconciliationTargetType;
import com.tongji.reconciliation.model.ReconciliationTask;
import com.tongji.reconciliation.model.ReconciliationTaskType;
import com.tongji.relation.mapper.RelationMapper;
import com.tongji.relation.mapper.RelationMapper.RelationRepairRow;
import com.tongji.search.index.SearchIndexService;
import com.tongji.storage.text.TextStorageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReconcilerTest {

    @Mock
    private KnowPostMapper knowPostMapper;
    @Mock
    private TextStorageService textStorageService;
    @Mock
    private GorseClient gorseClient;
    @Mock
    private TimelineExecutor timelineExecutor;
    @Mock
    private CommentMapper commentMapper;
    @Mock
    private CounterService counterService;
    @Mock
    private RelationMapper relationMapper;
    @Mock
    private StringRedisTemplate redis;
    @Mock
    private ZSetOperations<String, String> zSetOperations;
    @Mock
    private UserCounterService userCounterService;

    @Test
    void esIndexReconcilerUpsertsPost() {
        ReconciliationTask task = task(ReconciliationTaskType.ES_INDEX, ReconciliationTargetType.POST, 101L);
        RecordingSearchIndexService searchIndexService = new RecordingSearchIndexService();

        new EsIndexReconciler(counterService, searchIndexService).reconcile(task);

        verify(counterService).rebuildCountsFromFacts("knowpost", "101", List.of("like", "fav"));
        org.assertj.core.api.Assertions.assertThat(searchIndexService.strictUpsertedPostId).isEqualTo(101L);
    }

    @Test
    void esIndexReconcilerPropagatesStrictUpsertFailure() {
        ReconciliationTask task = task(ReconciliationTaskType.ES_INDEX, ReconciliationTargetType.POST, 105L);

        assertThatThrownBy(() -> new EsIndexReconciler(counterService, new FailingSearchIndexService()).reconcile(task))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("strict es failure");
    }

    @Test
    void gorseItemUpsertReconcilerUpsertsPublishedPost() {
        ReconciliationTask task = task(ReconciliationTaskType.GORSE_ITEM_UPSERT, ReconciliationTargetType.POST, 107L);
        KnowPost post = KnowPost.builder()
                .id(107L)
                .creatorId(7L)
                .status("published")
                .title("测试知文")
                .tags("[\"Java\"]")
                .publishTime(java.time.Instant.parse("2026-06-18T10:15:30Z"))
                .build();
        when(knowPostMapper.findById(107L)).thenReturn(post);

        new GorseItemUpsertReconciler(
                knowPostMapper,
                gorseClient,
                new GorseItemInputFactory(new ObjectMapper())
        ).reconcile(task);

        verify(gorseClient).upsertItem(new GorseItemInput(
                107L,
                7L,
                java.time.Instant.parse("2026-06-18T10:15:30Z"),
                "测试知文",
                List.of("Java")
        ));
    }

    @Test
    void gorseFeedbackReconcilerReplaysFeedbackFromPayload() {
        ReconciliationTask task = ReconciliationTask.builder()
                .id(2L)
                .taskType(ReconciliationTaskType.GORSE_FEEDBACK)
                .targetType(ReconciliationTargetType.POST)
                .targetId(107L)
                .taskPayload("""
                        {"feedbackType":"like","userId":7,"itemId":"107"}
                        """)
                .build();

        new GorseFeedbackReconciler(new ObjectMapper(), gorseClient).reconcile(task);

        verify(gorseClient).insertFeedback("like", 7L, "107");
    }

    @Test
    void followInboxReconcilerReplaysFanoutFromPayload() {
        ReconciliationTask task = ReconciliationTask.builder()
                .id(3L)
                .taskType(ReconciliationTaskType.FOLLOW_INBOX)
                .targetType(ReconciliationTargetType.USER)
                .targetId(7L)
                .taskPayload("""
                        {"postId":101,"authorId":7,"publishedAt":"2026-06-18T10:15:30Z","largeAuthor":false}
                        """)
                .build();

        new FollowInboxReconciler(new ObjectMapper(), timelineExecutor).reconcile(task);

        verify(timelineExecutor).fanout(new com.tongji.recommendation.feed.TimelineDispatch(
                101L,
                7L,
                java.time.Instant.parse("2026-06-18T10:15:30Z"),
                false
        ));
    }

    @Test
    void cassandraTextReconcilerRestoresPostTextFromAvailableSource() {
        ReconciliationTask task = task(ReconciliationTaskType.CASSANDRA_TEXT, ReconciliationTargetType.POST, 103L);
        KnowPostDetailRow row = new KnowPostDetailRow();
        row.setId(103L);
        row.setContentUrl("http://minio/posts/103.md");
        row.setContentSha256("sha-103");
        when(knowPostMapper.findDetailById(103L)).thenReturn(row);
        when(textStorageService.getPostText(103L, "http://minio/posts/103.md")).thenReturn(Optional.of("body-103"));

        new CassandraTextReconciler(knowPostMapper, textStorageService).reconcile(task);

        verify(textStorageService).savePostText(103L, "body-103", "sha-103");
    }

    @Test
    void cassandraTextReconcilerFailsWhenPostSourceBodyMissing() {
        ReconciliationTask task = task(ReconciliationTaskType.CASSANDRA_TEXT, ReconciliationTargetType.POST, 104L);
        KnowPostDetailRow row = new KnowPostDetailRow();
        row.setId(104L);
        row.setContentUrl("http://minio/posts/104.md");
        when(knowPostMapper.findDetailById(104L)).thenReturn(row);
        when(textStorageService.getPostText(104L, "http://minio/posts/104.md")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> new CassandraTextReconciler(knowPostMapper, textStorageService).reconcile(task))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("No source body available for post 104");
    }

    @Test
    void cassandraTextReconcilerFailsForCommentWithoutDurableBodySource() {
        ReconciliationTask task = task(ReconciliationTaskType.CASSANDRA_TEXT, ReconciliationTargetType.COMMENT, 201L);

        assertThatThrownBy(() -> new CassandraTextReconciler(knowPostMapper, textStorageService).reconcile(task))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Comment 201 cannot be repaired: no durable source body is stored for reconciliation");
    }

    @Test
    void commentCountReconcilerRebuildsPostCommentCounter() {
        ReconciliationTask task = task(ReconciliationTaskType.COMMENT_COUNT, ReconciliationTargetType.POST, 301L);
        when(commentMapper.countActiveTopLevelByPost(301L)).thenReturn(5);

        new CommentCountReconciler(commentMapper, counterService).reconcile(task);

        verify(counterService).overwriteCount("knowpost", "301", "comment", 5);
    }

    @Test
    void commentCountReconcilerRebuildsRootReplyCountAndCommentCounter() {
        ReconciliationTask task = task(ReconciliationTaskType.COMMENT_COUNT, ReconciliationTargetType.COMMENT, 302L);
        when(commentMapper.countActiveRepliesByRoot(302L)).thenReturn(3);

        new CommentCountReconciler(commentMapper, counterService).reconcile(task);

        verify(commentMapper).updateReplyCount(302L, 3);
        verify(counterService).overwriteCount("comment", "302", "comment", 3);
    }

    @Test
    void followGraphReconcilerRebuildsFollowerRowsCachesAndCounters() {
        ReconciliationTask task = task(ReconciliationTaskType.FOLLOW_GRAPH, ReconciliationTargetType.USER, 7L);
        when(redis.opsForZSet()).thenReturn(zSetOperations);
        when(relationMapper.listActiveFollowingRowsByUser(7L)).thenReturn(List.of(
                new RelationRepairRow(1001L, 7L, 9L, Timestamp.from(java.time.Instant.parse("2026-06-18T10:15:30Z")))
        ));
        when(relationMapper.listActiveFollowerRowsBySourceUser(7L)).thenReturn(List.of(
                new RelationRepairRow(1001L, 7L, 9L, Timestamp.from(java.time.Instant.parse("2026-06-18T10:15:30Z"))),
                new RelationRepairRow(1003L, 7L, 10L, Timestamp.from(java.time.Instant.parse("2026-06-18T12:15:30Z")))
        ));
        when(relationMapper.listActiveFollowerRowsByUser(7L)).thenReturn(List.of(
                new RelationRepairRow(1002L, 8L, 7L, Timestamp.from(java.time.Instant.parse("2026-06-18T11:15:30Z")))
        ));

        new FollowGraphReconciler(relationMapper, redis, userCounterService).reconcile(task);

        verify(relationMapper).cancelFollower(10L, 7L);
        verify(relationMapper).insertFollower(1001L, 9L, 7L, 1);
        verify(userCounterService).rebuildAllCounters(7L);
    }

    private static ReconciliationTask task(String taskType, String targetType, long targetId) {
        return ReconciliationTask.builder()
                .id(1L)
                .taskType(taskType)
                .targetType(targetType)
                .targetId(targetId)
                .build();
    }

    private static final class RecordingSearchIndexService extends SearchIndexService {
        private Long strictUpsertedPostId;

        private RecordingSearchIndexService() {
            super(null, null, null, null, null);
        }

        @Override
        public void upsertKnowPost(long id) {
            throw new AssertionError("reconciler should use strict ES upsert");
        }

        @Override
        public void upsertKnowPostStrict(long id) {
            strictUpsertedPostId = id;
        }
    }

    private static final class FailingSearchIndexService extends SearchIndexService {
        private FailingSearchIndexService() {
            super(null, null, null, null, null);
        }

        @Override
        public void upsertKnowPostStrict(long id) {
            throw new IllegalStateException("strict es failure");
        }
    }
}
