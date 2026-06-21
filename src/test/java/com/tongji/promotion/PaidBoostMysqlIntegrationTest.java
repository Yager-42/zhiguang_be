package com.tongji.promotion;

import com.tongji.promotion.mapper.PaidBoostCampaignMapper;
import com.tongji.promotion.mapper.PaidBoostDeliveryMapper;
import com.tongji.promotion.model.PaidBoostCampaign;
import com.tongji.promotion.model.PaidBoostCampaignStatus;
import com.tongji.promotion.model.PaidBoostChannel;
import com.tongji.promotion.model.PaidBoostDelivery;
import com.tongji.promotion.model.PaidBoostDeliveryStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.mybatis.spring.annotation.MapperScan;
import org.mybatis.spring.boot.autoconfigure.MybatisAutoConfiguration;
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
import java.util.List;

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
        "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
        "mybatis.mapper-locations=classpath*:mapper/**/*.xml",
        "mybatis.configuration.map-underscore-to-camel-case=true"
})
@EnabledIf("mysqlReachable")
class PaidBoostMysqlIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PaidBoostCampaignMapper campaignMapper;

    @Autowired
    private PaidBoostDeliveryMapper deliveryMapper;

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

    @Test
    void campaignMapperFindsActiveCampaignsByChannel() {
        long id = uniqueId();
        Instant start = Instant.now().minusSeconds(60);
        Instant end = Instant.now().plusSeconds(3600);
        campaignMapper.insert(PaidBoostCampaign.builder()
                .id(id).creatorUserId(42L).postId(1001L)
                .channel(PaidBoostChannel.HOME_RECOMMENDATION)
                .bidAmount(30L).boostValue(30L).unitPrice(2L).budgetTotal(100L).budgetConsumed(0L)
                .reserveBusinessRef("paid-boost:" + id + ":reserve")
                .status(PaidBoostCampaignStatus.ACTIVE)
                .startAt(start).endAt(end)
                .createdAt(start).updatedAt(start)
                .build());

        List<PaidBoostCampaign> active = campaignMapper.listActiveByChannel(
                PaidBoostChannel.HOME_RECOMMENDATION, Instant.now());
        assertThat(active).extracting(PaidBoostCampaign::getId).contains(id);

        assertThat(campaignMapper.increaseBudgetConsumed(id, 2L, Instant.now())).isEqualTo(1);
        assertThat(campaignMapper.findById(id).getBudgetConsumed()).isEqualTo(2L);
    }

    @Test
    void upsertAggregatesSameBucketViewerInsteadOfNewBillableFact() {
        long campaignId = uniqueId();
        Instant bucketStart = Instant.now();
        Instant now = bucketStart;
        PaidBoostDelivery delivery = PaidBoostDelivery.builder()
                .id(uniqueId()).campaignId(campaignId).channel(PaidBoostChannel.HOME_RECOMMENDATION)
                .postId(2001L).viewerUserId(77L).deliveryBucketStartAt(bucketStart).deliveryCount(1)
                .unitPriceSnapshot(2L).capturedAmount(0L)
                .settleBusinessRef("paid-boost:" + campaignId + ":spend:" + bucketStart.toEpochMilli() + ":77")
                .status(PaidBoostDeliveryStatus.PENDING).settledAt(null).createdAt(now).updatedAt(now)
                .build();

        deliveryMapper.upsertPending(delivery);
        // 同 (campaign, bucket, viewer) 第二次送达：累加 delivery_count，不新增第二条
        deliveryMapper.upsertPending(delivery);

        Integer rows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM promotion_boost_delivery WHERE campaign_id = ?", Integer.class, campaignId);
        Integer count = jdbcTemplate.queryForObject(
                "SELECT delivery_count FROM promotion_boost_delivery WHERE campaign_id = ?", Integer.class, campaignId);
        assertThat(rows).isEqualTo(1);
        assertThat(count).isEqualTo(2);

        assertThat(deliveryMapper.markSettledWithAmount(delivery.getId(), 4L, Instant.now())).isEqualTo(1);
        assertThat(deliveryMapper.listPendingBefore(Instant.now().plusSeconds(60), 100))
                .extracting(PaidBoostDelivery::getCampaignId)
                .doesNotContain(campaignId);
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
            JdbcTemplateAutoConfiguration.class,
            MybatisAutoConfiguration.class
    })
    @MapperScan(basePackageClasses = PaidBoostCampaignMapper.class)
    static class TestConfig {
    }
}
