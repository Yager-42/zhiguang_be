package com.tongji.comment.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class CommentOutboxSchemaContractTest {

    @Test
    void schemaDefinesUnifiedOutboxWithoutLegacyMigration() throws Exception {
        String schema = Files.readString(Path.of("db/schema.sql"));
        String mapper = Files.readString(Path.of("src/main/resources/mapper/CommentOutboxMapper.xml"));
        String initializer = Files.readString(Path.of(
                "src/main/java/com/tongji/comment/config/CommentOutboxSchemaInitializer.java"));
        String application = Files.readString(Path.of("src/main/resources/application.yml"));

        assertThat(schema).contains("CREATE TABLE IF NOT EXISTS comment_outbox")
                .contains("UNIQUE KEY uk_comment_event (event_type, aggregate_id)")
                .contains("KEY idx_pending_comment_status_created (status, create_time)")
                .contains("KEY idx_comment_outbox_published (state, published_at, event_id)")
                .doesNotContain("CREATE TABLE IF NOT EXISTS comment_write_outbox");
        assertThat(initializer)
                .contains("KEY idx_comment_outbox_published (state, published_at, event_id)")
                .contains("ADD INDEX idx_comment_outbox_published (state, published_at, event_id)");
        assertThat(application).contains("clean-interval-ms: ${COMMENT_OUTBOX_CLEAN_INTERVAL_MS:86400000}");
        assertThat(mapper).contains("UPDATE comment_outbox")
                .doesNotContainIgnoringCase("INSERT INTO comment_outbox SELECT")
                .doesNotContainIgnoringCase("backfill");
    }
}
