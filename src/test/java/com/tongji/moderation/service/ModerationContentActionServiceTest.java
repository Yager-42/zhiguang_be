package com.tongji.moderation.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.tongji.comment.mapper.CommentMapper;
import com.tongji.comment.service.impl.CommentMutationService;
import com.tongji.common.id.IdNamespace;
import com.tongji.common.id.IdService;
import com.tongji.knowpost.api.dto.FeedPageResponse;
import com.tongji.knowpost.api.dto.KnowPostDetailResponse;
import com.tongji.knowpost.mapper.KnowPostMapper;
import com.tongji.moderation.model.ModerationReport;
import com.tongji.moderation.service.impl.ModerationContentActionServiceImpl;
import com.tongji.outbox.OutboxMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModerationContentActionServiceTest {

    private KnowPostMapper knowPostMapper;
    private CommentMapper commentMapper;
    private OutboxMapper outboxMapper;
    private IdService idService;
    private StringRedisTemplate redisTemplate;
    private Cache<String, KnowPostDetailResponse> detailCache;
    private Cache<String, FeedPageResponse> feedPublicCache;
    private ModerationContentActionService service;
    private CommentMutationService commentMutationService;

    @BeforeEach
    void setUp() {
        knowPostMapper = mock(KnowPostMapper.class);
        commentMapper = mock(CommentMapper.class);
        outboxMapper = mock(OutboxMapper.class);
        idService = mock(IdService.class);
        redisTemplate = mock(StringRedisTemplate.class);
        detailCache = Caffeine.newBuilder().build();
        feedPublicCache = Caffeine.newBuilder().build();
        commentMutationService = mock(CommentMutationService.class);
        service = new ModerationContentActionServiceImpl(
                knowPostMapper,
                commentMapper,
                outboxMapper,
                idService,
                new ObjectMapper(),
                redisTemplate,
                detailCache,
                feedPublicCache,
                commentMutationService
        );
    }

    @Test
    void approvedPostIsRejectedAndSearchDeleteOutboxIsWrittenInAction() {
        when(knowPostMapper.rejectPublishedForModeration(101L)).thenReturn(1);
        when(idService.nextId(IdNamespace.OUTBOX_EVENT)).thenReturn(31L);

        service.applyApprovedAction(report("post", 101L));

        verify(knowPostMapper).rejectPublishedForModeration(101L);
        verify(outboxMapper).insert(org.mockito.Mockito.eq(31L),
                org.mockito.Mockito.eq("knowpost"),
                org.mockito.Mockito.eq(101L),
                org.mockito.Mockito.eq("KnowPostModerationRejected"),
                org.mockito.Mockito.contains("\"op\":\"delete\""));
        verify(redisTemplate).delete("knowpost:detail:101:v1");
        verify(redisTemplate).delete("feed:item:101");
        assertThat(detailCache.getIfPresent("knowpost:detail:101:v1")).isNull();
    }

    @Test
    void approvedPostInvalidatesIndexedFeedPages() {
        when(knowPostMapper.rejectPublishedForModeration(101L)).thenReturn(1);
        when(idService.nextId(IdNamespace.OUTBOX_EVENT)).thenReturn(31L);
        org.springframework.data.redis.core.SetOperations<String, String> setOps = org.mockito.Mockito.mock(org.springframework.data.redis.core.SetOperations.class);
        when(redisTemplate.opsForSet()).thenReturn(setOps);
        long hourSlot = System.currentTimeMillis() / 3600000L;
        when(setOps.members("feed:public:index:101:" + hourSlot)).thenReturn(Set.of("feed:public:20:1:v1"));
        when(setOps.members("feed:public:index:101:" + (hourSlot - 1))).thenReturn(Set.of("feed:public:20:2:v1"));
        feedPublicCache.put("feed:public:20:1:v1", new FeedPageResponse(java.util.List.of(), 1, 20, false));
        feedPublicCache.put("feed:public:20:2:v1", new FeedPageResponse(java.util.List.of(), 2, 20, false));

        service.applyApprovedAction(report("post", 101L));

        verify(redisTemplate).delete("feed:public:20:1:v1");
        verify(redisTemplate).delete("feed:public:20:2:v1");
        verify(redisTemplate).delete("feed:public:ids:20:" + hourSlot + ":1");
        verify(redisTemplate).delete("feed:public:ids:20:" + hourSlot + ":1:hasMore");
        verify(redisTemplate).delete("feed:public:ids:20:" + (hourSlot - 1) + ":2");
        verify(redisTemplate).delete("feed:public:ids:20:" + (hourSlot - 1) + ":2:hasMore");
        verify(setOps).remove("feed:public:index:101:" + hourSlot, "feed:public:20:1:v1");
        verify(setOps).remove("feed:public:index:101:" + (hourSlot - 1), "feed:public:20:2:v1");
        assertThat(feedPublicCache.getIfPresent("feed:public:20:1:v1")).isNull();
        assertThat(feedPublicCache.getIfPresent("feed:public:20:2:v1")).isNull();
    }

    @Test
    void redisCacheFailureDoesNotFailApprovedPostAction() {
        when(knowPostMapper.rejectPublishedForModeration(101L)).thenReturn(1);
        when(idService.nextId(IdNamespace.OUTBOX_EVENT)).thenReturn(31L);
        when(redisTemplate.delete("knowpost:detail:101:v1")).thenThrow(new IllegalStateException("redis down"));

        service.applyApprovedAction(report("post", 101L));

        verify(outboxMapper).insert(org.mockito.Mockito.eq(31L),
                org.mockito.Mockito.eq("knowpost"),
                org.mockito.Mockito.eq(101L),
                org.mockito.Mockito.eq("KnowPostModerationRejected"),
                org.mockito.Mockito.contains("\"op\":\"delete\""));
    }

    @Test
    void approvedCommentOnlySoftDeletesMetadata() {
        service.applyApprovedAction(report("comment", 301L));

        verify(commentMutationService).moderate(301L);
        verify(outboxMapper, never()).insert(org.mockito.Mockito.any(), org.mockito.Mockito.any(), org.mockito.Mockito.any(), org.mockito.Mockito.any(), org.mockito.Mockito.any());
    }

    @Test
    void postActionFailsWhenNoPublishedPostWasUpdated() {
        when(knowPostMapper.rejectPublishedForModeration(101L)).thenReturn(0);
        when(knowPostMapper.findById(101L)).thenReturn(null);

        assertThatThrownBy(() -> service.applyApprovedAction(report("post", 101L)))
                .hasMessageContaining("目标不存在");

        verify(outboxMapper, never()).insert(org.mockito.Mockito.any(), org.mockito.Mockito.any(), org.mockito.Mockito.any(), org.mockito.Mockito.any(), org.mockito.Mockito.any());
    }

    @Test
    void commentActionFailsWhenNoActiveCommentWasUpdated() {
        org.mockito.Mockito.doThrow(new IllegalStateException("comment moderation target does not exist"))
                .when(commentMutationService).moderate(301L);

        assertThatThrownBy(() -> service.applyApprovedAction(report("comment", 301L)))
                .hasMessageContaining("does not exist");
    }

    @Test
    void actionRejectsMissingTargetIdClearly() {
        assertThatThrownBy(() -> service.applyApprovedAction(ModerationReport.builder()
                .id(21L)
                .targetType("post")
                .build()))
                .hasMessageContaining("目标ID");
    }

    @Test
    void alreadyRejectedPostActionIsIdempotentSuccess() {
        when(knowPostMapper.rejectPublishedForModeration(101L)).thenReturn(0);
        when(knowPostMapper.findById(101L)).thenReturn(com.tongji.knowpost.model.KnowPost.builder()
                .id(101L)
                .status("rejected")
                .build());

        service.applyApprovedAction(report("post", 101L));

        verify(outboxMapper, never()).insert(org.mockito.Mockito.any(), org.mockito.Mockito.any(), org.mockito.Mockito.any(), org.mockito.Mockito.any(), org.mockito.Mockito.any());
    }

    @Test
    void alreadyDeletedCommentActionIsIdempotentSuccess() {
        service.applyApprovedAction(report("comment", 301L));

        verify(commentMutationService).moderate(301L);
    }

    private ModerationReport report(String targetType, long targetId) {
        return ModerationReport.builder()
                .id(21L)
                .targetType(targetType)
                .targetId(targetId)
                .build();
    }
}
