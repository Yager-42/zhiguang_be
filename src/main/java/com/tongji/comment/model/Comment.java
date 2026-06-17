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
public class Comment {
    private Long commentId;
    private Long postId;
    private Long rootId;
    private Long parentId;
    private Long creatorId;
    private String clientRequestId;
    private Integer status;
    private Integer likeCount;
    private Integer replyCount;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
