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
public class CommentOutbox {
    private Long eventId;
    private String eventType;
    private Long aggregateId;
    private String payload;
    private Integer state;
    private Integer retryCount;
    private LocalDateTime nextAttemptAt;
    private String claimToken;
    private LocalDateTime claimedUntil;
    private String lastError;
    private LocalDateTime createdAt;
    private LocalDateTime publishedAt;
}
