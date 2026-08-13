package com.tongji.comment.service.impl;

import com.tongji.comment.event.CommentEventWriter;
import com.tongji.comment.mapper.CommentMapper;
import com.tongji.comment.model.Comment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class CommentMutationService {
    private final CommentMapper commentMapper;
    private final CommentEventWriter eventWriter;

    public CommentMutationService(CommentMapper commentMapper,
                                  CommentEventWriter eventWriter) {
        this.commentMapper = commentMapper;
        this.eventWriter = eventWriter;
    }

    @Transactional
    public void deleteFinalizer(Comment comment, long creatorId) {
        if (commentMapper.softDelete(comment.getCommentId(), creatorId) == 0) {
            throw new IllegalStateException("comment delete state changed concurrently");
        }
        eventWriter.deleted(comment);
    }

    @Transactional
    public void moderate(long commentId) {
        Comment comment = commentMapper.findById(commentId);
        if (comment == null) {
            throw new IllegalStateException("comment moderation target does not exist");
        }
        if (Integer.valueOf(1).equals(comment.getStatus())) {
            return;
        }
        if (commentMapper.softDeleteForModeration(commentId) == 0) {
            throw new IllegalStateException("comment moderation state changed concurrently");
        }
        eventWriter.moderated(comment);
    }

}
