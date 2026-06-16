package com.tongji.knowpost.publish;

import com.tongji.knowpost.api.dto.PublishAcceptedResponse;
import com.tongji.knowpost.api.dto.PublishRequest;
import com.tongji.knowpost.api.dto.PublishStatusResponse;
import com.tongji.knowpost.mapper.KnowPostMapper;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class Chunk1ContractTest {

    private static final Path SCHEMA_PATH = Path.of("db/schema.sql");
    private static final Path PUBLISH_ATTEMPT_MAPPER_XML_PATH = Path.of("src/main/resources/mapper/PublishAttemptMapper.xml");

    @Test
    void chunk1ContractMatchesSchemaMapperAndDtoRequirements() throws Exception {
        String schema = read(SCHEMA_PATH);
        assertThat(schema).contains("CREATE TABLE IF NOT EXISTS publish_attempt");
        assertThat(schema).contains("attempt_id BIGINT UNSIGNED NOT NULL");
        assertThat(schema).contains("post_id BIGINT UNSIGNED NOT NULL");
        assertThat(schema).contains("creator_id BIGINT UNSIGNED NOT NULL");
        assertThat(schema).contains("idempotent_key VARCHAR(128) NOT NULL");
        assertThat(schema).contains("status VARCHAR(32) NOT NULL");
        assertThat(schema).contains("failed_step VARCHAR(64) NULL");
        assertThat(schema).contains("error_message VARCHAR(1024) NULL");
        assertThat(schema).contains("retry_count INT NOT NULL DEFAULT 0");
        assertThat(schema).contains("created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP");
        assertThat(schema).contains("updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP");
        assertThat(schema).contains("UNIQUE KEY uk_publish_attempt_creator_post_key (creator_id, post_id, idempotent_key)");
        assertThat(schema).contains("publish_attempt_id BIGINT UNSIGNED NULL");
        assertThat(schema).contains("publish_failed_reason VARCHAR(512) NULL");

        String mapperXml = read(PUBLISH_ATTEMPT_MAPPER_XML_PATH);
        assertThat(mapperXml).contains("namespace=\"com.tongji.knowpost.publish.PublishAttemptMapper\"");
        assertThat(mapperXml).contains("<insert id=\"insert\"");
        assertThat(mapperXml).contains("<select id=\"findByIdempotencyKey\"");
        assertThat(mapperXml).contains("<select id=\"findById\"");
        assertThat(mapperXml).contains("<update id=\"markSucceeded\"");
        assertThat(mapperXml).contains("<update id=\"markFailed\"");
        assertThat(mapperXml).contains("<select id=\"findStatusById\"");

        assertThat(recordComponentNames(PublishAcceptedResponse.class))
                .containsExactly("publishAttemptId");
        assertThat(recordComponentNames(PublishStatusResponse.class))
                .containsExactly("publishAttemptId", "attemptStatus", "postStatus", "failedStep", "retryable");

        RecordComponent idempotentKey = PublishRequest.class.getRecordComponents()[0];
        assertThat(idempotentKey.getName()).isEqualTo("idempotentKey");
        assertThat(idempotentKey.getAccessor().getAnnotation(NotBlank.class)).isNotNull();

        Set<String> knowPostMapperMethods = Arrays.stream(KnowPostMapper.class.getDeclaredMethods())
                .map(Method::getName)
                .collect(Collectors.toSet());
        assertThat(knowPostMapperMethods).doesNotContain("updatePublishState");
    }

    private static String read(Path path) throws IOException {
        return Files.readString(path);
    }

    private static String[] recordComponentNames(Class<?> recordClass) {
        return Arrays.stream(recordClass.getRecordComponents())
                .map(RecordComponent::getName)
                .toArray(String[]::new);
    }
}
