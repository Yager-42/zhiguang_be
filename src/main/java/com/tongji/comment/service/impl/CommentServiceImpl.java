package com.tongji.comment.service.impl;

import com.tongji.comment.api.dto.CommentItemResponse;
import com.tongji.comment.api.dto.CommentPageResponse;
import com.tongji.comment.api.dto.CommentStatusResponse;
import com.tongji.comment.api.dto.CommentSubmitRequest;
import com.tongji.comment.api.dto.CommentSubmitResponse;
import com.tongji.comment.cache.CommentBaseItem;
import com.tongji.comment.cache.CommentBasePage;
import com.tongji.comment.cache.CommentCacheKeys;
import com.tongji.comment.cache.CommentPageCacheService;
import com.tongji.comment.mapper.CommentMapper;
import com.tongji.comment.mapper.CommentOutboxMapper;
import com.tongji.comment.mapper.PendingCommentMapper;
import com.tongji.comment.model.Comment;
import com.tongji.comment.model.CommentOutbox;
import com.tongji.comment.event.CommentEventType;
import com.tongji.comment.event.CommentOutboxEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.comment.model.PendingComment;
import com.tongji.comment.metrics.CommentMetrics;
import com.tongji.comment.service.CommentService;
import com.tongji.common.exception.BusinessException;
import com.tongji.common.exception.ErrorCode;
import com.tongji.common.id.IdNamespace;
import com.tongji.common.id.IdService;
import com.tongji.counter.service.CounterService;
import com.tongji.counter.service.CommentPageCounterState;
import com.tongji.storage.text.TextStorageService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import java.util.concurrent.atomic.AtomicLong;

import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class CommentServiceImpl implements CommentService {
    private static final int MAX_PAGE_SIZE = 100;
    private static final String PENDING = "pending";
    private static final String DELETED_BODY = "[deleted]";
    private static final long COUNTER_ERROR_LOG_INTERVAL_MILLIS = 10_000L;
    private static final AtomicLong LAST_COUNTER_ERROR_LOG_TIME = new AtomicLong();

    private final CommentMapper commentMapper;
    private final PendingCommentMapper pendingCommentMapper;
    private final TextStorageService textStorageService;
    private final IdService idService;
    private final CommentOutboxMapper commentOutboxMapper;
    private final CounterService counterService;
    private final ObjectMapper objectMapper;
    private final CommentPageCacheService pageCacheService;
    private final Executor commentReadExecutor;
    private final CommentMutationService mutationService;
    private final CommentMetrics metrics;

    public CommentServiceImpl(CommentMapper commentMapper,
                              PendingCommentMapper pendingCommentMapper,
                              TextStorageService textStorageService,
                              IdService idService,
                              CommentOutboxMapper commentOutboxMapper,
                              CounterService counterService,
                              ObjectMapper objectMapper,
                              CommentPageCacheService pageCacheService,
                              @Qualifier("commentReadExecutor") Executor commentReadExecutor,
                              CommentMutationService mutationService,
                              CommentMetrics metrics) {
        this.commentMapper = commentMapper;
        this.pendingCommentMapper = pendingCommentMapper;
        this.textStorageService = textStorageService;
        this.idService = idService;
        this.commentOutboxMapper = commentOutboxMapper;
        this.counterService = counterService;
        this.objectMapper = objectMapper;
        this.pageCacheService = pageCacheService;
        this.commentReadExecutor = commentReadExecutor;
        this.mutationService = mutationService;
        this.metrics = metrics;
    }

    @Override
    @Transactional
    public CommentSubmitResponse submit(long creatorId, long postId, CommentSubmitRequest request) {
        if (request == null || blank(request.clientRequestId()) || blank(request.body())) {
            throw badRequest("comment body and clientRequestId are required");
        }
        if (positive(request.rootId()) && !positive(request.parentId())) {
            throw badRequest("root comment requires parent comment");
        }
        PendingComment existing = pendingCommentMapper.findByCreatorAndClientRequestId(creatorId, request.clientRequestId());
        if (existing != null) {
            return new CommentSubmitResponse(existing.getClientRequestId(), String.valueOf(existing.getPendingCommentId()), existing.getStatus());
        }

        long rootId = 0L;
        long parentId = 0L;
        if (positive(request.parentId())) {
            Comment parent = commentMapper.findById(request.parentId());
            if (parent == null || Integer.valueOf(1).equals(parent.getStatus()) || positive(parent.getParentId())
                    || !Long.valueOf(postId).equals(parent.getPostId())) {
                throw badRequest("invalid parent comment");
            }
            parentId = parent.getCommentId();
            if (positive(request.rootId()) && !request.rootId().equals(parent.getCommentId())) {
                throw badRequest("invalid root comment");
            }
            rootId = parent.getCommentId();
        }

        long commentId = idService.nextId(IdNamespace.COMMENT);
        LocalDateTime now = LocalDateTime.now();
        try {
            pendingCommentMapper.insert(PendingComment.builder()
                    .pendingCommentId(commentId)
                    .postId(postId)
                    .creatorId(creatorId)
                    .clientRequestId(request.clientRequestId())
                    .status(PENDING)
                    .createTime(now)
                    .updateTime(now)
                    .build());
        } catch (DuplicateKeyException exception) {
            PendingComment raced = pendingCommentMapper.findByCreatorAndClientRequestId(creatorId, request.clientRequestId());
            if (raced != null) {
                return new CommentSubmitResponse(raced.getClientRequestId(), String.valueOf(raced.getPendingCommentId()), raced.getStatus());
            }
            throw exception;
        }
        long eventId = idService.nextId(IdNamespace.OUTBOX_EVENT);
        CommentOutboxEvent event = new CommentOutboxEvent(eventId, CommentEventType.COMMENT_WRITE_REQUESTED,
                commentId, postId, rootId, parentId, creatorId, request.clientRequestId(), request.body(), now);
        commentOutboxMapper.insert(CommentOutbox.builder()
                .eventId(eventId)
                .eventType(CommentEventType.COMMENT_WRITE_REQUESTED.name())
                .aggregateId(commentId)
                .payload(serialize(event))
                .nextAttemptAt(now)
                .createdAt(now)
                .build());
        return new CommentSubmitResponse(request.clientRequestId(), String.valueOf(commentId), PENDING);
    }

    @Override
    public CommentStatusResponse status(long pendingCommentId) {
        PendingComment pending = pendingCommentMapper.findById(pendingCommentId);
        if (pending == null) {
            throw badRequest("pending comment not found");
        }
        return new CommentStatusResponse(String.valueOf(pending.getPendingCommentId()), pending.getClientRequestId(), pending.getStatus());
    }

    @Override
    public CommentPageResponse pageComments(long postId, LocalDateTime cursorCreateTime, Long cursorCommentId, int limit, long currentUserId) {
        requirePositiveLimit(limit);
        CommentBasePage basePage;
        if (cursorCreateTime == null && cursorCommentId == null) {
            String key = CommentCacheKeys.postHead(postId, limit);
            basePage = pageCacheService.getHead(key, CommentCacheKeys.postHeadIndex(postId),
                    () -> loadBasePage(() -> commentMapper.listTopLevelByPost(postId, null, null, limit + 1), limit));
        } else {
            basePage = loadBasePage(
                    () -> commentMapper.listTopLevelByPost(postId, cursorCreateTime, cursorCommentId, limit + 1), limit);
        }
        return overlay(basePage, currentUserId);
    }

    @Override
    public CommentPageResponse pageReplies(long rootId, LocalDateTime cursorCreateTime, Long cursorCommentId, int limit) {
        requirePositiveLimit(limit);
        CommentBasePage basePage;
        if (cursorCreateTime == null && cursorCommentId == null) {
            String key = CommentCacheKeys.rootHead(rootId, limit);
            basePage = pageCacheService.getHead(key, CommentCacheKeys.rootHeadIndex(rootId),
                    () -> loadBasePage(() -> commentMapper.listRepliesByRoot(rootId, null, null, limit + 1), limit));
        } else {
            basePage = loadBasePage(
                    () -> commentMapper.listRepliesByRoot(rootId, cursorCreateTime, cursorCommentId, limit + 1), limit);
        }
        return overlay(basePage, 0L);
    }

    @Override
    public void delete(long creatorId, long commentId) {
        Comment comment = commentMapper.findById(commentId);
        if (comment == null
                || !Long.valueOf(creatorId).equals(comment.getCreatorId())
                || Integer.valueOf(1).equals(comment.getStatus())) {
            throw badRequest("comment not found or not owned by user");
        }
        textStorageService.deleteCommentText(commentId);
        mutationService.deleteFinalizer(comment, creatorId);
    }

    private CommentBasePage loadBasePage(Supplier<List<Comment>> query, int limit) {
        List<Comment> rows;
        try {
            rows = query.get();
            metrics.readDependency("mysql", "success");
        } catch (RuntimeException exception) {
            metrics.readDependency("mysql", "failure");
            throw exception;
        }
        boolean hasMore = rows.size() > limit;
        List<Comment> pageRows = hasMore ? rows.subList(0, limit) : rows;
        List<String> commentIds = pageRows.stream()
                .map(row -> String.valueOf(row.getCommentId()))
                .toList();
        CompletableFuture<Map<Long, String>> textsFuture = CompletableFuture.supplyAsync(
                () -> {
                    try {
                        Map<Long, String> texts = textStorageService.getCommentTexts(pageRows.stream()
                                .filter(row -> !Integer.valueOf(1).equals(row.getStatus()))
                                .map(Comment::getCommentId)
                                .toList());
                        metrics.readDependency("cassandra", "success");
                        return texts;
                    } catch (RuntimeException exception) {
                        metrics.readDependency("cassandra", "failure");
                        throw exception;
                    }
                }, commentReadExecutor);
        CompletableFuture<Map<String, CommentPageCounterState>> countersFuture = CompletableFuture.supplyAsync(
                () -> getPageState(commentIds, 0L), commentReadExecutor);
        Map<Long, String> texts = textsFuture.join();
        Map<String, CommentPageCounterState> counters = countersFuture.join();
        List<CommentBaseItem> items = pageRows.stream()
                .map(row -> baseItem(row, texts, counters))
                .toList();
        Comment last = items.isEmpty() ? null : pageRows.get(pageRows.size() - 1);
        return new CommentBasePage(items,
                last == null ? null : last.getCreateTime(),
                last == null ? null : String.valueOf(last.getCommentId()),
                hasMore);
    }

    private CommentBaseItem baseItem(Comment row,
                                     Map<Long, String> texts,
                                     Map<String, CommentPageCounterState> counters) {
        boolean deleted = Integer.valueOf(1).equals(row.getStatus());
        String commentIdStr = String.valueOf(row.getCommentId());
        CommentPageCounterState state = counters.getOrDefault(commentIdStr,
                new CommentPageCounterState(Map.of(), false));
        return new CommentBaseItem(
                commentIdStr,
                String.valueOf(row.getPostId()),
                row.getRootId() == null ? null : String.valueOf(row.getRootId()),
                row.getParentId() == null ? null : String.valueOf(row.getParentId()),
                String.valueOf(row.getCreatorId()),
                deleted ? DELETED_BODY : texts.get(row.getCommentId()),
                row.getStatus(),
                deleted,
                clamp(state.counts().getOrDefault("like", 0L)),
                clamp(state.counts().getOrDefault("comment", (long) row.getReplyCount())),
                row.getCreateTime(),
                row.getUpdateTime());
    }

    private CommentPageResponse overlay(CommentBasePage page, long currentUserId) {
        List<String> ids = page.items().stream().map(CommentBaseItem::commentId).toList();
        Map<String, CommentPageCounterState> states = getPageState(ids, currentUserId);
        List<CommentItemResponse> items = page.items().stream().map(item -> {
            CommentPageCounterState state = states.getOrDefault(item.commentId(),
                    new CommentPageCounterState(Map.of(), false));
            return new CommentItemResponse(item.commentId(), item.postId(), item.rootId(), item.parentId(),
                    item.creatorId(), item.deleted() ? DELETED_BODY : item.body(), item.status(), item.deleted(),
                    clamp(state.counts().getOrDefault("like", (long) item.likeCount())),
                    clamp(state.counts().getOrDefault("comment", (long) item.replyCount())),
                    item.createTime(), item.updateTime(), currentUserId > 0 && state.liked());
        }).toList();
        return new CommentPageResponse(items, page.nextCursorCreateTime(), page.nextCursorCommentId(), page.hasMore());
    }

    private Map<String, CommentPageCounterState> getPageState(List<String> ids, long currentUserId) {
        try {
            Map<String, CommentPageCounterState> states = counterService.getPageStateBatch(
                    "comment", ids, currentUserId > 0 ? currentUserId : null,
                    List.of("like", "comment"));
            metrics.readDependency("counter", "success");
            return states;
        } catch (RuntimeException exception) {
            metrics.readDependency("counter", "failure");
            logCounterFailure(exception);
            return Map.of();
        }
    }

    private void logCounterFailure(RuntimeException exception) {
        long now = System.currentTimeMillis();
        long previous = LAST_COUNTER_ERROR_LOG_TIME.get();
        if (now - previous >= COUNTER_ERROR_LOG_INTERVAL_MILLIS
                && LAST_COUNTER_ERROR_LOG_TIME.compareAndSet(previous, now)) {
            log.warn("comment page counter overlay failed, returning fallback counts", exception);
        }
    }

    private int clamp(long count) {
        return (int) Math.max(0L, Math.min(Integer.MAX_VALUE, count));
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private String serialize(CommentOutboxEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "comment outbox serialization failed");
        }
    }

    private static boolean positive(Long value) {
        return value != null && value > 0;
    }

    private static BusinessException badRequest(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST, message);
    }

    private static void requirePositiveLimit(int limit) {
        if (limit <= 0 || limit > MAX_PAGE_SIZE) {
            throw badRequest("limit must be between 1 and " + MAX_PAGE_SIZE);
        }
    }
}
