package com.tongji.moderation.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.tongji.comment.mapper.CommentMapper;
import com.tongji.comment.service.impl.CommentMutationService;
import com.tongji.common.exception.BusinessException;
import com.tongji.common.exception.ErrorCode;
import com.tongji.common.id.IdNamespace;
import com.tongji.common.id.IdService;
import com.tongji.knowpost.api.dto.FeedPageResponse;
import com.tongji.knowpost.api.dto.KnowPostDetailResponse;
import com.tongji.knowpost.mapper.KnowPostMapper;
import com.tongji.moderation.model.ModerationReport;
import com.tongji.moderation.model.ModerationTargetType;
import com.tongji.moderation.service.ModerationContentActionService;
import com.tongji.outbox.OutboxMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.Set;

@Slf4j
@Service
public class ModerationContentActionServiceImpl implements ModerationContentActionService {
    private static final int DETAIL_LAYOUT_VER = 1;

    private final KnowPostMapper knowPostMapper;
    private final CommentMapper commentMapper;
    private final OutboxMapper outboxMapper;
    private final IdService idService;
    private final ObjectMapper objectMapper;
    private final StringRedisTemplate redisTemplate;
    private final Cache<String, KnowPostDetailResponse> detailCache;
    private final Cache<String, FeedPageResponse> feedPublicCache;
    private final CommentMutationService commentMutationService;

    public ModerationContentActionServiceImpl(KnowPostMapper knowPostMapper,
                                              CommentMapper commentMapper,
                                              OutboxMapper outboxMapper,
                                              IdService idService,
                                              ObjectMapper objectMapper,
                                              StringRedisTemplate redisTemplate,
                                              @Qualifier("knowPostDetailCache") Cache<String, KnowPostDetailResponse> detailCache,
                                              @Qualifier("feedPublicCache") Cache<String, FeedPageResponse> feedPublicCache,
                                              CommentMutationService commentMutationService) {
        this.knowPostMapper = knowPostMapper;
        this.commentMapper = commentMapper;
        this.outboxMapper = outboxMapper;
        this.idService = idService;
        this.objectMapper = objectMapper;
        this.redisTemplate = redisTemplate;
        this.detailCache = detailCache;
        this.feedPublicCache = feedPublicCache;
        this.commentMutationService = commentMutationService;
    }

    @Override
    @Transactional
    public void applyApprovedAction(ModerationReport report) {
        Long targetId = report.getTargetId();
        if (targetId == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "审核目标ID非法");
        }
        if (ModerationTargetType.POST.equals(report.getTargetType())) {
            rejectPost(targetId);
            return;
        }
        if (ModerationTargetType.COMMENT.equals(report.getTargetType())) {
            commentMutationService.moderate(targetId);
            return;
        }
        throw new BusinessException(ErrorCode.BAD_REQUEST, "审核目标类型非法");
    }

    private void rejectPost(long postId) {
        int updated = knowPostMapper.rejectPublishedForModeration(postId);
        if (updated == 0) {
            assertPostAlreadyUnavailable(postId);
            return;
        }
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("entity", "knowpost");
            payload.put("op", "delete");
            payload.put("id", postId);
            payload.put("source", "moderation");
            outboxMapper.insert(
                    idService.nextId(IdNamespace.OUTBOX_EVENT),
                    "knowpost",
                    postId,
                    "KnowPostModerationRejected",
                    objectMapper.writeValueAsString(payload)
            );
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "审核下架事件序列化失败");
        }
        invalidatePostCachesAfterCommit(postId);
    }

    private void invalidatePostCachesAfterCommit(long postId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            invalidatePostCachesBestEffort(postId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                invalidatePostCachesBestEffort(postId);
            }
        });
    }

    private void invalidatePostCachesBestEffort(long postId) {
        try {
            invalidatePostDetailCache(postId);
        } catch (RuntimeException exception) {
            log.warn("moderation post cache invalidation failed, postId={}: {}", postId, exception.getMessage());
        }
    }

    private void invalidatePostDetailCache(long postId) {
        String key = "knowpost:detail:" + postId + ":v" + DETAIL_LAYOUT_VER;
        redisTemplate.delete(key);
        detailCache.invalidate(key);
        redisTemplate.delete("feed:item:" + postId);
        invalidateIndexedFeedPages(postId);
    }

    private void invalidateIndexedFeedPages(long postId) {
        SetOperations<String, String> setOps = redisTemplate.opsForSet();
        if (setOps == null) {
            return;
        }
        long hourSlot = System.currentTimeMillis() / 3600000L;
        for (long slot : new long[]{hourSlot, hourSlot - 1}) {
            String indexKey = "feed:public:index:" + postId + ":" + slot;
            Set<String> keys = setOps.members(indexKey);
            if (keys == null || keys.isEmpty()) {
                continue;
            }
            Set<String> uniqueKeys = new LinkedHashSet<>(keys);
            for (String key : uniqueKeys) {
                redisTemplate.delete(key);
                feedPublicCache.invalidate(key);
                deleteFeedPageFragments(key, slot);
                setOps.remove(indexKey, key);
            }
        }
    }

    private void deleteFeedPageFragments(String pageKey, long slot) {
        String[] parts = pageKey.split(":");
        if (parts.length != 5 || !"feed".equals(parts[0]) || !"public".equals(parts[1])) {
            return;
        }
        String size = parts[2];
        String page = parts[3];
        String idsKey = "feed:public:ids:" + size + ":" + slot + ":" + page;
        redisTemplate.delete(idsKey);
        redisTemplate.delete(idsKey + ":hasMore");
    }

    private void assertPostAlreadyUnavailable(long postId) {
        com.tongji.knowpost.model.KnowPost post = knowPostMapper.findById(postId);
        if (post == null || "published".equals(post.getStatus())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "目标不存在或仍未下架");
        }
    }

    private void assertCommentAlreadyDeleted(long commentId) {
        com.tongji.comment.model.Comment comment = commentMapper.findById(commentId);
        if (comment == null || !Integer.valueOf(1).equals(comment.getStatus())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "目标不存在或仍未删除");
        }
    }
}
