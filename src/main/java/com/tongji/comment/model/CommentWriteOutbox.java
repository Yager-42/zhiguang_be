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
public class CommentWriteOutbox {
    private Long commentId;
    private Long postId;
    private Long rootId;
    private Long parentId;
    private Long creatorId;
    private String clientRequestId;
    private String body;
    private String state;
    private Integer attemptCount;
    private LocalDateTime nextAttemptAt;
    private String claimToken;
    private LocalDateTime claimUntil;
    private String lastError;
    private LocalDateTime publishedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
