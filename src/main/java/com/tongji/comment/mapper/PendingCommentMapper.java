package com.tongji.comment.mapper;

import com.tongji.comment.model.PendingComment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface PendingCommentMapper {
    int insert(PendingComment pendingComment);

    PendingComment findById(@Param("pendingCommentId") Long pendingCommentId);

    PendingComment findByCreatorAndClientRequestId(@Param("creatorId") Long creatorId,
                                                   @Param("clientRequestId") String clientRequestId);

    int updateStatus(@Param("pendingCommentId") Long pendingCommentId,
                     @Param("status") String status);

    int updateStatusIfCurrent(@Param("pendingCommentId") Long pendingCommentId,
                              @Param("status") String status,
                              @Param("currentStatus") String currentStatus);

    int updateStatusByCreatorAndClientRequestId(@Param("creatorId") Long creatorId,
                                                @Param("clientRequestId") String clientRequestId,
                                                @Param("status") String status);
}
