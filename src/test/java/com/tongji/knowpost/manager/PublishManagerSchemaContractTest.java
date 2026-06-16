package com.tongji.knowpost.manager;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class PublishManagerSchemaContractTest {

    private static final Path SCHEMA_PATH = Path.of("db/schema.sql");
    private static final Path MAPPER_PATH = Path.of("src/main/resources/mapper/PublishAttemptMapper.xml");

    @Test
    void publishAttemptSchemaIncludesFallbackColumnsUsedByMapper() throws IOException {
        String schema = Files.readString(SCHEMA_PATH);
        String mapper = Files.readString(MAPPER_PATH);

        assertThat(mapper).contains("<update id=\"updateDerivedFailureFallback\">");
        assertThat(mapper).contains("fallback_task_type");
        assertThat(mapper).contains("fallback_target_type");
        assertThat(mapper).contains("fallback_target_id");
        assertThat(mapper).contains("fallback_failure_reason");
        assertThat(mapper).contains("fallback_next_retry_at");

        assertThat(schema).contains("fallback_task_type VARCHAR(64) NULL");
        assertThat(schema).contains("fallback_target_type VARCHAR(64) NULL");
        assertThat(schema).contains("fallback_target_id BIGINT UNSIGNED NULL");
        assertThat(schema).contains("fallback_failure_reason VARCHAR(1024) NULL");
        assertThat(schema).contains("fallback_next_retry_at TIMESTAMP NULL");
    }
}
