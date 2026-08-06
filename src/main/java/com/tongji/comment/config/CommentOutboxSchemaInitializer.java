package com.tongji.comment.config;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class CommentOutboxSchemaInitializer {
    private final JdbcTemplate jdbcTemplate;

    public CommentOutboxSchemaInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void initialize() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS comment_write_outbox (
                    comment_id BIGINT UNSIGNED NOT NULL,
                    post_id BIGINT UNSIGNED NOT NULL,
                    root_id BIGINT UNSIGNED NOT NULL DEFAULT 0,
                    parent_id BIGINT UNSIGNED NOT NULL DEFAULT 0,
                    creator_id BIGINT UNSIGNED NOT NULL,
                    client_request_id VARCHAR(64) NOT NULL,
                    body TEXT NOT NULL,
                    state VARCHAR(16) NOT NULL DEFAULT 'pending',
                    attempt_count INT NOT NULL DEFAULT 0,
                    next_attempt_at DATETIME(3) NOT NULL,
                    claim_token VARCHAR(64) NULL,
                    claim_until DATETIME(3) NULL,
                    last_error VARCHAR(512) NULL,
                    published_at DATETIME(3) NULL,
                    created_at DATETIME(3) NOT NULL,
                    updated_at DATETIME(3) NOT NULL,
                    PRIMARY KEY (comment_id),
                    UNIQUE KEY uk_comment_write_outbox_client (creator_id, client_request_id),
                    KEY idx_comment_write_outbox_ready (state, next_attempt_at, comment_id),
                    KEY idx_comment_write_outbox_claim (claim_token, state)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """);
    }
}
