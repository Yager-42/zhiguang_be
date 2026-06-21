# Paid Boost Promotions Implementation Plan

> **给 agent 工作者：** REQUIRED SUB-SKILL: 使用 `superpowers:subagent-driven-development`（推荐）或 `superpowers:executing-plans` 按任务逐项执行本计划。步骤使用 checkbox（`- [ ]`）语法跟踪。

**Goal:** 在 zhiguang 内落地非拍卖 `paid boost` 推广：支持创作者为帖子创建推荐/关注两类 boost 活动，提交 `bidAmount` 后经 zhiguang 内本地 quote 适配层产出有效 boost 值，冻结预算、按实际消费结算、到期释放剩余预算，并在 home feed 推荐阶段与 follow feed 受限裁剪阶段接入 boost 权重与商业标记。

**Architecture:** 保持当前模块化单体，沿用已有 `com.tongji.promotion` 顶层 feature 包，但把 `paid boost` 与 `slot auction` 分成独立子域和独立表，不复用 `auction_window` / `bid` / `slot_allocation` 语义。写路径采用单接口创建活动，请求接收 `bidAmount` 而不是最终 boost 值；zhiguang 仓内新增 `PaidBoostQuoteService`，参考 `bytedance` 竞价域 `BidAmount` / `effectiveAmount` 语义做本地换算，不引入跨仓编译依赖。读路径不调用外部推荐引擎做商业排序，只在本地 mixing/follow 裁剪阶段叠加这个有效值；预算账务复用 `WalletService` 的冻结/释放/平台扣减能力，定时关闭活动并做最终结算。

**Tech Stack:** Java 21、Spring Boot 3.2.4、MyBatis、MySQL 8、Redis、Cassandra、Maven、JUnit 5、Mockito、AssertJ、OpenSpec。

---

## 必读上下文

编辑前先读：

- `openspec/changes/add-paid-boost-promotions/proposal.md`
- `openspec/changes/add-paid-boost-promotions/design.md`
- `openspec/changes/add-paid-boost-promotions/tasks.md`
- `openspec/changes/add-paid-boost-promotions/specs/paid-boost-promotions/spec.md`
- `openspec/changes/add-paid-boost-promotions/specs/recommendation-feed/spec.md`
- `docs/prd/2026-06-18-bidding-system-integration.md`
- `CONTEXT.md`
- `docs/superpowers/plans/2026-06-19-add-wallet-and-escrow.md`
- `docs/superpowers/plans/2026-06-20-add-slot-auction-promotions.md`
- `db/schema.sql`
- `src/main/java/com/tongji/wallet/service/WalletService.java`
- `src/main/java/com/tongji/wallet/model/WalletLedgerReason.java`
- `src/main/java/com/tongji/recommendation/HomeFeedMixingService.java`
- `src/main/java/com/tongji/recommendation/RecommendationCandidate.java`
- `src/main/java/com/tongji/recommendation/RecommendationEngine.java`
- `src/main/java/com/tongji/recommendation/feed/FollowFeedServiceImpl.java`
- `src/main/java/com/tongji/recommendation/feed/TimelineDispatcher.java`
- `src/main/java/com/tongji/recommendation/feed/TimelineExecutor.java`
- `src/main/java/com/tongji/knowpost/api/KnowPostController.java`
- `src/main/java/com/tongji/knowpost/api/dto/FeedItemResponse.java`
- `src/main/java/com/tongji/promotion/api/PromotionController.java`
- `src/main/java/com/tongji/promotion/service/PromotionCampaignService.java`
- `src/main/java/com/tongji/promotion/service/PromotionAllocationService.java`
- `src/main/java/com/tongji/promotion/config/PromotionProperties.java`
- `src/test/java/com/tongji/recommendation/HomeFeedMixingServiceTest.java`
- `src/test/java/com/tongji/recommendation/feed/FollowFeedServiceTest.java`
- `src/test/java/com/tongji/search/service/SearchServiceImplTest.java`
- `src/test/java/com/tongji/promotion/api/PromotionControllerTest.java`
- `参考语义（不构成 Maven/源码依赖）：/Volumes/lexar/revive/bytedance/backend/live-auction-domain/src/main/java/cn/revive/liveauction/domain/bidding/BidAmount.java`

## Senior Review

### Blockers

- [B1] `paid boost` 不得复用 `promotion_campaign` 现有拍卖语义。当前表/模型默认 `resource_type = feed_top_slot/search_top_slot`，且 controller/service 名字直指“campaign + bid”。若硬塞 `boost` 字段，会把同一个活动实体同时表达“拍卖活动”和“非拍卖活动”，后面维护必炸。必须新增独立 `paid boost` 表与 service/api。
- [B2] 现有 `RecommendationCandidate` 只有 `contentId + source`，没有 organic score。若直接在 `HomeFeedMixingService` 里拿到 ID 后再排序，无法证明 “organic score + boost effect” 真存在，只能变成“boost 把推荐列表前移” 的黑盒。必须先把 `RecommendationCandidate` 扩成包含 `organicScore`，并让 `GorseRecommendationAdapter` / hot fallback 都产出明确基线分。
- [B3] `follow feed` 当前没有现成“容量上限/截断规则”，只有 read-time `limit=20` 页面裁剪。spec 写的是“受限触达优先级”，不等于要重写 Kafka fanout 或 Cassandra schema。首期必须把“受限”具体化为 `KnowPostController.followFeed(...)` 的本地候选选择阶段，同时让 `FollowFeedServiceImpl` 只负责支持更大的 raw timeline 拉取，否则规则落不到代码。
- [B4] 预算结算不能做“每次曝光一条就扣一次 DB+wallet”。当前 home/follow 读接口高频，若请求路径直接写账，会把展示次数和接口重试混成账务事实。必须采用“读路径只记 spend 事件 / 计数，定时聚合结算”的两段式方案。

### Major

- [M1] `paid boost` 是独立 change。不要修改 `SearchServiceImpl`、不要扩 `search_top_slot`、不要把 boost 作用到搜索。现有 spec 只改 `recommendation-feed` 与 follow-feed。
- [M2] 现有 `FeedItemResponse` 的 `withPromotion(...)` 带 `auctionWindowId`，仅适合 slot allocation。`paid boost` 也要商业标记，但不能伪造 window id；要补独立 builder/metadata，允许 `promotionCampaignId` 存在而 `auctionWindowId = null`。
- [M3] 钱包前提已定：`platform-user-id = 0`，预算冻结走 `hold(...)`，消费走 `captureHoldToPlatform(...)`，剩余走 `releaseHold(...)`。不要引 escrow，不要发明平台内部二次账户。
- [M4] 首期“实际生效计费规则”必须保守可验。推荐最懒可行方案：按“每次被本地排序接纳并返回给客户端”计 1 次 deliver，消费额 = `min(unitPrice, 剩余预算)`；follow 同理。复杂 CPI/CTR 不做。
- [M5] 推荐 boost 只应放在 `HomeFeedMixingService` 的 recommendation stage，本地混排层处理，不改 Gorse 协议，不改 ES，不改外部引擎。
- [M6] follow boost 应只影响 `KnowPostController.followFeed(...)` 读路径返回结果，不改 `TimelineDispatcher` / `TimelineExecutor` 的实际 fanout 行为。PRD 的“触达优先级”先解释为“受限读取时优先展示”，这符合现有架构且最少破坏。
- [M7] 需要明确幂等 ref 设计。建议：
  - 预算冻结：`paid-boost:{campaignId}:reserve`
  - 周期消费：`paid-boost:{campaignId}:spend:{bucketStart}`
  - 结束释放：`paid-boost:{campaignId}:release`
  每类业务 ref 唯一且可重放。

### Minor

- [m1] 推荐 score 不需要绝对真实。hot fallback 没有外部 relevance 时，给一个单调递减基线分即可，例如 `count-index`。
- [m2] follow 受限配额不必上新表。直接加配置项并在 service 内按配置截断足够。
- [m3] schedule 继续用 `@Scheduled`，和 slot auction 保持一致；无须先接 `reconciliation_task`。

## 关键决策

1. `paid boost` 新增独立子域，不复用拍卖表。  
   具体：新增 `promotion_boost_campaign`、`promotion_boost_delivery` 两张表；不改 `promotion_bid` / `promotion_slot_allocation`。

2. boost 渠道只保留两个：`HOME_RECOMMENDATION`、`FOLLOW_DELIVERY`。  
   原因：刚好覆盖 spec；不把 search、public feed、slot auction 混进来。

3. 写路径使用单接口 `POST /api/v1/promotions/boost-campaigns`，不复用 slot auction 的 `campaign + bid` 两步式。  
   请求体直接表达“我要为某个帖子开一个 paid boost 投放活动”，而不是先建活动再补一笔 bid。

4. 用户提交的是 `bidAmount`，不是最终 `boostValue`。  
   创建活动时调用 `PaidBoostQuoteService`；该 service 在 zhiguang 仓内实现，参考 `bytedance` `BidAmount.of(...)` 与 `effectiveAmount` 语义做本地 quote，不引入外部仓代码依赖。排序与 follow 优先级只消费适配层返回的 `effectiveBoostValue`。  
   首期适配规则：`effectiveBoostValue = min(validatedBidAmount, recommendationMaxBoostEffect)`，但这个值必须来自 `PaidBoostQuoteService` 返回，而不是 controller/service 直接拿请求体字段入库。

5. 推荐排序的公式在本地显式化为：  
   `finalScore = organicScore + effectiveBoostValue`  
   条件：只有活动 active、预算未耗尽、帖子仍可见时才参与加权。

6. follow “受限触达” 首期具体化为：  
   `KnowPostController.followFeed(...)` 在检测到有 active `FOLLOW_DELIVERY` boost 时，向 `FollowFeedServiceImpl` 请求 `follow.delivery-selection-cap` 条 raw timeline 候选；service 仅负责给出更大的原始时间序列窗口，controller 再在这个窗口内做“boost 优先选择 20 条交付结果”。  
   理由：不改 fanout 写模型；避免在 `FollowFeedServiceImpl` 里破坏 cursor 语义；timeline cache 也会因为请求 limit 不再是默认 20 而自然旁路。

7. 预算账务采用“三段式”：  
   创建活动冻结总预算；运行中只记录 delivery 事实，不直接写 wallet；定时聚合器按 bucket 扣费到平台；活动结束释放剩余冻结。  
   理由：把高频读与低频账务分离，账务可重试且幂等。

8. delivery 事实最小单位是“内容被最终返回给已登录客户端一次”。  
   字段：`campaignId/channel/postId/viewerUserId/deliveryBucketStart/deliveryCount/unitPriceSnapshot/capturedAmount/status`。  
   计费去重口径：同一 `viewerUserId + campaignId + postId + deliveryBucketStart` 在同一 bucket 内重复返回，只累计到同一条 delivery 事实，不因刷新重复新增计费事实。  
   本 change 只覆盖登录态 `home feed` 与 `follow feed`，不处理匿名首页，因此 `viewer_user_id` 可设为非空。

9. 商业标记复用 `FeedItemResponse` 现有字段，但扩一个通用方法：  
   `withPromotionMetadata(placementType, promotionCampaignId, auctionWindowId)`；slot auction 和 paid boost 都走它。  
   `paid boost` 传 `placementType = home_recommendation_boost` 或 `follow_delivery_boost`，`auctionWindowId = null`。

10. `PromotionController` 不继续承载 paid boost。  
   新增 `PaidBoostController`，避免 slot auction API 与 paid boost API 混在同一套 DTO/校验里。

11. 结束调度单独新增 `PaidBoostScheduler`。  
   任务：
   - 结算到期前的 delivery bucket
   - 关闭已结束 campaign
   - 释放剩余预算
   - 刷新 boost 活动缓存

12. active boost 读路径统一由 `PaidBoostCacheService` 提供。  
    `PaidBoostDeliveryService` 只负责记录与结算 delivery 事实，不承担读路径 active campaign 查询。

13. active boost 读路径走独立 Redis key。  
    例如：
    - `promotion:boost:active:home_recommendation`
    - `promotion:boost:active:follow_delivery`
    不塞进 slot allocation 缓存。

## 文件地图

### 预计新增文件

- `src/main/java/com/tongji/promotion/model/PaidBoostChannel.java`
- `src/main/java/com/tongji/promotion/model/PaidBoostCampaign.java`
- `src/main/java/com/tongji/promotion/model/PaidBoostCampaignStatus.java`
- `src/main/java/com/tongji/promotion/model/PaidBoostDelivery.java`
- `src/main/java/com/tongji/promotion/model/PaidBoostDeliveryStatus.java`
- `src/main/java/com/tongji/promotion/mapper/PaidBoostCampaignMapper.java`
- `src/main/java/com/tongji/promotion/mapper/PaidBoostDeliveryMapper.java`
- `src/main/java/com/tongji/promotion/service/PaidBoostCampaignService.java`
- `src/main/java/com/tongji/promotion/service/PaidBoostQuoteService.java`
- `src/main/java/com/tongji/promotion/service/PaidBoostDeliveryService.java`
- `src/main/java/com/tongji/promotion/service/PaidBoostRankingService.java`
- `src/main/java/com/tongji/promotion/service/PaidBoostSettlementService.java`
- `src/main/java/com/tongji/promotion/service/PaidBoostCacheService.java`
- `src/main/java/com/tongji/promotion/api/PaidBoostController.java`
- `src/main/java/com/tongji/promotion/api/dto/CreatePaidBoostCampaignRequest.java`
- `src/main/java/com/tongji/promotion/api/dto/PaidBoostCampaignResponse.java`
- `src/main/java/com/tongji/promotion/api/dto/PaidBoostDeliverySummaryResponse.java`
- `src/main/java/com/tongji/promotion/schedule/PaidBoostScheduler.java`
- `src/main/resources/mapper/PaidBoostCampaignMapper.xml`
- `src/main/resources/mapper/PaidBoostDeliveryMapper.xml`
- `src/test/java/com/tongji/promotion/service/PaidBoostCampaignServiceTest.java`
- `src/test/java/com/tongji/promotion/service/PaidBoostRankingServiceTest.java`
- `src/test/java/com/tongji/promotion/service/PaidBoostSettlementServiceTest.java`
- `src/test/java/com/tongji/promotion/api/PaidBoostControllerTest.java`
- `src/test/java/com/tongji/promotion/schedule/PaidBoostSchedulerTest.java`
- `src/test/java/com/tongji/promotion/PaidBoostMysqlIntegrationTest.java`

### 预计修改文件

- `db/schema.sql`
- `CONTEXT.md`
- `src/main/java/com/tongji/common/exception/ErrorCode.java`
- `src/main/java/com/tongji/wallet/model/WalletLedgerReason.java`
- `src/main/java/com/tongji/wallet/service/WalletService.java`
- `src/main/java/com/tongji/recommendation/RecommendationCandidate.java`
- `src/main/java/com/tongji/recommendation/GorseRecommendationAdapter.java`
- `src/main/java/com/tongji/recommendation/HomeFeedMixingService.java`
- `src/main/java/com/tongji/recommendation/feed/FollowFeedServiceImpl.java`
- `src/main/java/com/tongji/knowpost/api/KnowPostController.java`
- `src/main/java/com/tongji/knowpost/api/dto/FeedItemResponse.java`
- `src/main/resources/application.yml`
- `src/test/java/com/tongji/recommendation/HomeFeedMixingServiceTest.java`
- `src/test/java/com/tongji/recommendation/feed/FollowFeedServiceTest.java`
- `src/test/java/com/tongji/promotion/api/PromotionControllerTest.java`（仅在通用 metadata helper 复用时需要微调）
- `openspec/changes/add-paid-boost-promotions/tasks.md`（实现完后回填）

## 数据模型草案

```sql
CREATE TABLE promotion_boost_campaign (
  id BIGINT UNSIGNED PRIMARY KEY,
  creator_user_id BIGINT UNSIGNED NOT NULL,
  post_id BIGINT UNSIGNED NOT NULL,
  channel VARCHAR(32) NOT NULL,
  bid_amount BIGINT NOT NULL,
  boost_value BIGINT NOT NULL,
  unit_price BIGINT NOT NULL,
  budget_total BIGINT NOT NULL,
  budget_consumed BIGINT NOT NULL,
  reserve_business_ref VARCHAR(128) NOT NULL,
  status VARCHAR(16) NOT NULL,
  start_at DATETIME(3) NOT NULL,
  end_at DATETIME(3) NOT NULL,
  closed_at DATETIME(3) NULL,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  UNIQUE KEY uk_paid_boost_campaign_reserve_ref (reserve_business_ref),
  KEY idx_paid_boost_campaign_creator_status (creator_user_id, status),
  KEY idx_paid_boost_campaign_channel_window (channel, status, start_at, end_at),
  KEY idx_paid_boost_campaign_post_channel (post_id, channel)
);

CREATE TABLE promotion_boost_delivery (
  id BIGINT UNSIGNED PRIMARY KEY,
  campaign_id BIGINT UNSIGNED NOT NULL,
  channel VARCHAR(32) NOT NULL,
  post_id BIGINT UNSIGNED NOT NULL,
  viewer_user_id BIGINT UNSIGNED NOT NULL,
  delivery_bucket_start_at DATETIME(3) NOT NULL,
  delivery_count INT NOT NULL,
  unit_price_snapshot BIGINT NOT NULL,
  captured_amount BIGINT NOT NULL,
  settle_business_ref VARCHAR(128) NOT NULL,
  status VARCHAR(16) NOT NULL,
  settled_at DATETIME(3) NULL,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  UNIQUE KEY uk_paid_boost_delivery_campaign_bucket_viewer (campaign_id, delivery_bucket_start_at, viewer_user_id),
  UNIQUE KEY uk_paid_boost_delivery_settle_ref (settle_business_ref),
  KEY idx_paid_boost_delivery_status_bucket (status, delivery_bucket_start_at),
  KEY idx_paid_boost_delivery_campaign_status (campaign_id, status)
);
```

说明：

- `bid_amount` 是创作者原始出价，货币单位与 wallet 一致；它用于 quote 与审计，不直接参与排序。
- `boost_value` 是排序加权值，不是货币。
- `unit_price` 是每次有效 deliver 的扣费单价，货币单位与 wallet 一致。
- `budget_consumed` 是已结算 capture 额，不含仍处于 `PENDING` 的 delivery。
- `delivery_bucket_start_at` 统一按配置粒度对齐，例如 1 分钟；同一 viewer 同一活动同一 bucket 聚合成一条，减小写放大。
- 本 change 只覆盖登录态 `home feed` / `follow feed`，因此 `viewer_user_id` 不允许为空。
- `captured_amount` 初始为 `0`；结算时按 `min(remainingBudget, deliveryCount * unitPriceSnapshot)` 回填，避免并发记录 delivery 时把预算先超记。

## API 与配置草案

### 写接口

- `POST /api/v1/promotions/boost-campaigns`
- `GET /api/v1/promotions/boost-campaigns/{campaignId}`
- `GET /api/v1/promotions/boost-campaigns/{campaignId}/deliveries`

`POST /api/v1/promotions/boost-campaigns` 请求体：

```json
{
  "postId": 1001,
  "channel": "home_recommendation",
  "bidAmount": 30,
  "unitPrice": 2,
  "budgetTotal": 100,
  "startAt": "2026-06-21T10:00:00Z",
  "endAt": "2026-06-21T12:00:00Z"
}
```

约束：

- `bidAmount > 0`
- `unitPrice > 0`
- `budgetTotal >= unitPrice`
- `startAt < endAt`
- 帖子必须归当前用户所有
- 帖子必须仍可见于对应渠道（`published`；home 还需 `visible=public`，follow 允许 `public/followers`）
- 服务端返回的活动详情应包含 `effectiveBoostValue`；客户端不直接提交该字段

### 配置

在 `application.yml` 新增：

```yaml
promotion:
  paid-boost:
    active-cache-ttl-seconds: ${PROMOTION_PAID_BOOST_CACHE_TTL_SECONDS:60}
    delivery-bucket-seconds: ${PROMOTION_PAID_BOOST_DELIVERY_BUCKET_SECONDS:60}
    settle-delay-ms: ${PROMOTION_PAID_BOOST_SETTLE_DELAY_MS:30000}
    recommendation-max-boost-effect: ${PROMOTION_PAID_BOOST_RECOMMENDATION_MAX_EFFECT:50}
    follow-delivery-selection-cap: ${PROMOTION_PAID_BOOST_FOLLOW_SELECTION_CAP:40}
```

## 任务拆解

### Task 1: 建 paid boost 基础模型与 schema

**Files:**
- Create: `src/main/java/com/tongji/promotion/model/PaidBoostChannel.java`
- Create: `src/main/java/com/tongji/promotion/model/PaidBoostCampaign.java`
- Create: `src/main/java/com/tongji/promotion/model/PaidBoostCampaignStatus.java`
- Create: `src/main/java/com/tongji/promotion/model/PaidBoostDelivery.java`
- Create: `src/main/java/com/tongji/promotion/model/PaidBoostDeliveryStatus.java`
- Modify: `db/schema.sql`
- Test: `src/test/java/com/tongji/promotion/PaidBoostMysqlIntegrationTest.java`

- [ ] **Step 1: 写失败的 schema/model 契约测试**

```java
@Test
void paidBoostTablesSupportCampaignLifecycle() {
    jdbcTemplate.execute("""
        INSERT INTO promotion_boost_campaign (
          id, creator_user_id, post_id, channel, bid_amount, boost_value, unit_price, budget_total, budget_consumed,
          reserve_business_ref, status, start_at, end_at, closed_at, created_at, updated_at
        ) VALUES (
          1, 42, 1001, 'HOME_RECOMMENDATION', 30, 30, 2, 100, 0,
          'paid-boost:1:reserve', 'ACTIVE', NOW(3), DATE_ADD(NOW(3), INTERVAL 1 HOUR), NULL, NOW(3), NOW(3)
        )
        """);
    jdbcTemplate.execute("""
        INSERT INTO promotion_boost_delivery (
          id, campaign_id, channel, post_id, viewer_user_id, delivery_bucket_start_at, delivery_count,
          unit_price_snapshot, captured_amount, settle_business_ref, status, settled_at, created_at, updated_at
        ) VALUES (
          11, 1, 'HOME_RECOMMENDATION', 1001, 77, NOW(3), 1,
          2, 0, 'paid-boost:1:spend:202606211000:77', 'PENDING', NULL, NOW(3), NOW(3)
        )
        """);
    Integer campaigns = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM promotion_boost_campaign", Integer.class);
    Integer deliveries = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM promotion_boost_delivery", Integer.class);
    assertThat(campaigns).isEqualTo(1);
    assertThat(deliveries).isEqualTo(1);
}
```

- [ ] **Step 2: 运行测试，确认当前失败**

Run: `mvn -Dtest=PaidBoostMysqlIntegrationTest#paidBoostTablesSupportCampaignLifecycle test`
Expected: FAIL，报 `Table 'promotion_boost_campaign' doesn't exist` 或同类建表缺失错误。

- [ ] **Step 3: 最小实现 schema 与模型**

```java
public enum PaidBoostChannel {
    HOME_RECOMMENDATION,
    FOLLOW_DELIVERY;

    public String placementType() {
        return name().toLowerCase();
    }
}
```

```java
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaidBoostCampaign {
    private long id;
    private long creatorUserId;
    private long postId;
    private PaidBoostChannel channel;
    private long bidAmount;
    private long boostValue;
    private long unitPrice;
    private long budgetTotal;
    private long budgetConsumed;
    private String reserveBusinessRef;
    private PaidBoostCampaignStatus status;
    private Instant startAt;
    private Instant endAt;
    private Instant closedAt;
    private Instant createdAt;
    private Instant updatedAt;
}
```

```sql
CREATE TABLE IF NOT EXISTS promotion_boost_campaign ( ... );
CREATE TABLE IF NOT EXISTS promotion_boost_delivery ( ... );
```

- [ ] **Step 4: 运行测试，确认通过**

Run: `mvn -Dtest=PaidBoostMysqlIntegrationTest#paidBoostTablesSupportCampaignLifecycle test`
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add db/schema.sql src/main/java/com/tongji/promotion/model src/test/java/com/tongji/promotion/PaidBoostMysqlIntegrationTest.java
git commit -m "feat: add paid boost schema"
```

### Task 2: 建 mapper 与查询边界

**Files:**
- Create: `src/main/java/com/tongji/promotion/mapper/PaidBoostCampaignMapper.java`
- Create: `src/main/java/com/tongji/promotion/mapper/PaidBoostDeliveryMapper.java`
- Create: `src/main/resources/mapper/PaidBoostCampaignMapper.xml`
- Create: `src/main/resources/mapper/PaidBoostDeliveryMapper.xml`
- Test: `src/test/java/com/tongji/promotion/PaidBoostMysqlIntegrationTest.java`

- [ ] **Step 1: 写失败的 mapper 集成测试**

```java
@Test
void campaignMapperFindsActiveCampaignsByChannel() {
    PaidBoostCampaign campaign = PaidBoostCampaign.builder()
            .id(1L).creatorUserId(42L).postId(1001L)
            .channel(PaidBoostChannel.HOME_RECOMMENDATION)
            .bidAmount(30L)
            .boostValue(30L).unitPrice(2L).budgetTotal(100L).budgetConsumed(0L)
            .reserveBusinessRef("paid-boost:1:reserve")
            .status(PaidBoostCampaignStatus.ACTIVE)
            .startAt(Instant.parse("2026-06-21T10:00:00Z"))
            .endAt(Instant.parse("2026-06-21T12:00:00Z"))
            .createdAt(Instant.parse("2026-06-21T10:00:00Z"))
            .updatedAt(Instant.parse("2026-06-21T10:00:00Z"))
            .build();
    campaignMapper.insert(campaign);
    List<PaidBoostCampaign> active = campaignMapper.listActiveByChannel(
            PaidBoostChannel.HOME_RECOMMENDATION,
            Instant.parse("2026-06-21T10:30:00Z")
    );
    assertThat(active).extracting(PaidBoostCampaign::getId).containsExactly(1L);
}
```

- [ ] **Step 2: 运行测试，确认当前失败**

Run: `mvn -Dtest=PaidBoostMysqlIntegrationTest#campaignMapperFindsActiveCampaignsByChannel test`
Expected: FAIL，报 mapper bean / SQL 缺失。

- [ ] **Step 3: 最小实现 mapper**

```java
@Mapper
public interface PaidBoostCampaignMapper {
    int insert(PaidBoostCampaign campaign);
    PaidBoostCampaign findById(@Param("id") long id);
    List<PaidBoostCampaign> listActiveByChannel(@Param("channel") PaidBoostChannel channel,
                                                @Param("now") Instant now);
    List<PaidBoostCampaign> listClosable(@Param("now") Instant now, @Param("limit") int limit);
    int increaseBudgetConsumed(@Param("id") long id, @Param("amount") long amount, @Param("updatedAt") Instant updatedAt);
    int markClosed(@Param("id") long id, @Param("closedAt") Instant closedAt, @Param("updatedAt") Instant updatedAt);
}
```

```java
@Mapper
public interface PaidBoostDeliveryMapper {
    int upsertPending(PaidBoostDelivery delivery);
    List<PaidBoostDelivery> listPendingBefore(@Param("now") Instant now, @Param("limit") int limit);
    int markSettledWithAmount(@Param("id") long id, @Param("capturedAmount") long capturedAmount,
                              @Param("settledAt") Instant settledAt);
    List<PaidBoostDelivery> listByCampaignId(@Param("campaignId") long campaignId,
                                             @Param("limit") int limit,
                                             @Param("offset") int offset);
}
```

```xml
<select id="listActiveByChannel" resultMap="PaidBoostCampaignResultMap">
  SELECT *
  FROM promotion_boost_campaign
  WHERE channel = #{channel}
    AND status = 'ACTIVE'
    AND start_at <= #{now}
    AND end_at > #{now}
    AND budget_consumed < budget_total
  ORDER BY boost_value DESC, id ASC
</select>
```

```xml
<insert id="upsertPending">
  INSERT INTO promotion_boost_delivery (
    id, campaign_id, channel, post_id, viewer_user_id, delivery_bucket_start_at, delivery_count,
    unit_price_snapshot, captured_amount, settle_business_ref, status, settled_at, created_at, updated_at
  ) VALUES (
    #{id}, #{campaignId}, #{channel}, #{postId}, #{viewerUserId}, #{deliveryBucketStartAt}, #{deliveryCount},
    #{unitPriceSnapshot}, #{capturedAmount}, #{settleBusinessRef}, #{status}, #{settledAt}, #{createdAt}, #{updatedAt}
  )
  ON DUPLICATE KEY UPDATE
    delivery_count = delivery_count + VALUES(delivery_count),
    updated_at = VALUES(updatedAt)
</insert>
```

- [ ] **Step 4: 运行测试，确认通过**

Run: `mvn -Dtest=PaidBoostMysqlIntegrationTest#campaignMapperFindsActiveCampaignsByChannel test`
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/tongji/promotion/mapper src/main/resources/mapper/PaidBoostCampaignMapper.xml src/main/resources/mapper/PaidBoostDeliveryMapper.xml src/test/java/com/tongji/promotion/PaidBoostMysqlIntegrationTest.java
git commit -m "feat: add paid boost mappers"
```

### Task 3: 创建 paid boost 活动并冻结预算

**Files:**
- Create: `src/main/java/com/tongji/promotion/service/PaidBoostCampaignService.java`
- Create: `src/main/java/com/tongji/promotion/service/PaidBoostQuoteService.java`
- Create: `src/main/java/com/tongji/promotion/api/PaidBoostController.java`
- Create: `src/main/java/com/tongji/promotion/api/dto/CreatePaidBoostCampaignRequest.java`
- Create: `src/main/java/com/tongji/promotion/api/dto/PaidBoostCampaignResponse.java`
- Modify: `src/main/java/com/tongji/common/exception/ErrorCode.java`
- Test: `src/test/java/com/tongji/promotion/service/PaidBoostCampaignServiceTest.java`
- Test: `src/test/java/com/tongji/promotion/api/PaidBoostControllerTest.java`

- [ ] **Step 1: 写失败的 service 测试**

```java
@Test
void createCampaignHoldsBudgetAndPersistsActiveCampaign() {
    when(knowPostMapper.findById(1001L)).thenReturn(post(1001L, 42L, "published", "public"));
    when(quoteService.quote(PaidBoostChannel.HOME_RECOMMENDATION, 30L)).thenReturn(20L);
    PaidBoostCampaign campaign = service.createCampaign(
            42L, 1001L, PaidBoostChannel.HOME_RECOMMENDATION,
            30L, 2L, 100L,
            Instant.parse("2026-06-21T10:00:00Z"),
            Instant.parse("2026-06-21T12:00:00Z")
    );
    verify(walletService).hold(42L, 100L, WalletLedgerReason.HOLD_RESERVE,
            WalletBusinessType.PROMOTION, "paid-boost:" + campaign.getId() + ":reserve");
    verify(quoteService).quote(PaidBoostChannel.HOME_RECOMMENDATION, 30L);
    assertThat(campaign.getBidAmount()).isEqualTo(30L);
    assertThat(campaign.getBoostValue()).isEqualTo(20L);
    assertThat(campaign.getStatus()).isEqualTo(PaidBoostCampaignStatus.ACTIVE);
}
```

- [ ] **Step 2: 运行测试，确认当前失败**

Run: `mvn -Dtest=PaidBoostCampaignServiceTest#createCampaignHoldsBudgetAndPersistsActiveCampaign test`
Expected: FAIL，报 `PaidBoostCampaignService` 不存在或未冻结预算。

- [ ] **Step 3: 最小实现 service**

```java
@Transactional
public PaidBoostCampaign createCampaign(long creatorUserId, long postId, PaidBoostChannel channel,
                                        long bidAmount, long unitPrice, long budgetTotal,
                                        Instant startAt, Instant endAt) {
    validate(channel, bidAmount, unitPrice, budgetTotal, startAt, endAt);
    KnowPost post = requireOwnedEligiblePost(creatorUserId, postId, channel);
    Instant now = Instant.now();
    long id = idService.nextId(IdNamespace.ADMIN_OPERATION);
    long effectiveBoostValue = quoteService.quote(channel, bidAmount);
    String reserveRef = "paid-boost:" + id + ":reserve";
    walletService.hold(creatorUserId, budgetTotal, WalletLedgerReason.HOLD_RESERVE,
            WalletBusinessType.PROMOTION, reserveRef);
    PaidBoostCampaign campaign = PaidBoostCampaign.builder()
            .id(id)
            .creatorUserId(creatorUserId)
            .postId(post.getId())
            .channel(channel)
            .bidAmount(bidAmount)
            .boostValue(effectiveBoostValue)
            .unitPrice(unitPrice)
            .budgetTotal(budgetTotal)
            .budgetConsumed(0L)
            .reserveBusinessRef(reserveRef)
            .status(PaidBoostCampaignStatus.ACTIVE)
            .startAt(startAt)
            .endAt(endAt)
            .createdAt(now)
            .updatedAt(now)
            .build();
    campaignMapper.insert(campaign);
    return campaign;
}
```

- [ ] **Step 4: 补 controller 测试与实现**

```java
@Test
void createCampaignDelegatesWithExtractedUser() {
    when(jwtService.extractUserId(any())).thenReturn(42L);
    when(campaignService.createCampaign(eq(42L), eq(1001L), eq(PaidBoostChannel.HOME_RECOMMENDATION),
            eq(30L), eq(2L), eq(100L), any(), any())).thenReturn(campaign());
    PaidBoostCampaignResponse response = controller.createCampaign(new CreatePaidBoostCampaignRequest(
            1001L, "home_recommendation", 30L, 2L, 100L,
            Instant.parse("2026-06-21T10:00:00Z"),
            Instant.parse("2026-06-21T12:00:00Z")
    ), null);
    assertThat(response.channel()).isEqualTo("home_recommendation");
}
```

- [ ] **Step 5: 运行测试，确认通过**

Run: `mvn -Dtest=PaidBoostCampaignServiceTest,PaidBoostControllerTest test`
Expected: PASS。

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/tongji/promotion/api src/main/java/com/tongji/promotion/service/PaidBoostCampaignService.java src/main/java/com/tongji/promotion/service/PaidBoostQuoteService.java src/main/java/com/tongji/common/exception/ErrorCode.java src/test/java/com/tongji/promotion/service/PaidBoostCampaignServiceTest.java src/test/java/com/tongji/promotion/api/PaidBoostControllerTest.java
git commit -m "feat: create paid boost campaigns"
```

### Task 4: 建 active boost 缓存与查询服务

**Files:**
- Create: `src/main/java/com/tongji/promotion/service/PaidBoostCacheService.java`
- Modify: `src/main/resources/application.yml`
- Test: `src/test/java/com/tongji/promotion/service/PaidBoostCampaignServiceTest.java`

- [ ] **Step 1: 写失败的缓存读取测试**

```java
@Test
void getActiveHomeCampaignsFallsBackToDbAndCaches() {
    when(campaignMapper.listActiveByChannel(PaidBoostChannel.HOME_RECOMMENDATION, now))
            .thenReturn(List.of(campaign(1L, PaidBoostChannel.HOME_RECOMMENDATION, 30L, 2L, 100L, 0L)));
    List<PaidBoostCampaign> active = cacheService.getActive(PaidBoostChannel.HOME_RECOMMENDATION, now);
    assertThat(active).extracting(PaidBoostCampaign::getId).containsExactly(1L);
    verify(redisTemplate.opsForValue()).set(eq("promotion:boost:active:home_recommendation"), any(String.class), any(Duration.class));
}
```

- [ ] **Step 2: 运行测试，确认当前失败**

Run: `mvn -Dtest=PaidBoostCampaignServiceTest#getActiveHomeCampaignsFallsBackToDbAndCaches test`
Expected: FAIL。

- [ ] **Step 3: 最小实现**

```java
public List<PaidBoostCampaign> getActive(PaidBoostChannel channel, Instant now) {
    String key = "promotion:boost:active:" + channel.name().toLowerCase();
    List<PaidBoostCampaign> cached = read(key);
    if (cached != null) {
        return cached.stream()
                .filter(c -> !now.isBefore(c.getStartAt()) && now.isBefore(c.getEndAt()))
                .filter(c -> c.getBudgetConsumed() < c.getBudgetTotal())
                .toList();
    }
    List<PaidBoostCampaign> loaded = campaignMapper.listActiveByChannel(channel, now);
    write(key, loaded);
    return loaded;
}
```

- [ ] **Step 4: 配置落地**

```yaml
promotion:
  paid-boost:
    active-cache-ttl-seconds: ${PROMOTION_PAID_BOOST_CACHE_TTL_SECONDS:60}
    delivery-bucket-seconds: ${PROMOTION_PAID_BOOST_DELIVERY_BUCKET_SECONDS:60}
    settle-delay-ms: ${PROMOTION_PAID_BOOST_SETTLE_DELAY_MS:30000}
    recommendation-max-boost-effect: ${PROMOTION_PAID_BOOST_RECOMMENDATION_MAX_EFFECT:50}
    follow-delivery-selection-cap: ${PROMOTION_PAID_BOOST_FOLLOW_SELECTION_CAP:40}
```

- [ ] **Step 5: 运行测试，确认通过**

Run: `mvn -Dtest=PaidBoostCampaignServiceTest#getActiveHomeCampaignsFallsBackToDbAndCaches test`
Expected: PASS。

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/tongji/promotion/service/PaidBoostCacheService.java src/main/resources/application.yml src/test/java/com/tongji/promotion/service/PaidBoostCampaignServiceTest.java
git commit -m "feat: cache active paid boost campaigns"
```

### Task 5: 给 recommendation candidate 显式加 organic score

**Files:**
- Modify: `src/main/java/com/tongji/recommendation/RecommendationCandidate.java`
- Modify: `src/main/java/com/tongji/recommendation/GorseRecommendationAdapter.java`
- Test: `src/test/java/com/tongji/recommendation/HomeFeedMixingServiceTest.java`

- [ ] **Step 1: 写失败的 candidate 分数测试**

```java
@Test
void recommendationCandidatesCarryOrganicScore() {
    RecommendationCandidate candidate = new RecommendationCandidate(201L, "gorse", 98.0);
    assertThat(candidate.organicScore()).isEqualTo(98.0);
}
```

- [ ] **Step 2: 运行测试，确认当前失败**

Run: `mvn -Dtest=HomeFeedMixingServiceTest#recommendationCandidatesCarryOrganicScore test`
Expected: FAIL，record 构造签名不匹配。

- [ ] **Step 3: 最小实现**

```java
public record RecommendationCandidate(long contentId, String source, double organicScore) {
}
```

```java
return candidateIds.stream()
        .map(Long::parseLong)
        .map(id -> new RecommendationCandidate(id, "gorse", 100.0))
        .toList();
```

```java
AtomicInteger rank = new AtomicInteger(count);
return knowPostMapper.listFeedPublicIds(count, 0).stream()
        .filter(Objects::nonNull)
        .map(id -> new RecommendationCandidate(id, "hot", rank.getAndDecrement()))
        .toList();
```

- [ ] **Step 4: 批量修测试 fixture**

把现有 `new RecommendationCandidate(id, "gorse")` 全改成显式分数，比如：

```java
new RecommendationCandidate(201L, "gorse", 100.0)
```

- [ ] **Step 5: 运行测试，确认通过**

Run: `mvn -Dtest=HomeFeedMixingServiceTest test`
Expected: PASS。

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/tongji/recommendation/RecommendationCandidate.java src/main/java/com/tongji/recommendation/GorseRecommendationAdapter.java src/test/java/com/tongji/recommendation/HomeFeedMixingServiceTest.java
git commit -m "refactor: add organic score to recommendation candidates"
```

### Task 6: 建 recommendation boost 排序服务

**Files:**
- Create: `src/main/java/com/tongji/promotion/service/PaidBoostRankingService.java`
- Test: `src/test/java/com/tongji/promotion/service/PaidBoostRankingServiceTest.java`

- [ ] **Step 1: 写失败的排序测试**

```java
@Test
void reranksRecommendationCandidatesByOrganicScorePlusBoostEffect() {
    List<RecommendationCandidate> ranked = service.rankRecommendationCandidates(
            List.of(
                    new RecommendationCandidate(201L, "gorse", 90.0),
                    new RecommendationCandidate(202L, "gorse", 95.0)
            ),
            Map.of(
                    201L, campaign(1L, PaidBoostChannel.HOME_RECOMMENDATION, 20L, 2L, 100L, 0L),
                    202L, campaign(2L, PaidBoostChannel.HOME_RECOMMENDATION, 1L, 2L, 100L, 0L)
            )
    );
    assertThat(ranked).extracting(RecommendationCandidate::contentId).containsExactly(201L, 202L);
}
```

- [ ] **Step 2: 运行测试，确认当前失败**

Run: `mvn -Dtest=PaidBoostRankingServiceTest#reranksRecommendationCandidatesByOrganicScorePlusBoostEffect test`
Expected: FAIL。

- [ ] **Step 3: 最小实现**

```java
public List<RecommendationCandidate> rankRecommendationCandidates(List<RecommendationCandidate> input,
                                                                  Map<Long, PaidBoostCampaign> boosts) {
    return input.stream()
            .sorted(Comparator
                    .comparingDouble((RecommendationCandidate c) -> c.organicScore() + effect(boosts.get(c.contentId()))).reversed()
                    .thenComparingLong(RecommendationCandidate::contentId))
            .toList();
}

private double effect(PaidBoostCampaign campaign) {
    if (campaign == null) {
        return 0D;
    }
    return Math.min(campaign.getBoostValue(), recommendationMaxBoostEffect);
}
```

- [ ] **Step 4: 运行测试，确认通过**

Run: `mvn -Dtest=PaidBoostRankingServiceTest test`
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/tongji/promotion/service/PaidBoostRankingService.java src/test/java/com/tongji/promotion/service/PaidBoostRankingServiceTest.java
git commit -m "feat: rank recommendations with paid boost"
```

### Task 7: 在 home feed recommendation stage 接入 boost 与商业标记

**Files:**
- Modify: `src/main/java/com/tongji/recommendation/HomeFeedMixingService.java`
- Modify: `src/main/java/com/tongji/knowpost/api/dto/FeedItemResponse.java`
- Test: `src/test/java/com/tongji/recommendation/HomeFeedMixingServiceTest.java`

- [ ] **Step 1: 写失败的 home feed 测试**

```java
@Test
void boostsRecommendationCandidatesAndMarksCommercialItems() {
    when(followFeedService.getTimeline(42L, null, 20)).thenReturn(new TimelinePage(List.of(), null));
    when(recommendationEngine.recommend(42L, 40)).thenReturn(List.of(
            new RecommendationCandidate(201L, "gorse", 90.0),
            new RecommendationCandidate(202L, "gorse", 95.0)
    ));
    when(paidBoostCacheService.getActive(PaidBoostChannel.HOME_RECOMMENDATION, any()))
            .thenReturn(List.of(campaignForPost(201L, 20L)));
    when(knowPostFeedService.getFeedByIds(anyList(), eq(42L), eq(KnowPostFeedService.FeedVisibilityScope.PUBLIC)))
            .thenAnswer(inv -> ((List<Long>) inv.getArgument(0)).stream().map(this::feedItem).toList());

    FeedPageResponse response = service.getHomeFeed(42L);

    assertThat(response.items().get(0).id()).isEqualTo("201");
    assertThat(response.items().get(0).commercial()).isTrue();
    assertThat(response.items().get(0).placementType()).isEqualTo("home_recommendation_boost");
}
```

- [ ] **Step 2: 运行测试，确认当前失败**

Run: `mvn -Dtest=HomeFeedMixingServiceTest#boostsRecommendationCandidatesAndMarksCommercialItems test`
Expected: FAIL。

- [ ] **Step 3: 最小实现**

```java
List<RecommendationCandidate> rankedCandidates = paidBoostRankingService.rankRecommendationCandidates(
        recommendationEngine.recommend(userId, RECOMMENDATION_CANDIDATE_LIMIT),
        activeHomeBoostsByPostId()
);
List<Long> recommendationIds = rankedCandidates.stream().map(RecommendationCandidate::contentId).toList();
```

```java
public FeedItemResponse withPromotionMetadata(String placementType, String promotionCampaignId, String auctionWindowId) {
    return new FeedItemResponse(id, title, description, coverImage, tags, authorAvatar, authorNickname, tagJson,
            likeCount, favoriteCount, liked, faved, isTop, true, true, placementType, promotionCampaignId, auctionWindowId);
}
```

```java
if (campaign != null) {
    items.add(item.withPromotionMetadata("home_recommendation_boost", String.valueOf(campaign.getId()), null));
} else {
    items.add(item);
}
```

- [ ] **Step 4: 运行测试，确认通过**

Run: `mvn -Dtest=HomeFeedMixingServiceTest test`
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/tongji/recommendation/HomeFeedMixingService.java src/main/java/com/tongji/knowpost/api/dto/FeedItemResponse.java src/test/java/com/tongji/recommendation/HomeFeedMixingServiceTest.java
git commit -m "feat: apply paid boost in home feed"
```

### Task 8: 在 follow feed 受限裁剪阶段接入 boost 优先级

**Files:**
- Modify: `src/main/java/com/tongji/recommendation/feed/FollowFeedServiceImpl.java`
- Modify: `src/main/java/com/tongji/knowpost/api/KnowPostController.java`
- Test: `src/test/java/com/tongji/recommendation/feed/FollowFeedServiceTest.java`
- Test: `src/test/java/com/tongji/knowpost/api/KnowPostControllerPublishTest.java`

- [ ] **Step 1: 写失败的 follow 受限测试**

```java
@Test
void constrainedFollowSelectionPrefersBoostedItems() {
    ReflectionTestUtils.setField(controller, "followFeedSelectionCap", 3);
    when(followFeedService.getTimeline(42L, null, 3)).thenReturn(new TimelinePage(List.of(
            timelineItem(103L, "2026-06-21T10:00:03Z"),
            timelineItem(102L, "2026-06-21T10:00:02Z"),
            timelineItem(101L, "2026-06-21T10:00:01Z")
    ), null));
    when(paidBoostCacheService.getActive(PaidBoostChannel.FOLLOW_DELIVERY, any()))
            .thenReturn(List.of(campaignForPost(101L, 20L)));
    when(feedService.getFeedByIds(List.of(103L, 101L), 42L, KnowPostFeedService.FeedVisibilityScope.FOLLOW))
            .thenReturn(List.of(feedItem(103L), feedItem(101L)));

    FeedPageResponse page = controller.followFeed(null, jwt);

    assertThat(page.items()).extracting(FeedItemResponse::id).containsExactly("103", "101");
    assertThat(page.items().get(1).commercial()).isTrue();
}
```

- [ ] **Step 2: 运行测试，确认当前失败**

Run: `mvn -Dtest=FollowFeedServiceTest#constrainedFollowSelectionPrefersBoostedItems test`
Expected: FAIL。

- [ ] **Step 3: 最小实现**

在 `KnowPostController` 新增：

```java
@Value("${promotion.paid-boost.follow-delivery-selection-cap:40}")
private int followFeedSelectionCap = 40;
```

```java
private List<com.tongji.recommendation.feed.TimelineItem> selectFollowDeliveries(
        List<com.tongji.recommendation.feed.TimelineItem> rawItems,
        Map<Long, PaidBoostCampaign> boosts,
        int pageSize
) {
    if (rawItems.size() <= pageSize || boosts.isEmpty()) {
        return rawItems.size() <= pageSize ? rawItems : rawItems.subList(0, pageSize);
    }
    List<com.tongji.recommendation.feed.TimelineItem> ranked = new ArrayList<>(rawItems);
    ranked.sort(Comparator
            .comparingLong((com.tongji.recommendation.feed.TimelineItem item) -> boostValue(boosts.get(item.contentId()))).reversed()
            .thenComparing(com.tongji.recommendation.feed.TimelineItem::publishTs, Comparator.reverseOrder())
            .thenComparingLong(com.tongji.recommendation.feed.TimelineItem::contentId).reversed());
    List<com.tongji.recommendation.feed.TimelineItem> selected = new ArrayList<>(ranked.subList(0, pageSize));
    selected.sort(Comparator
            .comparing(com.tongji.recommendation.feed.TimelineItem::publishTs, Comparator.reverseOrder())
            .thenComparingLong(com.tongji.recommendation.feed.TimelineItem::contentId).reversed());
    return selected;
}
```

controller 里的主循环改成：

```java
int fetchLimit = boosts.isEmpty() ? FOLLOW_FEED_SIZE : Math.max(FOLLOW_FEED_SIZE, followFeedSelectionCap);
TimelinePage timelinePage = followFeedService.getTimeline(userId, currentCursor, fetchLimit);
List<com.tongji.recommendation.feed.TimelineItem> chosen = selectFollowDeliveries(
        timelinePage.items(),
        boostsByPostId,
        Math.min(FOLLOW_FEED_SIZE - items.size(), timelinePage.items().size())
);
```

注意：

- `FollowFeedServiceImpl` 必须先支持 `limit > 20` 的入参，不需要自己理解 boost，但要把当前三处写死的 `20` 一起改掉：
  - `SOURCE_SLICE_LIMIT` 从常量改成 `maxSourceSliceLimit` 配置，默认 `100`
  - `safeLimit = Math.max(1, Math.min(limit, maxSourceSliceLimit))`
  - `readInbox(...)` / `readAuthorHead(...)` / `loadAuthorFeed(...)` 的 Cassandra 查询绑定值改成传入的 `safeLimit`，不能继续绑定常量 `20`
- `timelineCache` 只有 `safeLimit == SOURCE_SLICE_LIMIT` 才命中；这里请求更大 limit 会自然旁路缓存，不会污染默认 20 条缓存。
- `nextCursor` 必须基于 `chosen` 里最后一个 raw item 计算，不能再基于原始 `hydrated.get(need - 1)` 假设。

- [ ] **Step 4A: 先补 service 失败测试，证明 follow service 真能多取**

```java
@Test
void serviceSupportsLimitGreaterThanTwenty() {
    ReflectionTestUtils.setField(service, "maxSourceSliceLimit", 100);
    when(inboxRead.bind(anyLong(), eq(40))).thenReturn(inboxReadBound);
    when(valueOperations.get("feed:timeline:42")).thenReturn(null);
    when(relationService.listFollowedLargeAuthorRowsForFeed(42L, null, null, 100)).thenReturn(List.of());
    when(cqlSession.execute(any(BoundStatement.class))).thenReturn(inboxResultSet);
    when(inboxResultSet.all()).thenReturn(List.of());

    service.getTimeline(42L, null, 40);

    verify(inboxRead).bind(42L, 40);
}
```

- [ ] **Step 4B: 再补 controller 失败测试，证明 boost 选择发生在 controller**

Run: `mvn -Dtest=FollowFeedServiceTest#serviceSupportsLimitGreaterThanTwenty,KnowPostControllerPublishTest#followFeedPrefersBoostedItemsWithinSelectionWindow test`
Expected: FAIL。

- [ ] **Step 5: 在 controller hydration 后补商业标记**

```java
Map<Long, PaidBoostCampaign> boosts = paidBoostCacheService.getActive(PaidBoostChannel.FOLLOW_DELIVERY, Instant.now())
        .stream()
        .collect(Collectors.toMap(PaidBoostCampaign::getPostId, Function.identity(), (left, right) -> left));
for (FeedItemResponse item : hydrated) {
    PaidBoostCampaign campaign = boosts.get(Long.parseLong(item.id()));
    items.add(campaign == null ? item : item.withPromotionMetadata(
            "follow_delivery_boost",
            String.valueOf(campaign.getId()),
            null
    ));
}
```

- [ ] **Step 6: 运行测试，确认通过**

Run: `mvn -Dtest=FollowFeedServiceTest,KnowPostControllerPublishTest test`
Expected: PASS。

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/tongji/recommendation/feed/FollowFeedServiceImpl.java src/main/java/com/tongji/knowpost/api/KnowPostController.java src/test/java/com/tongji/recommendation/feed/FollowFeedServiceTest.java src/test/java/com/tongji/knowpost/api/KnowPostControllerPublishTest.java
git commit -m "feat: prioritize follow delivery with paid boost"
```

### Task 9: 记录 delivery 事实，不在读路径直接扣 wallet

**Files:**
- Create: `src/main/java/com/tongji/promotion/service/PaidBoostDeliveryService.java`
- Test: `src/test/java/com/tongji/promotion/service/PaidBoostCampaignServiceTest.java`
- Test: `src/test/java/com/tongji/recommendation/HomeFeedMixingServiceTest.java`

- [ ] **Step 1: 写失败的 delivery 聚合测试**

```java
@Test
void recordsDeliveryIntoBucketInsteadOfImmediateWalletCapture() {
    service.recordDeliveries(
            PaidBoostChannel.HOME_RECOMMENDATION,
            42L,
            List.of(campaign(1L, PaidBoostChannel.HOME_RECOMMENDATION, 30L, 2L, 100L, 0L))
    );
    verify(walletService, never()).captureHoldToPlatform(anyLong(), anyLong(), any(), anyString());
    verify(deliveryMapper).upsertPending(any());
}
```

```java
@Test
void repeatedDeliveryInSameBucketAggregatesIntoSameBillableFact() {
    when(clock.instant()).thenReturn(
            Instant.parse("2026-06-21T10:00:05Z"),
            Instant.parse("2026-06-21T10:00:25Z")
    );
    PaidBoostCampaign campaign = campaign(1L, PaidBoostChannel.HOME_RECOMMENDATION, 30L, 2L, 100L, 0L);

    service.recordDeliveries(PaidBoostChannel.HOME_RECOMMENDATION, 42L, List.of(campaign));
    service.recordDeliveries(PaidBoostChannel.HOME_RECOMMENDATION, 42L, List.of(campaign));

    verify(walletService, never()).captureHoldToPlatform(anyLong(), anyLong(), any(), anyString());
    verify(deliveryMapper, times(2)).upsertPending(argThat(d ->
            d.getCampaignId() == 1L &&
            d.getViewerUserId() == 42L &&
            d.getSettleBusinessRef().equals("paid-boost:1:spend:202606211000:42")
    ));
}
```

- [ ] **Step 2: 运行测试，确认当前失败**

Run: `mvn -Dtest=PaidBoostCampaignServiceTest#recordsDeliveryIntoBucketInsteadOfImmediateWalletCapture test`
Expected: FAIL。

- [ ] **Step 3: 最小实现**

```java
@Transactional
public void recordDeliveries(PaidBoostChannel channel, Long viewerUserId, List<PaidBoostCampaign> deliveredCampaigns) {
    Instant bucketStart = alignBucket(clock.instant());
    for (PaidBoostCampaign campaign : deliveredCampaigns) {
        long remaining = campaign.getBudgetTotal() - campaign.getBudgetConsumed();
        if (remaining <= 0) {
            continue;
        }
        PaidBoostDelivery delivery = PaidBoostDelivery.builder()
                .id(idService.nextId(IdNamespace.ADMIN_OPERATION))
                .campaignId(campaign.getId())
                .channel(channel)
                .postId(campaign.getPostId())
                .viewerUserId(viewerUserId)
                .deliveryBucketStartAt(bucketStart)
                .deliveryCount(1)
                .unitPriceSnapshot(campaign.getUnitPrice())
                .capturedAmount(0L)
                .settleBusinessRef(settleRef(campaign.getId(), bucketStart, viewerUserId))
                .status(PaidBoostDeliveryStatus.PENDING)
                .createdAt(clock.instant())
                .updatedAt(clock.instant())
                .build();
        deliveryMapper.upsertPending(delivery);
    }
}
```

`deliveryMapper.upsertPending(...)` 必须依赖唯一键 `(campaign_id, delivery_bucket_start_at, viewer_user_id)` 做聚合：同 bucket 重复返回同一活动内容只增加 `delivery_count`，不新增第二条 billable fact。

- [ ] **Step 4: 在 home/follow 读路径接入 recordDeliveries**

```java
paidBoostDeliveryService.recordDeliveries(
        PaidBoostChannel.HOME_RECOMMENDATION,
        userId,
        deliveredHomeBoostCampaigns
);
```

本 change 只在登录态 `home feed` 与 `follow feed` 记录 delivery；匿名首页不记账。

- [ ] **Step 5: 运行测试，确认通过**

Run: `mvn -Dtest=PaidBoostCampaignServiceTest,HomeFeedMixingServiceTest test`
Expected: PASS。

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/tongji/promotion/service/PaidBoostDeliveryService.java src/main/java/com/tongji/recommendation/HomeFeedMixingService.java src/main/java/com/tongji/knowpost/api/KnowPostController.java src/test/java/com/tongji/promotion/service/PaidBoostCampaignServiceTest.java src/test/java/com/tongji/recommendation/HomeFeedMixingServiceTest.java
git commit -m "feat: record paid boost delivery facts"
```

### Task 10: 结算 pending delivery 并关闭活动释放剩余预算

**Files:**
- Create: `src/main/java/com/tongji/promotion/service/PaidBoostSettlementService.java`
- Create: `src/main/java/com/tongji/promotion/schedule/PaidBoostScheduler.java`
- Modify: `src/main/java/com/tongji/wallet/model/WalletLedgerReason.java`
- Modify: `src/main/java/com/tongji/wallet/service/WalletService.java`
- Test: `src/test/java/com/tongji/promotion/service/PaidBoostSettlementServiceTest.java`
- Test: `src/test/java/com/tongji/promotion/schedule/PaidBoostSchedulerTest.java`

- [ ] **Step 1: 写失败的结算测试**

```java
@Test
void settlesPendingDeliveryByCapturingHeldBudget() {
    PaidBoostDelivery delivery = pendingDelivery(1L, 2L, "paid-boost:1:spend:202606211000:77");
    PaidBoostCampaign campaign = campaign(1L, PaidBoostChannel.HOME_RECOMMENDATION, 30L, 2L, 100L, 0L);
    when(deliveryMapper.listPendingBefore(any(), eq(100))).thenReturn(List.of(delivery));
    when(campaignMapper.findById(1L)).thenReturn(campaign);

    service.settlePendingDeliveries(Instant.parse("2026-06-21T10:05:00Z"), 100);

    verify(walletService).captureHoldToPlatform(
            campaign.getCreatorUserId(),
            2L,
            WalletLedgerReason.PAID_BOOST_CAPTURE,
            WalletBusinessType.PROMOTION,
            "paid-boost:1:spend:202606211000:77"
    );
    verify(campaignMapper).increaseBudgetConsumed(1L, 2L, Instant.parse("2026-06-21T10:05:00Z"));
    verify(deliveryMapper).markSettledWithAmount(delivery.getId(), 2L, Instant.parse("2026-06-21T10:05:00Z"));
}
```

- [ ] **Step 2: 运行测试，确认当前失败**

Run: `mvn -Dtest=PaidBoostSettlementServiceTest#settlesPendingDeliveryByCapturingHeldBudget test`
Expected: FAIL。

- [ ] **Step 3: 最小实现**

给 `WalletLedgerReason` 加：

```java
PAID_BOOST_CAPTURE,
PAID_BOOST_RELEASE
```

并给 `WalletService` 补一个最小 capture 重载，避免把 paid boost 记成 `PROMOTION_BID_CAPTURE`：

```java
@Transactional(isolation = Isolation.READ_COMMITTED)
public WalletLedgerEntry captureHoldToPlatform(long ownerUserId, long amount,
                                               WalletLedgerReason reason,
                                               WalletBusinessType businessType, String businessRef) {
    return apply(ownerUserId, amount, reason, businessType,
            WalletLedgerDirection.DEBIT, walletProperties.getPlatformUserId(),
            null, 0L, -amount, 0L, businessRef);
}
```

`PaidBoostSettlementService`：

```java
@Transactional
public void settlePendingDeliveries(Instant now, int batchSize) {
    for (PaidBoostDelivery delivery : deliveryMapper.listPendingBefore(now, batchSize)) {
        PaidBoostCampaign campaign = campaignMapper.findById(delivery.getCampaignId());
        if (campaign == null || campaign.getStatus() != PaidBoostCampaignStatus.ACTIVE) {
            continue;
        }
        long remaining = campaign.getBudgetTotal() - campaign.getBudgetConsumed();
        long planned = delivery.getDeliveryCount() * delivery.getUnitPriceSnapshot();
        long captured = Math.min(remaining, planned);
        if (captured <= 0) {
            deliveryMapper.markSettledWithAmount(delivery.getId(), 0L, now);
            continue;
        }
        walletService.captureHoldToPlatform(campaign.getCreatorUserId(), captured,
                WalletLedgerReason.PAID_BOOST_CAPTURE, WalletBusinessType.PROMOTION, delivery.getSettleBusinessRef());
        campaignMapper.increaseBudgetConsumed(campaign.getId(), captured, now);
        deliveryMapper.markSettledWithAmount(delivery.getId(), captured, now);
    }
}
```

- [ ] **Step 4: 写失败的关闭释放测试**

```java
@Test
void closesExpiredCampaignAndReleasesRemainingBudget() {
    PaidBoostCampaign campaign = campaign(1L, PaidBoostChannel.HOME_RECOMMENDATION, 30L, 2L, 100L, 40L);
    when(campaignMapper.listClosable(now, 100)).thenReturn(List.of(campaign));

    service.closeExpiredCampaigns(now, 100);

    verify(walletService).releaseHold(
            campaign.getCreatorUserId(),
            60L,
            WalletLedgerReason.PAID_BOOST_RELEASE,
            WalletBusinessType.PROMOTION,
            "paid-boost:1:release"
    );
    verify(campaignMapper).markClosed(1L, now, now);
}
```

- [ ] **Step 5: 实现 scheduler**

```java
@Scheduled(fixedDelayString = "${promotion.paid-boost.settle-delay-ms:30000}")
public void settlePending() {
    Instant now = Instant.now();
    settlementService.settlePendingDeliveries(now, 100);
    settlementService.closeExpiredCampaigns(now, 100);
    cacheService.refreshAll(now);
}
```

- [ ] **Step 6: 运行测试，确认通过**

Run: `mvn -Dtest=PaidBoostSettlementServiceTest,PaidBoostSchedulerTest test`
Expected: PASS。

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/tongji/promotion/service/PaidBoostSettlementService.java src/main/java/com/tongji/promotion/schedule/PaidBoostScheduler.java src/main/java/com/tongji/wallet/model/WalletLedgerReason.java src/test/java/com/tongji/promotion/service/PaidBoostSettlementServiceTest.java src/test/java/com/tongji/promotion/schedule/PaidBoostSchedulerTest.java
git commit -m "feat: settle and close paid boost campaigns"
```

### Task 11: 补 API 查询、文档与术语

**Files:**
- Modify: `CONTEXT.md`
- Modify: `src/main/java/com/tongji/promotion/api/PaidBoostController.java`
- Create: `src/main/java/com/tongji/promotion/api/dto/PaidBoostDeliverySummaryResponse.java`
- Test: `src/test/java/com/tongji/promotion/api/PaidBoostControllerTest.java`

- [ ] **Step 1: 写失败的 controller 查询测试**

```java
@Test
void getCampaignReturnsBudgetProgressWithoutAuctionFields() {
    when(campaignService.getCampaign(1L)).thenReturn(campaign());
    PaidBoostCampaignResponse response = controller.getCampaign(1L);
    assertThat(response.budgetConsumed()).isEqualTo(40L);
    assertThat(response.channel()).isEqualTo("home_recommendation");
}
```

- [ ] **Step 2: 运行测试，确认当前失败**

Run: `mvn -Dtest=PaidBoostControllerTest#getCampaignReturnsBudgetProgressWithoutAuctionFields test`
Expected: FAIL。

- [ ] **Step 3: 最小实现**

`CONTEXT.md` 增补一条 glossary（若当前定义已够，不新增重复条目，只补“投放活动/投放事实”两个词）：

```md
- **boost 活动（paid boost campaign）** — 创作者针对推荐排序或关注触达开启的非拍卖推广投放，包含 boost 值、预算、投放窗口与预算消耗进度。
- **boost 投放事实（paid boost delivery）** — 内容一次被 boost 规则实际送达后的可结算记录；用于后续聚合扣费，不等于拍卖赢家。
```

- [ ] **Step 4: 运行测试，确认通过**

Run: `mvn -Dtest=PaidBoostControllerTest test`
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add CONTEXT.md src/main/java/com/tongji/promotion/api src/test/java/com/tongji/promotion/api/PaidBoostControllerTest.java
git commit -m "docs: document paid boost terms and query api"
```

### Task 12: 全量验证与 OpenSpec 回填

**Files:**
- Modify: `openspec/changes/add-paid-boost-promotions/tasks.md`

- [ ] **Step 1: 跑核心测试集**

Run: `mvn -Dtest=PaidBoostMysqlIntegrationTest,PaidBoostCampaignServiceTest,PaidBoostRankingServiceTest,PaidBoostSettlementServiceTest,PaidBoostControllerTest,HomeFeedMixingServiceTest,FollowFeedServiceTest test`
Expected: 全 PASS。

- [ ] **Step 2: 跑 promotion + recommendation 相关回归**

Run: `mvn -Dtest=PromotionControllerTest,SearchServiceImplTest test`
Expected: 全 PASS，证明 slot auction / search 未被 paid boost 污染。

- [ ] **Step 3: 回填 OpenSpec tasks**

把 `openspec/changes/add-paid-boost-promotions/tasks.md` 中已完成项逐个打勾；若实现与原 task 命名有偏差，补一行简短说明，不改 capability 语义。

- [ ] **Step 4: 提交收尾**

```bash
git add openspec/changes/add-paid-boost-promotions/tasks.md
git commit -m "chore: mark paid boost change tasks complete"
```

## 自查

### Spec coverage

- `non-auction campaigns`：Task 1 / Task 3，独立表与 API，不产 winner/GSP。
- `reserve and settle budget`：Task 3 / Task 9 / Task 10，冻结、聚合结算、关闭释放。
- `recommendation ranking through local weighting`：Task 5 / Task 6 / Task 7。
- `follow priority only under constrained delivery`：Task 8，限定在 `KnowPostController.followFeed(...)` 的受限选择阶段。
- `commercially marked output`：Task 7 / Task 8，通过通用 metadata helper 标记。

### Placeholder scan

- 计费规则已定为“每次最终返回计一次，按 unit price 扣费；同 bucket 重复刷新不重复新增 billable fact”，未留 TBD。
- follow 受限点已钉在 `KnowPostController.followFeed(...)` 选择阶段，`FollowFeedServiceImpl` 仅负责多取 raw timeline。
- 定时结算/关闭/缓存刷新均有具体类与命令。

### Type consistency

- 渠道统一为 `PaidBoostChannel`，对外小写值 `home_recommendation` / `follow_delivery`。
- commercial placement 统一为 `home_recommendation_boost` / `follow_delivery_boost`。
- 写接口入参统一为 `bidAmount`，持久化同时保留 `bidAmount` 与 `boostValue`（effective boost）。
- wallet business ref 前缀统一 `paid-boost:{campaignId}:...`。

Plan complete and saved to `docs/superpowers/plans/2026-06-21-add-paid-boost-promotions.md`. Two execution options:

1. Subagent-Driven (recommended) - 我分 task 派新 subagent 执行，中间穿插 review  
2. Inline Execution - 在本 session 直接按计划批量做
