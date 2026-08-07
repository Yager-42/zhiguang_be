package com.tongji.comment.mapper;

import com.tongji.comment.model.CommentOutbox;
import com.tongji.comment.model.CommentOutboxRetry;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface CommentOutboxMapper {
    int insert(CommentOutbox outbox);

    int insertIgnore(CommentOutbox outbox);

    int releaseExpiredClaims(@Param("now") LocalDateTime now);

    int claimReady(@Param("claimToken") String claimToken,
                   @Param("now") LocalDateTime now,
                   @Param("claimedUntil") LocalDateTime claimedUntil,
                   @Param("limit") int limit);

    List<CommentOutbox> findClaimed(@Param("claimToken") String claimToken);

    int markPublishedBatch(@Param("eventIds") List<Long> eventIds,
                           @Param("claimToken") String claimToken,
                           @Param("publishedAt") LocalDateTime publishedAt);

    int markRetryBatch(@Param("retries") List<CommentOutboxRetry> retries,
                       @Param("claimToken") String claimToken);

    int deletePublishedBefore(@Param("cutoff") LocalDateTime cutoff, @Param("limit") int limit);
}
