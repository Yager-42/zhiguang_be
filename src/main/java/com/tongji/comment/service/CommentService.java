package com.tongji.comment.service;

import com.tongji.comment.api.dto.CommentPageResponse;
import com.tongji.comment.api.dto.CommentStatusResponse;
import com.tongji.comment.api.dto.CommentSubmitRequest;
import com.tongji.comment.api.dto.CommentSubmitResponse;

import java.time.LocalDateTime;

public interface CommentService {
    CommentSubmitResponse submit(long creatorId, long postId, CommentSubmitRequest request);

    CommentStatusResponse status(long pendingCommentId);

    CommentPageResponse pageComments(long postId, LocalDateTime cursorCreateTime, Long cursorCommentId, int limit, long currentUserId);

    CommentPageResponse pageReplies(long rootId, LocalDateTime cursorCreateTime, Long cursorCommentId, int limit);

    void delete(long creatorId, long commentId);
}
