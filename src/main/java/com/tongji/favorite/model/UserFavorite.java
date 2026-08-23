package com.tongji.favorite.model;

import lombok.Data;

import java.time.Instant;

/**
 * 用户收藏关系持久化模型。
 *
 * @since 2026-08-21
 */
@Data
public class UserFavorite {
    private Long userId;
    private Long postId;
    private Instant createdAt;
}
