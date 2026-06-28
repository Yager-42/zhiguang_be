package com.tongji.moderation;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ModerationSchemaContractTest {

    @Test
    void schemaContainsModerationReportReviewContract() throws Exception {
        String schema = Files.readString(Path.of("db/schema.sql"));

        assertThat(schema).contains("CREATE TABLE IF NOT EXISTS moderation_reports");
        assertThat(schema).contains("id BIGINT UNSIGNED NOT NULL");
        assertThat(schema).contains("reporter_user_id BIGINT UNSIGNED NOT NULL");
        assertThat(schema).contains("target_type VARCHAR(16) NOT NULL");
        assertThat(schema).contains("target_id BIGINT UNSIGNED NOT NULL");
        assertThat(schema).contains("target_owner_user_id BIGINT UNSIGNED NOT NULL");
        assertThat(schema).contains("status VARCHAR(16) NOT NULL DEFAULT 'pending'");
        assertThat(schema).contains("llm_provider VARCHAR(32) NULL");
        assertThat(schema).contains("llm_model VARCHAR(128) NULL");
        assertThat(schema).contains("llm_decision VARCHAR(16) NULL");
        assertThat(schema).contains("llm_confidence DECIMAL(5,4) NULL");
        assertThat(schema).contains("failure_code VARCHAR(64) NULL");
        assertThat(schema).contains("failure_reason VARCHAR(512) NULL");
        assertThat(schema).contains("retry_count INT NOT NULL DEFAULT 0");
        assertThat(schema).contains("next_retry_at DATETIME(3) NULL");
        assertThat(schema).contains("content_action_status VARCHAR(16) NULL");
        assertThat(schema).contains("notification_failure VARCHAR(512) NULL");
        assertThat(schema).contains("UNIQUE KEY uk_moderation_report_reporter_target (reporter_user_id, target_type, target_id)");
        assertThat(schema).contains("KEY idx_moderation_report_status_created (status, created_at, id)");
        assertThat(schema).contains("KEY idx_moderation_report_retry (status, next_retry_at, id)");
        assertThat(schema).contains("KEY idx_moderation_report_target (target_type, target_id, created_at)");
    }
}
