package com.tongji.comment.service.impl;

import com.tongji.comment.api.dto.CommentItemResponse;
import com.tongji.comment.api.dto.CommentPageResponse;
import com.tongji.comment.api.dto.CommentStatusResponse;
import com.tongji.comment.api.dto.CommentSubmitRequest;
import com.tongji.comment.api.dto.CommentSubmitResponse;
import com.tongji.comment.event.CommentWriteEvent;
import com.tongji.comment.event.CommentWriteProducer;
import com.tongji.comment.mapper.CommentMapper;
import com.tongji.comment.mapper.PendingCommentMapper;
import com.tongji.comment.model.Comment;
import com.tongji.comment.model.PendingComment;
import com.tongji.comment.service.CommentService;
import com.tongji.common.exception.BusinessException;
import com.tongji.common.exception.ErrorCode;
import com.tongji.common.id.IdNamespace;
import com.tongji.common.id.IdService;
import com.tongji.counter.service.CounterService;
import com.tongji.storage.text.TextStorageService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
public class CommentServiceImpl implements CommentService {
    private static final int MAX_PAGE_SIZE = 100;
    private static final String PENDING = "pending";
    private static final String DELETED_BODY = "[deleted]";

    private final CommentMapper commentMapper;
    private final PendingCommentMapper pendingCommentMapper;
    private final TextStorageService textStorageService;
    private final IdService idService;
    private final CommentWriteProducer commentWriteProducer;
    private final CounterService counterService;

    public CommentServiceImpl(CommentMapper commentMapper,
                              PendingCommentMapper pendingCommentMapper,
                              TextStorageService textStorageService,
                              IdService idService,
                              CommentWriteProducer commentWriteProducer,
                              CounterService counterService) {
        this.commentMapper = commentMapper;
        this.pendingCommentMapper = pendingCommentMapper;
        this.textStorageService = textStorageService;
        this.idService = idService;
        this.commentWriteProducer = commentWriteProducer;
        this.counterService = counterService;
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
        commentWriteProducer.publish(new CommentWriteEvent(commentId, postId, rootId, parentId, creatorId,
                request.clientRequestId(), request.body()));
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
        return page(commentMapper.listTopLevelByPost(postId, cursorCreateTime, cursorCommentId, limit + 1), limit, currentUserId);
    }

    @Override
    public CommentPageResponse pageReplies(long rootId, LocalDateTime cursorCreateTime, Long cursorCommentId, int limit) {
        requirePositiveLimit(limit);
        // replies 路径不做 liked（userId=0，位图查不到点赞态，liked 恒 false）。后续楼中楼 feature 再补。
        return page(commentMapper.listRepliesByRoot(rootId, cursorCreateTime, cursorCommentId, limit + 1), limit, 0L);
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
        if (commentMapper.softDelete(commentId, creatorId) == 0) {
            throw badRequest("comment not found or not owned by user");
        }
    }

    private CommentPageResponse page(List<Comment> rows, int limit, long currentUserId) {
        boolean hasMore = rows.size() > limit;
        List<Comment> pageRows = hasMore ? rows.subList(0, limit) : rows;
        Map<Long, String> texts = textStorageService.getCommentTexts(pageRows.stream()
                .filter(row -> !Integer.valueOf(1).equals(row.getStatus()))
                .map(Comment::getCommentId)
                .toList());
        List<CommentItemResponse> items = pageRows.stream()
                .map(row -> item(row, texts, currentUserId))
                .toList();
        Comment last = items.isEmpty() ? null : pageRows.get(pageRows.size() - 1);
        return new CommentPageResponse(items,
                last == null ? null : last.getCreateTime(),
                last == null ? null : String.valueOf(last.getCommentId()),
                hasMore);
    }

    private CommentItemResponse item(Comment row, Map<Long, String> texts, long currentUserId) {
        boolean deleted = Integer.valueOf(1).equals(row.getStatus());
        boolean liked = currentUserId > 0
                && counterService.isLiked("comment", String.valueOf(row.getCommentId()), currentUserId);
        return new CommentItemResponse(
                String.valueOf(row.getCommentId()),
                String.valueOf(row.getPostId()),
                row.getRootId() == null ? null : String.valueOf(row.getRootId()),
                row.getParentId() == null ? null : String.valueOf(row.getParentId()),
                String.valueOf(row.getCreatorId()),
                deleted ? DELETED_BODY : texts.get(row.getCommentId()),
                row.getStatus(),
                deleted,
                row.getLikeCount(),
                row.getReplyCount(),
                row.getCreateTime(),
                row.getUpdateTime(),
                liked
        );
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
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
