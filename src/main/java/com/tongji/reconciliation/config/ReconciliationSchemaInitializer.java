package com.tongji.reconciliation.config;

import jakarta.annotation.PostConstruct;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class ReconciliationSchemaInitializer {
    private static final String TABLE_NAME = "reconciliation_task";
    private static final String DEDUPE_STATUS_INDEX_NAME = "idx_reconciliation_task_dedupe_status";
    private static final String DEDUPE_STATUS_INDEX_COLUMNS = "dedupe_scope,status";

    private final JdbcTemplate jdbcTemplate;

    public ReconciliationSchemaInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void initialize() {
        String columns = dedupeStatusIndexColumns();
        if (columns == null) {
            try {
                jdbcTemplate.execute("""
                        ALTER TABLE reconciliation_task
                        ADD INDEX idx_reconciliation_task_dedupe_status (dedupe_scope, status)
                        """);
                return;
            } catch (DataAccessException exception) {
                columns = dedupeStatusIndexColumns();
                if (columns == null) {
                    throw exception;
                }
            }
        }
        if (!DEDUPE_STATUS_INDEX_COLUMNS.equals(columns)) {
            throw new IllegalStateException(
                    "Unexpected columns for index " + DEDUPE_STATUS_INDEX_NAME + ": " + columns);
        }
    }

    private String dedupeStatusIndexColumns() {
        return jdbcTemplate.queryForObject("""
                SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',')
                FROM information_schema.statistics
                WHERE table_schema = DATABASE()
                  AND table_name = ?
                  AND index_name = ?
                """, String.class, TABLE_NAME, DEDUPE_STATUS_INDEX_NAME);
    }
}
