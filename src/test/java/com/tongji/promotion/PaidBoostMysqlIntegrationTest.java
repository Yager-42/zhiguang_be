package com.tongji.promotion;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 付费加权持久化层 MySQL 集成测试：schema 契约、mapper 往返、bucket 聚合与状态迁移。
 * <p>风格对齐 {@link com.tongji.promotion.PromotionMysqlIntegrationTest}；无 Docker 时通过 {@code @EnabledIf} 跳过。</p>
 */
@SpringBootTest(classes = PaidBoostMysqlIntegrationTest.TestConfig.class)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:mysql://127.0.0.1:3306/zhiguang?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false",
        "spring.datasource.username=zhiguang",
        "spring.datasource.password=zhiguang123456",
        "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver"
})
@EnabledIf("mysqlReachable")
class PaidBoostMysqlIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void paidBoostTablesSupportCampaignLifecycle() {
        long campaignId = uniqueId();
        long deliveryId = uniqueId();
        Instant now = Instant.now();
        jdbcTemplate.update("""
                INSERT INTO promotion_boost_campaign (
                  id, creator_user_id, post_id, channel, bid_amount, boost_value, unit_price, budget_total, budget_consumed,
                  reserve_business_ref, status, start_at, end_at, closed_at, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                campaignId, 42L, 1001L, "HOME_RECOMMENDATION", 30L, 30L, 2L, 100L, 0L,
                "paid-boost:" + campaignId + ":reserve", "ACTIVE", now, now.plusSeconds(3600), null, now, now);
        jdbcTemplate.update("""
                INSERT INTO promotion_boost_delivery (
                  id, campaign_id, channel, post_id, viewer_user_id, delivery_bucket_start_at, delivery_count,
                  unit_price_snapshot, captured_amount, settle_business_ref, status, settled_at, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                deliveryId, campaignId, "HOME_RECOMMENDATION", 1001L, 77L, now, 1,
                2L, 0L, "paid-boost:" + campaignId + ":spend:" + now.toEpochMilli() + ":77", "PENDING", null, now, now);

        Integer campaigns = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM promotion_boost_campaign WHERE id = ?", Integer.class, campaignId);
        Integer deliveries = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM promotion_boost_delivery WHERE id = ?", Integer.class, deliveryId);
        assertThat(campaigns).isEqualTo(1);
        assertThat(deliveries).isEqualTo(1);
    }

    private static long uniqueId() {
        return 7_800_000_000L + (System.nanoTime() % 1_000_000L);
    }

    static boolean mysqlReachable() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", 3306), 500);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    @Configuration
    @ImportAutoConfiguration({
            DataSourceAutoConfiguration.class,
            DataSourceTransactionManagerAutoConfiguration.class,
            JdbcTemplateAutoConfiguration.class
    })
    static class TestConfig {
    }
}
