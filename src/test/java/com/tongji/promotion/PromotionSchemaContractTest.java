package com.tongji.promotion;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 钉死 promotion 位竞价所需的表、唯一键与索引，防止 schema 漂移。
 * 读 db/schema.sql 文本做包含断言，不依赖数据库连接。
 */
class PromotionSchemaContractTest {

    @Test
    void schemaContainsPromotionTablesAndIndexes() throws Exception {
        String schema = Files.readString(Path.of("db/schema.sql"));
        String checkpointMapper = Files.readString(Path.of("src/main/resources/mapper/PromotionProjectionCheckpointMapper.xml"));

        assertThat(schema).contains("CREATE TABLE IF NOT EXISTS promotion_campaign");
        assertThat(schema).contains("CREATE TABLE IF NOT EXISTS promotion_auction_window");
        assertThat(schema).contains("CREATE TABLE IF NOT EXISTS promotion_bid");
        assertThat(schema).contains("CREATE TABLE IF NOT EXISTS promotion_auction_command");
        assertThat(schema).doesNotContain("CREATE TABLE IF NOT EXISTS promotion_auction_decision");
        assertThat(schema).contains("CREATE TABLE IF NOT EXISTS promotion_projection_checkpoint");
        assertThat(schema).contains("last_decision_version BIGINT NOT NULL");
        assertThat(schema).contains("last_kafka_topic VARCHAR(128) NULL");
        assertThat(schema).contains("last_kafka_partition INT NULL");
        assertThat(schema).contains("last_kafka_offset BIGINT NULL");
        assertThat(checkpointMapper).contains("SELECT last_decision_version");
        assertThat(checkpointMapper).contains("SELECT last_decision_id");
        assertThat(checkpointMapper).contains("last_decision_version = VALUES(last_decision_version)");
        assertThat(checkpointMapper).contains("COALESCE(VALUES(last_kafka_topic), last_kafka_topic)");
        assertThat(checkpointMapper).contains("COALESCE(VALUES(last_kafka_partition), last_kafka_partition)");
        assertThat(checkpointMapper).contains("COALESCE(VALUES(last_kafka_offset), last_kafka_offset)");
        assertThat(schema).contains("CREATE TABLE IF NOT EXISTS promotion_slot_allocation");
        assertThat(schema).contains("uk_promotion_window_resource_time");
        assertThat(schema).contains("uk_promotion_command_id");
        assertThat(schema).contains("uk_promotion_command_idempotency");
        assertThat(schema).contains("idx_promotion_bid_window_status_amount");
        assertThat(schema).contains("idx_promotion_slot_resource_time");
    }
}
