package com.tongji.comment.mapper;

import com.tongji.comment.model.Comment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface CommentMapper {
    int insert(Comment comment);

    int insertIgnore(Comment comment);

    Comment findById(@Param("commentId") Long commentId);

    List<Comment> listTopLevelByPost(@Param("postId") Long postId,
                                      @Param("cursorCreateTime") LocalDateTime cursorCreateTime,
                                      @Param("cursorCommentId") Long cursorCommentId,
                                      @Param("ascending") boolean ascending,
                                      @Param("limit") int limit);

    List<Comment> listRepliesByRoot(@Param("rootId") Long rootId,
                                     @Param("cursorCreateTime") LocalDateTime cursorCreateTime,
                                     @Param("cursorCommentId") Long cursorCommentId,
                                     @Param("limit") int limit);

    int softDelete(@Param("commentId") Long commentId, @Param("creatorId") Long creatorId);

    int softDeleteForModeration(@Param("commentId") Long commentId);

    List<Long> listCommentIdsCursor(@Param("cursorCommentId") Long cursorCommentId,
                                    @Param("limit") int limit);

    int countActiveTopLevelByPost(@Param("postId") Long postId);

    int countActiveRepliesByRoot(@Param("rootId") Long rootId);

    int updateReplyCount(@Param("commentId") Long commentId, @Param("replyCount") int replyCount);
}
