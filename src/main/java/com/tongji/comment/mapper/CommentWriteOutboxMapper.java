package com.tongji.comment.mapper;

import com.tongji.comment.model.CommentWriteOutbox;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface CommentWriteOutboxMapper {
    int insert(CommentWriteOutbox outbox);

    int releaseExpiredClaims(@Param("now") LocalDateTime now);

    int claimReady(@Param("claimToken") String claimToken,
                   @Param("now") LocalDateTime now,
                   @Param("claimUntil") LocalDateTime claimUntil,
                   @Param("limit") int limit);

    List<CommentWriteOutbox> findClaimed(@Param("claimToken") String claimToken);

    int markPublished(@Param("commentId") Long commentId,
                      @Param("claimToken") String claimToken,
                      @Param("publishedAt") LocalDateTime publishedAt);

    int markRetry(@Param("commentId") Long commentId,
                  @Param("claimToken") String claimToken,
                  @Param("attemptCount") int attemptCount,
                  @Param("nextAttemptAt") LocalDateTime nextAttemptAt,
                  @Param("lastError") String lastError);
}
