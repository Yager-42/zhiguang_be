package com.tongji.recommendation.feed;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.common.util.OutboxMessageUtil;
import com.tongji.relation.mapper.RelationMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.TaskExecutor;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TimelineDispatcherFollowFeedTest {

    @Mock
    private RelationMapper relationMapper;
    @Mock
    private TimelineExecutor timelineExecutor;
    @Mock
    private TaskExecutor feedTimelineExecutor;
    @Mock
    private Acknowledgment acknowledgment;

    private TimelineDispatcher dispatcher;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        dispatcher = new TimelineDispatcher(new ObjectMapper(), relationMapper, timelineExecutor, feedTimelineExecutor);
    }

    @Test
    void contentPublishedFromNormalAuthorDispatchesInboxFanout() {
        when(relationMapper.countFollowerActive(7L)).thenReturn(9999);

        dispatcher.onMessage(canalMessage(contentPublishedRow(101L, 7L, Instant.parse("2026-06-18T10:15:30Z"))), acknowledgment);

        ArgumentCaptor<Runnable> taskCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(feedTimelineExecutor).execute(taskCaptor.capture());
        verify(acknowledgment, never()).acknowledge();

        taskCaptor.getValue().run();

        verify(timelineExecutor).fanout(new TimelineDispatch(
                101L,
                7L,
                Instant.parse("2026-06-18T10:15:30Z"),
                false
        ));
        verify(acknowledgment).acknowledge();
    }

    @Test
    void contentPublishedFromLargeAuthorDispatchesAuthorFeedOnly() {
        when(relationMapper.countFollowerActive(7L)).thenReturn(10000);

        dispatcher.onMessage(canalMessage(contentPublishedRow(101L, 7L, Instant.parse("2026-06-18T10:15:30Z"))), acknowledgment);

        ArgumentCaptor<Runnable> taskCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(feedTimelineExecutor).execute(taskCaptor.capture());

        taskCaptor.getValue().run();

        verify(timelineExecutor).fanout(new TimelineDispatch(
                101L,
                7L,
                Instant.parse("2026-06-18T10:15:30Z"),
                true
        ));
        verify(acknowledgment).acknowledge();
    }

    @Test
    void usesConfiguredPushPullThreshold() {
        ReflectionTestUtils.setField(dispatcher, "pushPullThreshold", 5);
        when(relationMapper.countFollowerActive(7L)).thenReturn(5);

        dispatcher.onMessage(canalMessage(contentPublishedRow(101L, 7L, Instant.parse("2026-06-18T10:15:30Z"))), acknowledgment);

        ArgumentCaptor<Runnable> taskCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(feedTimelineExecutor).execute(taskCaptor.capture());
        taskCaptor.getValue().run();

        verify(timelineExecutor).fanout(new TimelineDispatch(
                101L,
                7L,
                Instant.parse("2026-06-18T10:15:30Z"),
                true
        ));
    }

    @Test
    void ignoresNonContentPublishedRows() {
        dispatcher.onMessage(canalMessage("""
                {"payload":"{\"eventType\":\"publish_derived_failure\",\"postId\":101,\"authorId\":7}"}
                """), acknowledgment);

        verify(feedTimelineExecutor, never()).execute(org.mockito.ArgumentMatchers.any());
        verify(acknowledgment).acknowledge();
    }

    @Test
    void validPublishClassificationFailureDoesNotAcknowledgeMessage() {
        when(relationMapper.countFollowerActive(7L)).thenThrow(new RuntimeException("count failed"));

        assertThatThrownBy(() -> dispatcher.onMessage(
                canalMessage(contentPublishedRow(101L, 7L, Instant.parse("2026-06-18T10:15:30Z"))),
                acknowledgment
        )).isInstanceOf(RuntimeException.class);

        verify(feedTimelineExecutor, never()).execute(org.mockito.ArgumentMatchers.any());
        verify(acknowledgment, never()).acknowledge();
    }

    private String canalMessage(String row) {
        return """
                {"table":"outbox","type":"INSERT","data":[%s]}
                """.formatted(row);
    }

    private String contentPublishedRow(long postId, long authorId, Instant publishedAt) {
        return """
                {"payload":"{\\"eventType\\":\\"content_published\\",\\"postId\\":%d,\\"authorId\\":%d,\\"publishedAt\\":\\"%s\\"}"}
                """.formatted(postId, authorId, publishedAt);
    }
}
