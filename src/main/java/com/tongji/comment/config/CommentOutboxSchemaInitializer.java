package com.tongji.comment.config;

import jakarta.annotation.PostConstruct;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class CommentOutboxSchemaInitializer {
    private static final String TABLE_NAME = "comment_outbox";
    private static final String PUBLISHED_INDEX_NAME = "idx_comment_outbox_published";
    private static final String PUBLISHED_INDEX_COLUMNS = "state,published_at,event_id";

    private final JdbcTemplate jdbcTemplate;

    public CommentOutboxSchemaInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void initialize() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS comment_outbox (
                    event_id BIGINT UNSIGNED NOT NULL,
                    event_type VARCHAR(40) NOT NULL,
                    aggregate_id BIGINT UNSIGNED NOT NULL,
                    payload JSON NOT NULL,
                    state TINYINT NOT NULL DEFAULT 0,
                    retry_count INT NOT NULL DEFAULT 0,
                    next_attempt_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                    claim_token VARCHAR(64) NULL,
                    claimed_until DATETIME(3) NULL,
                    last_error VARCHAR(500) NULL,
                    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                    published_at DATETIME(3) NULL,
                    PRIMARY KEY (event_id),
                    UNIQUE KEY uk_comment_event (event_type, aggregate_id),
                    KEY idx_comment_outbox_ready (state, next_attempt_at, event_id),
                    KEY idx_comment_outbox_claim (claim_token, state),
                    KEY idx_comment_outbox_published (state, published_at, event_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """);
        ensurePublishedIndex();
    }

    private void ensurePublishedIndex() {
        String columns = publishedIndexColumns();
        if (columns == null) {
            try {
                jdbcTemplate.execute("""
                        ALTER TABLE comment_outbox
                        ADD INDEX idx_comment_outbox_published (state, published_at, event_id)
                        """);
                return;
            } catch (DataAccessException exception) {
                columns = publishedIndexColumns();
                if (columns == null) {
                    throw exception;
                }
            }
        }
        if (!PUBLISHED_INDEX_COLUMNS.equals(columns)) {
            throw new IllegalStateException("Unexpected columns for index " + PUBLISHED_INDEX_NAME + ": " + columns);
        }
    }

    private String publishedIndexColumns() {
        return jdbcTemplate.queryForObject("""
                SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',')
                FROM information_schema.statistics
                WHERE table_schema = DATABASE()
                  AND table_name = ?
                  AND index_name = ?
                """, String.class, TABLE_NAME, PUBLISHED_INDEX_NAME);
    }
}
