package com.tongji.promotion;

import com.tongji.promotion.mapper.PromotionAuctionWindowMapper;
import com.tongji.promotion.mapper.PromotionBidMapper;
import com.tongji.promotion.mapper.PromotionCampaignMapper;
import com.tongji.promotion.mapper.PromotionSlotAllocationMapper;
import com.tongji.promotion.model.PromotionAuctionWindow;
import com.tongji.promotion.model.PromotionAuctionWindowStatus;
import com.tongji.promotion.model.PromotionBid;
import com.tongji.promotion.model.PromotionBidStatus;
import com.tongji.promotion.model.PromotionCampaign;
import com.tongji.promotion.model.PromotionCampaignStatus;
import com.tongji.promotion.model.PromotionResourceType;
import com.tongji.promotion.model.PromotionSlotAllocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.mybatis.spring.annotation.MapperScan;
import org.mybatis.spring.boot.autoconfigure.MybatisAutoConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.TestPropertySource;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 推广位竞价持久化层 MySQL 集成测试：window/bid/allocation 的 enum 往返、时间过滤查询、状态迁移。
 * <p>风格对齐 {@link com.tongji.wallet.WalletMysqlIntegrationTest}；无 Docker 时通过 {@code @EnabledIf} 跳过。</p>
 */
@SpringBootTest(classes = PromotionMysqlIntegrationTest.TestConfig.class)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:mysql://127.0.0.1:3306/zhiguang?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false",
        "spring.datasource.username=zhiguang",
        "spring.datasource.password=zhiguang123456",
        "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
        "mybatis.mapper-locations=classpath*:mapper/**/*.xml",
        "mybatis.configuration.map-underscore-to-camel-case=true"
})
@EnabledIf("mysqlReachable")
class PromotionMysqlIntegrationTest {

    @Autowired
    private PromotionAuctionWindowMapper windowMapper;

    @Autowired
    private PromotionBidMapper bidMapper;

    @Autowired
    private PromotionSlotAllocationMapper allocationMapper;

    @Autowired
    private PromotionCampaignMapper campaignMapper;

    @Test
    void windowEnumRoundTripsAndOpenWindowLookup() {
        long id = uniqueId();
        // 秒对齐：findExactWindow 是精确时间相等查询，纳秒精度会被 DATETIME(3) 毫秒截断/舍入破坏
        Instant start = uniqueOpenWindowStart();
        Instant end = start.plusSeconds(3600);
        insertWindow(id, PromotionResourceType.SEARCH_TOP_SLOT, start, end,
                1, 10L, PromotionAuctionWindowStatus.OPEN);

        PromotionAuctionWindow open = windowMapper.findOpenWindow(PromotionResourceType.SEARCH_TOP_SLOT, Instant.now());
        assertThat(open).isNotNull();
        assertThat(open.getStatus()).isEqualTo(PromotionAuctionWindowStatus.OPEN);
        assertThat(open.getResourceType()).isEqualTo(PromotionResourceType.SEARCH_TOP_SLOT);

        PromotionAuctionWindow exact = windowMapper.findExactWindow(PromotionResourceType.SEARCH_TOP_SLOT, start, end);
        assertThat(exact).isNotNull();
        assertThat(exact.getId()).isEqualTo(id);
    }

    @Test
    void listClosableWindowsAndMarkSettled() {
        long id = uniqueId();
        Instant start = uniqueClosableWindowStart();
        Instant end = start.plusSeconds(3600);
        insertWindow(id, PromotionResourceType.FEED_TOP_SLOT, start, end,
                1, 10L, PromotionAuctionWindowStatus.OPEN);

        List<PromotionAuctionWindow> closable = windowMapper.listClosableWindows(Instant.now(), 50);
        assertThat(closable).extracting(PromotionAuctionWindow::getId).contains(id);

        assertThat(windowMapper.markSettled(id, Instant.now())).isEqualTo(1);
        // 结算后 status=SETTLED，不再被列为可关闭
        assertThat(windowMapper.findExactWindow(PromotionResourceType.FEED_TOP_SLOT, start, end).getStatus())
                .isEqualTo(PromotionAuctionWindowStatus.SETTLED);
    }

    @Test
    void bidLifecycleAndActiveListing() {
        long campaignId = uniqueId();
        long windowId = uniqueId();
        long bidId = uniqueId();
        Instant windowStartAt = uniqueOpenWindowStart();
        Instant windowEndAt = windowStartAt.plusSeconds(3600);
        long windowSpanSeconds = windowEndAt.getEpochSecond() - windowStartAt.getEpochSecond();
        Instant allocationStartAt = windowEndAt;
        Instant allocationEndAt = allocationStartAt.plusSeconds(windowSpanSeconds);
        insertCampaign(campaignId, PromotionResourceType.FEED_TOP_SLOT,
                allocationStartAt, allocationEndAt,
                PromotionCampaignStatus.ACTIVE);
        insertWindow(windowId, PromotionResourceType.FEED_TOP_SLOT, windowStartAt, windowEndAt,
                2, 10L, PromotionAuctionWindowStatus.OPEN);
        insertBid(bidId, campaignId, windowId, 42L, 120L, PromotionBidStatus.ACTIVE);

        assertThat(bidMapper.findByCampaignIdAndAuctionWindowId(campaignId, windowId)).isNotNull();
        // listActiveBidsByWindowId JOIN campaign 回填 postId
        List<PromotionBid> active = bidMapper.listActiveBidsByWindowId(windowId, allocationStartAt, allocationEndAt);
        assertThat(active).extracting(PromotionBid::getId).contains(bidId);
        assertThat(active.getFirst().getPostId()).isEqualTo(5000L + campaignId);

        assertThat(bidMapper.markWon(bidId, 0, 100L)).isEqualTo(1);
        assertThat(bidMapper.findByCampaignIdAndAuctionWindowId(campaignId, windowId).getStatus())
                .isEqualTo(PromotionBidStatus.WON);
        // WON 后不再属于 ACTIVE 列表
        assertThat(bidMapper.listActiveBidsByWindowId(windowId, allocationStartAt, allocationEndAt).stream()
                .map(PromotionBid::getId))
                .doesNotContain(bidId);
        assertThat(bidMapper.listSettledBidsByWindowId(windowId, allocationStartAt, allocationEndAt).stream()
                .map(PromotionBid::getId))
                .contains(bidId);

        long loserId = uniqueId();
        long loserCampaignId = uniqueId();
        insertCampaign(loserCampaignId, PromotionResourceType.FEED_TOP_SLOT,
                allocationStartAt, allocationEndAt,
                PromotionCampaignStatus.ACTIVE);
        insertBid(loserId, loserCampaignId, windowId, 43L, 70L, PromotionBidStatus.ACTIVE);
        assertThat(bidMapper.markLost(loserId)).isEqualTo(1);
        assertThat(bidMapper.findByCampaignIdAndAuctionWindowId(loserCampaignId, windowId).getStatus())
                .isEqualTo(PromotionBidStatus.LOST);
        assertThat(bidMapper.listSettledBidsByWindowId(windowId, allocationStartAt, allocationEndAt).stream()
                .map(PromotionBid::getId))
                .contains(bidId, loserId);
    }

    @Test
    void listActiveBidsExcludesCampaignOutsideAllocationWindow() {
        long windowId = uniqueId();
        Instant windowStartAt = uniqueOpenWindowStart();
        Instant windowEndAt = windowStartAt.plusSeconds(3600);
        long windowSpanSeconds = windowEndAt.getEpochSecond() - windowStartAt.getEpochSecond();
        Instant allocationStartAt = windowEndAt;
        Instant allocationEndAt = allocationStartAt.plusSeconds(windowSpanSeconds);
        insertWindow(windowId, PromotionResourceType.FEED_TOP_SLOT, windowStartAt, windowEndAt,
                2, 10L, PromotionAuctionWindowStatus.OPEN);

        long eligibleCampaignId = uniqueId();
        insertCampaign(eligibleCampaignId, PromotionResourceType.FEED_TOP_SLOT,
                allocationStartAt, allocationEndAt,
                PromotionCampaignStatus.ACTIVE);
        long eligibleBidId = uniqueId();
        insertBid(eligibleBidId, eligibleCampaignId, windowId, 42L, 120L, PromotionBidStatus.ACTIVE);

        long endedEarlyCampaignId = uniqueId();
        insertCampaign(endedEarlyCampaignId, PromotionResourceType.FEED_TOP_SLOT,
                allocationStartAt.minusSeconds(60), allocationEndAt.minusSeconds(1),
                PromotionCampaignStatus.ACTIVE);
        long endedEarlyBidId = uniqueId();
        insertBid(endedEarlyBidId, endedEarlyCampaignId, windowId, 43L, 110L, PromotionBidStatus.ACTIVE);

        long startsLateCampaignId = uniqueId();
        insertCampaign(startsLateCampaignId, PromotionResourceType.FEED_TOP_SLOT,
                allocationStartAt.plusSeconds(1), allocationEndAt.plusSeconds(60),
                PromotionCampaignStatus.ACTIVE);
        long startsLateBidId = uniqueId();
        insertBid(startsLateBidId, startsLateCampaignId, windowId, 44L, 100L, PromotionBidStatus.ACTIVE);

        assertThat(bidMapper.listActiveBidsByWindowId(windowId, allocationStartAt, allocationEndAt))
                .extracting(PromotionBid::getId)
                .contains(eligibleBidId)
                .doesNotContain(endedEarlyBidId, startsLateBidId);
        assertThat(bidMapper.listActiveBidsByWindowId(windowId, allocationStartAt, allocationEndAt))
                .extracting(PromotionBid::getCampaignId)
                .contains(eligibleCampaignId)
                .doesNotContain(endedEarlyCampaignId, startsLateCampaignId);
    }

    @Test
    void allocationListActiveTimeFilter() {
        long windowId = uniqueId();
        Instant now = Instant.now();
        // 有效 allocation：[now-1m, now+1h)
        PromotionSlotAllocation active = PromotionSlotAllocation.builder()
                .id(uniqueId()).auctionWindowId(windowId).resourceType(PromotionResourceType.FEED_TOP_SLOT)
                .slotIndex((int) (windowId % 1000)).campaignId(uniqueId()).postId(uniqueId())
                .bidderUserId(42L).clearingPrice(80L)
                .allocationStartAt(now.minusSeconds(60)).allocationEndAt(now.plusSeconds(3600))
                .createdAt(now).build();
        assertThat(allocationMapper.insert(active)).isEqualTo(1);
        // 过期 allocation：[now-2h, now-1h)
        PromotionSlotAllocation expired = PromotionSlotAllocation.builder()
                .id(uniqueId()).auctionWindowId(windowId).resourceType(PromotionResourceType.FEED_TOP_SLOT)
                .slotIndex((int) (windowId % 1000) + 1).campaignId(uniqueId()).postId(uniqueId())
                .bidderUserId(43L).clearingPrice(70L)
                .allocationStartAt(now.minusSeconds(7200)).allocationEndAt(now.minusSeconds(3600))
                .createdAt(now).build();
        assertThat(allocationMapper.insert(expired)).isEqualTo(1);

        List<PromotionSlotAllocation> result = allocationMapper.listActive(PromotionResourceType.FEED_TOP_SLOT, now);
        assertThat(result).extracting(PromotionSlotAllocation::getId).contains(active.getId());
        assertThat(result).extracting(PromotionSlotAllocation::getId).doesNotContain(expired.getId());
    }

    private void insertWindow(long id, PromotionResourceType type, Instant start, Instant end,
                              int slotCount, long reserve, PromotionAuctionWindowStatus status) {
        windowMapper.insert(PromotionAuctionWindow.builder()
                .id(id).resourceType(type).windowStartAt(start).windowEndAt(end)
                .slotCount(slotCount).reservePrice(reserve).status(status)
                .settledAt(null).createdAt(start).updatedAt(start).build());
    }

    private void insertCampaign(long id, PromotionResourceType type) {
        Instant now = Instant.now();
        insertCampaign(id, type, now.minusSeconds(3600), now.plusSeconds(3600), PromotionCampaignStatus.ACTIVE);
    }

    private void insertCampaign(long id, PromotionResourceType type, Instant startAt, Instant endAt,
                                PromotionCampaignStatus status) {
        Instant now = Instant.now();
        campaignMapper.insert(PromotionCampaign.builder()
                .id(id).creatorUserId(42L).postId(5000L + id).resourceType(type)
                .status(status)
                .startAt(startAt).endAt(endAt)
                .createdAt(now).updatedAt(now).build());
    }

    private void insertBid(long id, long campaignId, long windowId, long bidder, long amount, PromotionBidStatus status) {
        Instant now = Instant.now();
        bidMapper.insert(PromotionBid.builder()
                .id(id).campaignId(campaignId).auctionWindowId(windowId).bidderUserId(bidder)
                .bidAmount(amount).walletBusinessRef("it-bid:" + id).status(status)
                .createdAt(now).updatedAt(now).build());
    }

    private static long uniqueId() {
        return 7_700_000_000L + (System.nanoTime() % 1_000_000L);
    }

    private static Instant uniqueOpenWindowStart() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        long offsetSeconds = uniqueId() % 1_800L;
        return now.minusSeconds(1_800L + offsetSeconds);
    }

    private static Instant uniqueClosableWindowStart() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        long offsetSeconds = uniqueId() % 1_800L;
        return now.minusSeconds(7_200L + offsetSeconds);
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
            MybatisAutoConfiguration.class
    })
    @MapperScan(basePackageClasses = PromotionCampaignMapper.class)
    static class TestConfig {
    }
}
