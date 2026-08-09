package com.tongji.promotion.bprime.config;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Upgrades existing promotion command tables with immutable decision metadata required by the MySQL-free consumer.
 *
 * @since 2026-08-08
 */
@Component
@ConditionalOnProperty(name = "promotion.bprime.enabled", havingValue = "true")
public class PromotionBPrimeSchemaInitializer {

    private static final String TABLE_NAME = "promotion_auction_command";

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
        ensureColumn("reserve_price", "BIGINT NULL");
        ensureColumn("window_status", "VARCHAR(16) NULL");
        jdbcTemplate.update("""
                UPDATE promotion_auction_command command_record
                LEFT JOIN promotion_auction_window auction_window
                  ON auction_window.id = command_record.auction_window_id
                SET command_record.reserve_price = COALESCE(command_record.reserve_price,
                                                             auction_window.reserve_price, 1),
                    command_record.window_status = COALESCE(command_record.window_status,
                                                             auction_window.status, 'OPEN')
                WHERE command_record.reserve_price IS NULL OR command_record.window_status IS NULL
                """);
        ensureNotNull("reserve_price", "BIGINT");
        ensureNotNull("window_status", "VARCHAR(16)");
    }

    private void ensureColumn(String columnName, String definition) {
        if (columnExists(columnName)) {
            return;
        }
        try {
            jdbcTemplate.execute("ALTER TABLE " + TABLE_NAME + " ADD COLUMN " + columnName + " " + definition);
        } catch (DataAccessException exception) {
            if (!columnExists(columnName)) {
                throw exception;
            }
        }
    }

    private boolean columnExists(String columnName) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.columns
                WHERE table_schema = DATABASE()
                  AND table_name = ?
                  AND column_name = ?
                """, Integer.class, TABLE_NAME, columnName);
        return count != null && count > 0;
    }

    private void ensureNotNull(String columnName, String dataType) {
        String nullable = jdbcTemplate.queryForObject("""
                SELECT is_nullable
                FROM information_schema.columns
                WHERE table_schema = DATABASE()
                  AND table_name = ?
                  AND column_name = ?
                """, String.class, TABLE_NAME, columnName);
        if ("YES".equals(nullable)) {
            jdbcTemplate.execute("ALTER TABLE " + TABLE_NAME + " MODIFY " + columnName
                    + " " + dataType + " NOT NULL");
        }
    }
}
