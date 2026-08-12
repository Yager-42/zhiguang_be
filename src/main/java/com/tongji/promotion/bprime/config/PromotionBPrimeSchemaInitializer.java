package com.tongji.promotion.bprime.config;

import jakarta.annotation.PostConstruct;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 为推广竞价热链路补齐兼容旧环境的数据库列。
 *
 * @since 2026-08-08
 */
@Component
public class PromotionBPrimeSchemaInitializer {

    private static final String CHECKPOINT_TABLE = "promotion_projection_checkpoint";

    private final JdbcTemplate jdbcTemplate;

    public PromotionBPrimeSchemaInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void initialize() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS promotion_bid_escrow (
                    id BIGINT UNSIGNED NOT NULL,
                    auction_window_id BIGINT UNSIGNED NOT NULL,
                    campaign_id BIGINT UNSIGNED NOT NULL,
                    bidder_user_id BIGINT UNSIGNED NOT NULL,
                    authorized_amount BIGINT NOT NULL,
                    current_hold BIGINT NOT NULL DEFAULT 0,
                    status VARCHAR(16) NOT NULL,
                    expires_at DATETIME(3) NOT NULL,
                    created_at DATETIME(3) NOT NULL,
                    updated_at DATETIME(3) NOT NULL,
                    PRIMARY KEY (id),
                    UNIQUE KEY uk_promotion_escrow_window_campaign (auction_window_id, campaign_id),
                    KEY idx_promotion_escrow_window_status (auction_window_id, status, campaign_id),
                    KEY idx_promotion_escrow_bidder_status (bidder_user_id, status, expires_at),
                    CONSTRAINT chk_promotion_escrow_amount CHECK (
                        authorized_amount > 0 AND current_hold >= 0 AND current_hold <= authorized_amount
                    )
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """);
        ensureColumn(CHECKPOINT_TABLE, "last_stream_id", "VARCHAR(32) NULL");
        jdbcTemplate.update("""
                UPDATE promotion_projection_checkpoint
                SET last_stream_id = CONCAT(last_decision_version, '-0')
                WHERE last_stream_id IS NULL
                """);
        ensureNotNull(CHECKPOINT_TABLE, "last_stream_id", "VARCHAR(32)");
    }

    private void ensureColumn(String tableName, String columnName, String definition) {
        if (columnExists(tableName, columnName)) {
            return;
        }
        try {
            jdbcTemplate.execute("ALTER TABLE " + tableName + " ADD COLUMN " + columnName + " " + definition);
        } catch (DataAccessException exception) {
            if (!columnExists(tableName, columnName)) {
                throw exception;
            }
        }
    }

    private boolean columnExists(String tableName, String columnName) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.columns
                WHERE table_schema = DATABASE()
                  AND table_name = ?
                  AND column_name = ?
                """, Integer.class, tableName, columnName);
        return count != null && count > 0;
    }

    private void ensureNotNull(String tableName, String columnName, String dataType) {
        String nullable = jdbcTemplate.queryForObject("""
                SELECT is_nullable
                FROM information_schema.columns
                WHERE table_schema = DATABASE()
                  AND table_name = ?
                  AND column_name = ?
                """, String.class, tableName, columnName);
        if ("YES".equals(nullable)) {
            jdbcTemplate.execute("ALTER TABLE " + tableName + " MODIFY " + columnName
                    + " " + dataType + " NOT NULL");
        }
    }
}
