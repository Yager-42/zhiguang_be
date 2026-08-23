package com.tongji.favorite.config;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 保证存量部署具备收藏关系表；新环境仍以 {@code db/schema.sql} 为完整 DDL 基线。
 *
 * @since 2026-08-21
 */
@Component
public class FavoriteSchemaInitializer {
    private final JdbcTemplate jdbcTemplate;

    public FavoriteSchemaInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 幂等创建收藏关系表。
     */
    @PostConstruct
    public void initialize() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS user_favorite (
                    user_id BIGINT UNSIGNED NOT NULL COMMENT '收藏用户 ID',
                    post_id BIGINT UNSIGNED NOT NULL COMMENT '被收藏知文 ID',
                    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '最近一次收藏时间',
                    PRIMARY KEY (user_id, post_id),
                    KEY idx_user_favorite_page (user_id, created_at DESC, post_id DESC)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """);
    }
}
