package com.tongji.comment.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PendingComment {
    private Long pendingCommentId;
    private Long postId;
    private Long creatorId;
    private String clientRequestId;
    private String status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
