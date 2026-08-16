package com.tongji.recommendation.feed;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FeedFollowCompensationConsumerTest {

    @Mock
    private TimelineExecutor timelineExecutor;
    @Mock
    private FollowFeedService followFeedService;
    @Mock
    private Acknowledgment acknowledgment;

    private FeedFollowCompensationConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new FeedFollowCompensationConsumer(new ObjectMapper(), timelineExecutor, followFeedService);
    }

    @Test
    void followCreatedBackfillsAuthorHistoryAndInvalidatesCaches() {
        consumer.onMessage(
                canalMessage("77", "{\"type\":\"FollowCreated\",\"fromUserId\":1,\"toUserId\":2,\"id\":3}"),
                acknowledgment
        );

        verify(timelineExecutor).backfillAuthorTimeline(2L, 100);
        verify(followFeedService).invalidateAuthorHeadCache(2L);
        verify(followFeedService).invalidateTimelineCache(1L);
        verify(acknowledgment).acknowledge();
    }

    @Test
    void followCanceledOnlyInvalidatesFollowerTimelineCache() {
        consumer.onMessage(
                canalMessage("78", "{\"type\":\"FollowCanceled\",\"fromUserId\":1,\"toUserId\":2,\"id\":null}"),
                acknowledgment
        );

        verify(timelineExecutor, never()).backfillAuthorTimeline(eq(2L), eq(100));
        verify(followFeedService, never()).invalidateAuthorHeadCache(2L);
        verify(followFeedService).invalidateTimelineCache(1L);
        verify(acknowledgment).acknowledge();
    }

    @Test
    void nonRelationOrNonFollowEventsAreIgnoredAndStillAcked() {
        consumer.onMessage(
                canalMessage("79", "{\"eventType\":\"content_published\",\"postId\":101,\"authorId\":7}"),
                acknowledgment
        );
        consumer.onMessage(
                canalMessage("80", "{\"type\":\"CommentCreated\",\"fromUserId\":1,\"toUserId\":2}"),
                acknowledgment
        );

        verify(timelineExecutor, never()).backfillAuthorTimeline(anyLong(), eq(100));
        verify(followFeedService, never()).invalidateTimelineCache(anyLong());
        verify(acknowledgment, times(2)).acknowledge();
    }

    @Test
    void backfillFailurePreventsAckSoRedeliveryRetries() {
        org.mockito.Mockito.doThrow(new RuntimeException("cassandra down"))
                .when(timelineExecutor).backfillAuthorTimeline(2L, 100);

        consumer.onMessage(
                canalMessage("81", "{\"type\":\"FollowCreated\",\"fromUserId\":1,\"toUserId\":2,\"id\":3}"),
                acknowledgment
        );

        verify(acknowledgment, never()).acknowledge();
    }

    private String canalMessage(String id, String payload) {
        return "{\"table\":\"outbox\",\"type\":\"INSERT\",\"data\":[{\"id\":\"" + id
                + "\",\"payload\":\"" + payload.replace("\"", "\\\"") + "\"}]}";
    }
}
