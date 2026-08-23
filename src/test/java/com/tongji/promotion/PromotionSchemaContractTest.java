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
        assertThat(schema).contains("CREATE TABLE IF NOT EXISTS promotion_bid_escrow");
        assertThat(schema).contains("CREATE TABLE IF NOT EXISTS promotion_bid");
        assertThat(schema).doesNotContain("CREATE TABLE IF NOT EXISTS promotion_auction_command");
        assertThat(schema).doesNotContain("CREATE TABLE IF NOT EXISTS promotion_auction_decision");
        assertThat(schema).contains("CREATE TABLE IF NOT EXISTS promotion_projection_checkpoint");
        assertThat(schema).contains("last_decision_version BIGINT NOT NULL");
        assertThat(schema).contains("last_stream_id VARCHAR(32) NOT NULL");
        assertThat(schema).doesNotContain("decision_path");
        assertThat(schema).contains("reserve_price BIGINT NOT NULL");
        assertThat(checkpointMapper).contains("SELECT auction_window_id, last_decision_id, last_decision_version");
        assertThat(checkpointMapper).contains("last_decision_version = VALUES(last_decision_version)");
        assertThat(checkpointMapper).contains("last_stream_id = VALUES(last_stream_id)");
        assertThat(schema).contains("CREATE TABLE IF NOT EXISTS promotion_slot_allocation");
        assertThat(schema).contains("uk_promotion_campaign_participation");
        assertThat(schema).contains("uk_promotion_window_resource_time");
        assertThat(schema).contains("uk_promotion_bid_command");
        assertThat(schema).contains("idx_promotion_bid_window_status_amount");
        assertThat(schema).contains("uk_promotion_escrow_window_campaign");
        assertThat(schema).contains("chk_promotion_escrow_amount");
        assertThat(schema).contains("idx_promotion_slot_resource_time");
    }
}
