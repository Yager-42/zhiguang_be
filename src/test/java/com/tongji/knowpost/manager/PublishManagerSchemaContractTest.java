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
    void publishAttemptSchemaMatchesVersionedSnapshotMapper() throws IOException {
        String schema = Files.readString(SCHEMA_PATH);
        String mapper = Files.readString(MAPPER_PATH);

        assertThat(schema).contains("run_version INT NOT NULL DEFAULT 1");
        assertThat(schema).contains("content_object_key_snapshot VARCHAR(512) NULL");
        assertThat(schema).contains("content_etag_snapshot VARCHAR(128) NULL");
        assertThat(schema).contains("content_sha256_snapshot CHAR(64) NULL");
        assertThat(schema).contains("event_key VARCHAR(191) NULL");
        assertThat(schema).contains("UNIQUE KEY uk_outbox_event_key (event_key)");

        assertThat(mapper).contains("property=\"runVersion\" column=\"run_version\"");
        assertThat(mapper).contains("property=\"contentObjectKeySnapshot\" column=\"content_object_key_snapshot\"");
        assertThat(mapper).contains("AND run_version = #{runVersion}");
        assertThat(mapper).contains("run_version = run_version + 1");
        assertThat(mapper).doesNotContain("fallback_task_type");
    }
}
