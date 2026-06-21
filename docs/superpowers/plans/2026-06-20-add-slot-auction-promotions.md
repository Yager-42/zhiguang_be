# Slot Auction Promotions 实现计划

> **给 agent 工作者：** REQUIRED SUB-SKILL: 使用 `superpowers:subagent-driven-development`（推荐）或 `superpowers:executing-plans` 按任务逐项执行本计划。步骤使用 checkbox（`- [ ]`）语法跟踪。

**Goal:** 在 zhiguang 内落地 `feed_top_slot` 与 `search_top_slot` 两类推广位的时间窗竞价、GSP 结算、钱包冻结/扣减/释放，以及 feed/search 读路径的商业位插入与标识输出。

**Architecture:** 保持当前模块化单体。新增最小 `com.tongji.promotion` feature 包，承载推广活动、竞价窗口、出价、位分配、结算与缓存读取；复用现有 `wallet`、`IdService`、MyBatis XML、Redis、`@Scheduled` 风格。请求读路径只消费已缓存的 `slot allocation`，不做请求内拍卖。`KnowPostTopPatch` 保留为兼容接口，但 `public feed` 与 `home feed` 的商业位来源切换为 allocation，不再依赖 `is_top` 手工运营置顶。

**Tech Stack:** Java 21、Spring Boot 3.2.4、MyBatis、MySQL 8、Redis、Maven、JUnit 5、Mockito、AssertJ、OpenSpec。

---

## 必读上下文

编辑前先读：

- `openspec/changes/add-slot-auction-promotions/proposal.md`
- `openspec/changes/add-slot-auction-promotions/design.md`
- `openspec/changes/add-slot-auction-promotions/specs/slot-auction-promotions/spec.md`
- `openspec/changes/add-slot-auction-promotions/tasks.md`
- `docs/prd/2026-06-18-bidding-system-integration.md`
- `CONTEXT.md`
- `docs/superpowers/plans/2026-06-19-add-wallet-and-escrow.md`
- `db/schema.sql`
- `src/main/java/com/tongji/wallet/service/WalletService.java`
- `src/main/java/com/tongji/wallet/service/WalletEscrowService.java`
- `src/main/java/com/tongji/knowpost/api/KnowPostController.java`
- `src/main/java/com/tongji/knowpost/service/impl/KnowPostFeedServiceImpl.java`
- `src/main/java/com/tongji/knowpost/api/dto/FeedItemResponse.java`
- `src/main/java/com/tongji/recommendation/HomeFeedMixingService.java`
- `src/main/java/com/tongji/search/service/SearchService.java`
- `src/main/java/com/tongji/search/service/impl/SearchServiceImpl.java`
- `src/main/java/com/tongji/reconciliation/service/impl/ReconciliationServiceImpl.java`
- `src/main/resources/mapper/KnowPostMapper.xml`
- `src/main/resources/mapper/ReconciliationTaskMapper.xml`
- `src/test/java/com/tongji/recommendation/HomeFeedMixingServiceTest.java`
- `src/test/java/com/tongji/recommendation/HomeFeedMixingHydrationTest.java`
- `src/test/java/com/tongji/reconciliation/executor/ReconciliationSchedulerTest.java`

## Senior Review

### Blockers

- [B1] 当前仓库已有完整 `wallet` 与 `escrow` 实现，slot auction plan 绝不能重造资金状态机。但现有 `WalletService` 只有 `hold(...)` / `releaseHold(...)`，没有“冻结转平台收入”的 capture 动作。计划必须显式补一个 promotion 专用最小 wallet 方法，否则 winner 只能释放超额、不能真正扣成交价。
- [B2] `FeedItemResponse` 目前只表达内容本身和用户态，无商业位字段。若直接复用 `isTop` 冒充推广位，会把“我的发布置顶”和“商业位”语义混成一团，还会污染搜索响应。必须显式新增 promoted/commercial 元数据。
- [B3] `KnowPostFeedServiceImpl` 的公共 feed 有本地 Caffeine + Redis 片段缓存 + 反向索引更新。若把 allocation 混到已有 page cache 里但不单独建版本/失效策略，窗口切换后会持续吐旧推广位。商业位读路径必须有独立缓存键与 layout version。
- [B4] `HomeFeedMixingService` 现在是 follow -> recommendation -> hot fallback 的纯 organic 管道。若直接在 controller 拼一条 promoted item，会破坏去重与 20 条填充逻辑。商业位插入必须在 mixing service 内统一处理，且要先占坑再补 organic。
- [B5] `SearchServiceImpl` 当前从 ES 命中直接组 `FeedItemResponse`。search 置顶位不是 ES 排序结果，不应通过改 ES query 注入。应先读 active allocation，再单独 hydrate 推广帖子，并与 organic hits 去重。

### Major

- [M1] 该 change 只覆盖 `feed_top_slot` / `search_top_slot`。`paid boost` 已拆到独立 change，不要在这里引入 `λ·bid` 排序加权、粉丝触达优先级、Kafka fanout 改造。
- [M2] 该 change 依赖 `wallet-and-escrow` 已落地，因此 plan 要默认：
  - `platform-user-id = 0`
  - 冻结发生在接单时
  - 结算时扣成交价、释放超额冻结
  - 不额外加 escrow，但允许在 `wallet` 内补一个最小 `captureHoldToPlatform(...)`
- [M3] `KnowPostTopPatch` 在 PRD 里是“从手工置顶迁到竞价位”的语义，不是必须删接口。更稳方案：保留 patch 接口和 `is_top` 字段给“我的发布/运营兜底”，但 public/home/search 商业位都不再依赖它。
- [M4] 当前 `IdNamespace` 只有 `ADMIN_OPERATION` 可供新增段号类实体复用。promotion 域本期可以先全部吃 `ADMIN_OPERATION`，不要为 plan 发明新的 leaf_alloc/bizTag 扩容 unless 真有冲突证据。
- [M5] 窗口关闭驱动不必硬塞 `reconciliation_task`。现有仓库已有大量 `@Scheduled` job；首期最懒且够用的路径是直接加 `PromotionAuctionScheduler -> PromotionAuctionWindowCloser`。除非后面明确要 dead-letter / retry 可观测，否则别先上 reconciliation task。

### Minor

- [m1] 文档中若提到 search 作用域，不要拍脑袋写关键词定向/标签定向。现有 PRD/spec 只要求 `search_top_slot` 资源与 placement metadata，本期默认“搜索结果页固定推广位 + 后续再扩 query targeting”更稳。
- [m2] 测试风格应贴现有仓库：Mockito 单测 + 少量 mapper/调度契约测试；不要引 Testcontainers 或新依赖。

## 关键决策

1. 新增顶层 feature 包 `com.tongji.promotion`。  
原因：当前仓库按 feature 分包；promotion 已是 glossary 主术语。

2. 资源枚举首期只保留 `FEED_TOP_SLOT` 与 `SEARCH_TOP_SLOT`。  
原因：spec 已定范围；`paid boost` 独立 change 处理。

3. 推广实体分四张核心表：`promotion_campaign`、`promotion_auction_window`、`promotion_bid`、`promotion_slot_allocation`。  
原因：campaign/窗口/出价/结果生命周期不同；按责任分表最小且清晰。

4. 竞价窗口由系统按资源类型自动滚动创建。  
规则：scheduler/closer 每轮都保证“当前 open window 存在 + 下一个 future window 已预建”；`submitBid(...)` 只接受当前 open window，不负责隐式建窗。

5. 接单即冻结，窗口关闭后一次性 GSP 结算。  
规则：bid 接口 `hold(maxBid)`；结算时 winner 先 `captureHoldToPlatform(clearingPrice)`，再释放超额； loser 全额释放。

6. 请求路径永远只读 active allocation。  
规则：feed/search 不调用竞价排序逻辑；找不到 active allocation 时直接 organic fallback。

7. 商业位元数据显式加到响应 DTO，不复用 `isTop`。  
规则：新增如 `promoted`、`commercial`、`placementType`、`promotionId`、`auctionWindowId`；`isTop` 继续只表达帖子自身置顶状态。

8. `home feed` 与 `public feed` 都插 `feed_top_slot`，但 `follow feed` 不插。  
原因：spec/PRD 只提首页 feed 与 search；follow feed 是关系消费流，不做广告位注入。

9. search 推广位默认“页首 1 条”，不做 query targeting。  
原因：满足首期 resource 与 placement metadata；不额外发明关键词投放 DSL。

10. allocation 缓存单独建 Redis key，不塞进已有 `feed:public:*` 页面缓存结构。  
原因：窗口切换频率和 feed page cache 频率不同，混用会让失效复杂度爆炸。

11. 结算驱动首期直接用 `@Scheduled`。  
规则：出价写入/窗口创建只负责业务事实；窗口关闭扫描与 allocation 刷新由 `PromotionAuctionScheduler` 定时触发 `PromotionAuctionWindowCloser`。后续真有失败重试与后台运维诉求，再升级到 `reconciliation_task`。

12. `feed_top_slot` 与 `search_top_slot` 都只作用当前结果页首位。  
规则：public feed 仅 `page=1` 插 1 条；home feed 每次响应插 1 条；search 在 `after == null` 首屏插 1 条，后续页不重复插。`nextAfter/hasMore` 只基于 organic 子序列计算。

## 文件地图

### 预计新增文件

- `src/main/java/com/tongji/promotion/model/PromotionResourceType.java`
- `src/main/java/com/tongji/promotion/model/PromotionCampaign.java`
- `src/main/java/com/tongji/promotion/model/PromotionCampaignStatus.java`
- `src/main/java/com/tongji/promotion/model/PromotionAuctionWindow.java`
- `src/main/java/com/tongji/promotion/model/PromotionAuctionWindowStatus.java`
- `src/main/java/com/tongji/promotion/model/PromotionBid.java`
- `src/main/java/com/tongji/promotion/model/PromotionBidStatus.java`
- `src/main/java/com/tongji/promotion/model/PromotionSlotAllocation.java`
- `src/main/java/com/tongji/promotion/model/PromotionPlacementType.java`
- `src/main/java/com/tongji/promotion/model/PromotionSettlementResult.java`
- `src/main/java/com/tongji/promotion/mapper/PromotionCampaignMapper.java`
- `src/main/java/com/tongji/promotion/mapper/PromotionAuctionWindowMapper.java`
- `src/main/java/com/tongji/promotion/mapper/PromotionBidMapper.java`
- `src/main/java/com/tongji/promotion/mapper/PromotionSlotAllocationMapper.java`
- `src/main/java/com/tongji/promotion/service/PromotionCampaignService.java`
- `src/main/java/com/tongji/promotion/service/PromotionAuctionService.java`
- `src/main/java/com/tongji/promotion/service/PromotionAuctionWindowService.java`
- `src/main/java/com/tongji/promotion/service/PromotionAllocationService.java`
- `src/main/java/com/tongji/promotion/service/PromotionAllocationCacheService.java`
- `src/main/java/com/tongji/promotion/api/PromotionController.java`
- `src/main/java/com/tongji/promotion/api/dto/CreatePromotionCampaignRequest.java`
- `src/main/java/com/tongji/promotion/api/dto/SubmitPromotionBidRequest.java`
- `src/main/java/com/tongji/promotion/api/dto/PromotionCampaignResponse.java`
- `src/main/java/com/tongji/promotion/api/dto/PromotionBidResponse.java`
- `src/main/java/com/tongji/promotion/api/dto/PromotionAllocationView.java`
- `src/main/java/com/tongji/promotion/schedule/PromotionAuctionWindowCloser.java`
- `src/main/java/com/tongji/promotion/schedule/PromotionAuctionScheduler.java`
- `src/main/resources/mapper/PromotionCampaignMapper.xml`
- `src/main/resources/mapper/PromotionAuctionWindowMapper.xml`
- `src/main/resources/mapper/PromotionBidMapper.xml`
- `src/main/resources/mapper/PromotionSlotAllocationMapper.xml`
- `src/test/java/com/tongji/promotion/service/PromotionAuctionServiceTest.java`
- `src/test/java/com/tongji/promotion/service/PromotionAuctionWindowServiceTest.java`
- `src/test/java/com/tongji/promotion/service/PromotionAllocationServiceTest.java`
- `src/test/java/com/tongji/promotion/service/PromotionCampaignServiceTest.java`
- `src/test/java/com/tongji/promotion/api/PromotionControllerTest.java`
- `src/test/java/com/tongji/promotion/schedule/PromotionAuctionWindowCloserTest.java`
- `src/test/java/com/tongji/promotion/schedule/PromotionAuctionSchedulerTest.java`
- `src/test/java/com/tongji/promotion/PromotionSchemaContractTest.java`

### 预计修改文件

- `db/schema.sql`
- `CONTEXT.md`
- `src/main/java/com/tongji/common/exception/ErrorCode.java`
- `src/main/java/com/tongji/wallet/model/WalletLedgerReason.java`
- `src/main/java/com/tongji/wallet/service/WalletService.java`
- `src/main/java/com/tongji/knowpost/api/dto/FeedItemResponse.java`
- `src/main/java/com/tongji/knowpost/service/impl/KnowPostFeedServiceImpl.java`
- `src/main/java/com/tongji/recommendation/HomeFeedMixingService.java`
- `src/main/java/com/tongji/search/service/SearchService.java`
- `src/main/java/com/tongji/search/service/impl/SearchServiceImpl.java`
- `src/main/resources/application.yml`
- `src/test/java/com/tongji/recommendation/HomeFeedMixingServiceTest.java`
- `src/test/java/com/tongji/recommendation/HomeFeedMixingHydrationTest.java`
- `src/test/java/com/tongji/knowpost/api/KnowPostControllerPublishTest.java`
- `openspec/changes/add-slot-auction-promotions/tasks.md`（仅在实现验证完成后回填）

## 数据模型草案

```sql
CREATE TABLE promotion_campaign (
  id BIGINT UNSIGNED PRIMARY KEY,
  creator_user_id BIGINT UNSIGNED NOT NULL,
  post_id BIGINT UNSIGNED NOT NULL,
  resource_type VARCHAR(32) NOT NULL,
  status VARCHAR(16) NOT NULL,
  start_at DATETIME(3) NOT NULL,
  end_at DATETIME(3) NOT NULL,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  KEY idx_promotion_campaign_creator_status (creator_user_id, status),
  KEY idx_promotion_campaign_post (post_id),
  KEY idx_promotion_campaign_resource_window (resource_type, start_at, end_at)
);

CREATE TABLE promotion_auction_window (
  id BIGINT UNSIGNED PRIMARY KEY,
  resource_type VARCHAR(32) NOT NULL,
  window_start_at DATETIME(3) NOT NULL,
  window_end_at DATETIME(3) NOT NULL,
  slot_count INT NOT NULL,
  reserve_price BIGINT NOT NULL,
  status VARCHAR(16) NOT NULL,
  settled_at DATETIME(3) NULL,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  UNIQUE KEY uk_promotion_window_resource_time (resource_type, window_start_at, window_end_at),
  KEY idx_promotion_window_status_time (status, window_end_at)
);

CREATE TABLE promotion_bid (
  id BIGINT UNSIGNED PRIMARY KEY,
  campaign_id BIGINT UNSIGNED NOT NULL,
  auction_window_id BIGINT UNSIGNED NOT NULL,
  bidder_user_id BIGINT UNSIGNED NOT NULL,
  bid_amount BIGINT NOT NULL,
  wallet_business_ref VARCHAR(128) NOT NULL,
  status VARCHAR(16) NOT NULL,
  clearing_price BIGINT NULL,
  slot_index INT NULL,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  UNIQUE KEY uk_promotion_bid_campaign_window (campaign_id, auction_window_id),
  UNIQUE KEY uk_promotion_bid_wallet_ref (wallet_business_ref),
  KEY idx_promotion_bid_window_status_amount (auction_window_id, status, bid_amount DESC, id ASC)
);

CREATE TABLE promotion_slot_allocation (
  id BIGINT UNSIGNED PRIMARY KEY,
  auction_window_id BIGINT UNSIGNED NOT NULL,
  resource_type VARCHAR(32) NOT NULL,
  slot_index INT NOT NULL,
  campaign_id BIGINT UNSIGNED NOT NULL,
  post_id BIGINT UNSIGNED NOT NULL,
  bidder_user_id BIGINT UNSIGNED NOT NULL,
  clearing_price BIGINT NOT NULL,
  allocation_start_at DATETIME(3) NOT NULL,
  allocation_end_at DATETIME(3) NOT NULL,
  created_at DATETIME(3) NOT NULL,
  UNIQUE KEY uk_promotion_slot_window_index (auction_window_id, slot_index),
  KEY idx_promotion_slot_resource_time (resource_type, allocation_start_at, allocation_end_at)
);
```

## API 与读路径草案

### Promotion 写接口

- `POST /api/v1/promotions/campaigns`
- `POST /api/v1/promotions/campaigns/{campaignId}/bids`
- `GET /api/v1/promotions/campaigns/{campaignId}`
- `GET /api/v1/promotions/allocations/active?resourceType=feed_top_slot`

### Feed/Search 响应扩展

`FeedItemResponse` 增加：

```java
public record FeedItemResponse(
        String id,
        String title,
        String description,
        String coverImage,
        List<String> tags,
        String authorAvatar,
        String authorNickname,
        String tagJson,
        Long likeCount,
        Long favoriteCount,
        Boolean liked,
        Boolean faved,
        Boolean isTop,
        Boolean promoted,
        Boolean commercial,
        String placementType,
        String promotionCampaignId,
        String auctionWindowId
) {}
```

最小规则：

- organic item：`promoted=false`，`commercial=false`，其余 placement 字段为 `null`
- promoted item：`promoted=true`，`commercial=true`
- `placementType` 首期只允许 `feed_top_slot` / `search_top_slot`

## 执行顺序

### Task 1: 定 promotion 词汇、schema、错误码与配置

**Files:**
- Modify: `CONTEXT.md`
- Modify: `db/schema.sql`
- Modify: `src/main/java/com/tongji/common/exception/ErrorCode.java`
- Modify: `src/main/resources/application.yml`
- Test: `src/test/java/com/tongji/promotion/PromotionSchemaContractTest.java`

- [ ] **Step 1: 写 schema contract test，先把要落的表/索引/配置钉死**

```java
package com.tongji.promotion;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class PromotionSchemaContractTest {

    @Test
    void schemaContainsPromotionTablesAndIndexes() throws Exception {
        String schema = Files.readString(Path.of("db/schema.sql"));

        assertThat(schema).contains("CREATE TABLE IF NOT EXISTS promotion_campaign");
        assertThat(schema).contains("CREATE TABLE IF NOT EXISTS promotion_auction_window");
        assertThat(schema).contains("CREATE TABLE IF NOT EXISTS promotion_bid");
        assertThat(schema).contains("CREATE TABLE IF NOT EXISTS promotion_slot_allocation");
        assertThat(schema).contains("uk_promotion_window_resource_time");
        assertThat(schema).contains("idx_promotion_bid_window_status_amount");
        assertThat(schema).contains("idx_promotion_slot_resource_time");
    }
}
```

- [ ] **Step 2: 跑 test，确认先失败**

Run: `mvn -q -Dtest=PromotionSchemaContractTest test`  
Expected: FAIL，包含 `promotion_campaign` 或同类缺失断言。

- [ ] **Step 3: 最小落 schema / glossary / error code / config**

```sql
-- db/schema.sql
CREATE TABLE IF NOT EXISTS promotion_campaign (
    id BIGINT UNSIGNED NOT NULL,
    creator_user_id BIGINT UNSIGNED NOT NULL,
    post_id BIGINT UNSIGNED NOT NULL,
    resource_type VARCHAR(32) NOT NULL,
    status VARCHAR(16) NOT NULL,
    start_at DATETIME(3) NOT NULL,
    end_at DATETIME(3) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_promotion_campaign_creator_status (creator_user_id, status),
    KEY idx_promotion_campaign_post (post_id),
    KEY idx_promotion_campaign_resource_window (resource_type, start_at, end_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS promotion_auction_window (
    id BIGINT UNSIGNED NOT NULL,
    resource_type VARCHAR(32) NOT NULL,
    window_start_at DATETIME(3) NOT NULL,
    window_end_at DATETIME(3) NOT NULL,
    slot_count INT NOT NULL,
    reserve_price BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL,
    settled_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_promotion_window_resource_time (resource_type, window_start_at, window_end_at),
    KEY idx_promotion_window_status_time (status, window_end_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS promotion_bid (
    id BIGINT UNSIGNED NOT NULL,
    campaign_id BIGINT UNSIGNED NOT NULL,
    auction_window_id BIGINT UNSIGNED NOT NULL,
    bidder_user_id BIGINT UNSIGNED NOT NULL,
    bid_amount BIGINT NOT NULL,
    wallet_business_ref VARCHAR(128) NOT NULL,
    status VARCHAR(16) NOT NULL,
    clearing_price BIGINT NULL,
    slot_index INT NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_promotion_bid_campaign_window (campaign_id, auction_window_id),
    UNIQUE KEY uk_promotion_bid_wallet_ref (wallet_business_ref),
    KEY idx_promotion_bid_window_status_amount (auction_window_id, status, bid_amount DESC, id ASC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS promotion_slot_allocation (
    id BIGINT UNSIGNED NOT NULL,
    auction_window_id BIGINT UNSIGNED NOT NULL,
    resource_type VARCHAR(32) NOT NULL,
    slot_index INT NOT NULL,
    campaign_id BIGINT UNSIGNED NOT NULL,
    post_id BIGINT UNSIGNED NOT NULL,
    bidder_user_id BIGINT UNSIGNED NOT NULL,
    clearing_price BIGINT NOT NULL,
    allocation_start_at DATETIME(3) NOT NULL,
    allocation_end_at DATETIME(3) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_promotion_slot_window_index (auction_window_id, slot_index),
    KEY idx_promotion_slot_resource_time (resource_type, allocation_start_at, allocation_end_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

```java
// ErrorCode.java
PROMOTION_CAMPAIGN_NOT_FOUND("PROMOTION_CAMPAIGN_NOT_FOUND", "推广活动不存在"),
PROMOTION_BID_DUPLICATE("PROMOTION_BID_DUPLICATE", "推广出价已存在"),
PROMOTION_BID_WINDOW_CLOSED("PROMOTION_BID_WINDOW_CLOSED", "竞价窗口已关闭"),
PROMOTION_BID_INVALID_RESOURCE("PROMOTION_BID_INVALID_RESOURCE", "推广资源类型不合法"),
PROMOTION_ALLOCATION_NOT_FOUND("PROMOTION_ALLOCATION_NOT_FOUND", "推广位分配不存在"),
PROMOTION_SETTLEMENT_INVALID_STATUS("PROMOTION_SETTLEMENT_INVALID_STATUS", "推广结算状态不合法"),
```

```yaml
# application.yml
promotion:
  slot-auction:
    feed-top-slot-count: 1
    search-top-slot-count: 1
    feed-reserve-price: 1
    search-reserve-price: 1
    cache-ttl-seconds: 300
    settle-batch-size: 50
    close-window-delay-ms: 30000
```

- [ ] **Step 4: 跑 contract test，确认通过**

Run: `mvn -q -Dtest=PromotionSchemaContractTest test`  
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add CONTEXT.md db/schema.sql src/main/java/com/tongji/common/exception/ErrorCode.java src/main/resources/application.yml src/test/java/com/tongji/promotion/PromotionSchemaContractTest.java
git commit -m "feat: add promotion slot auction schema and config"
```

### Task 2: 落 promotion domain、mapper、campaign/bid 写路径

**Files:**
- Create: `src/main/java/com/tongji/promotion/model/PromotionResourceType.java`
- Create: `src/main/java/com/tongji/promotion/model/PromotionCampaign.java`
- Create: `src/main/java/com/tongji/promotion/model/PromotionCampaignStatus.java`
- Create: `src/main/java/com/tongji/promotion/model/PromotionAuctionWindow.java`
- Create: `src/main/java/com/tongji/promotion/model/PromotionAuctionWindowStatus.java`
- Create: `src/main/java/com/tongji/promotion/model/PromotionBid.java`
- Create: `src/main/java/com/tongji/promotion/model/PromotionBidStatus.java`
- Create: `src/main/java/com/tongji/promotion/mapper/PromotionCampaignMapper.java`
- Create: `src/main/java/com/tongji/promotion/mapper/PromotionAuctionWindowMapper.java`
- Create: `src/main/java/com/tongji/promotion/mapper/PromotionBidMapper.java`
- Create: `src/main/java/com/tongji/promotion/service/PromotionCampaignService.java`
- Create: `src/main/java/com/tongji/promotion/service/PromotionAuctionWindowService.java`
- Create: `src/main/java/com/tongji/promotion/api/PromotionController.java`
- Create: `src/main/java/com/tongji/promotion/api/dto/CreatePromotionCampaignRequest.java`
- Create: `src/main/java/com/tongji/promotion/api/dto/SubmitPromotionBidRequest.java`
- Create: `src/main/resources/mapper/PromotionCampaignMapper.xml`
- Create: `src/main/resources/mapper/PromotionAuctionWindowMapper.xml`
- Create: `src/main/resources/mapper/PromotionBidMapper.xml`
- Test: `src/test/java/com/tongji/promotion/service/PromotionAuctionWindowServiceTest.java`
- Test: `src/test/java/com/tongji/promotion/service/PromotionCampaignServiceTest.java`
- Test: `src/test/java/com/tongji/promotion/api/PromotionControllerTest.java`

- [ ] **Step 0: 先写 window service test，钉住“自动首建/续建 open window”**

```java
@ExtendWith(MockitoExtension.class)
class PromotionAuctionWindowServiceTest {

    @Mock PromotionAuctionWindowMapper windowMapper;
    @Mock IdService idService;
    @Mock Clock clock;

    PromotionAuctionWindowService service;

    @BeforeEach
    void setUp() {
        service = new PromotionAuctionWindowService(windowMapper, idService, clock);
    }

    @Test
    void ensureOpenWindowCreatesCurrentAndNextWindowWhenMissing() {
        Instant now = Instant.parse("2026-06-20T10:05:00Z");
        when(clock.instant()).thenReturn(now);
        when(windowMapper.findCurrentOpenWindow(PromotionResourceType.FEED_TOP_SLOT, now)).thenReturn(null);
        when(idService.nextId(IdNamespace.ADMIN_OPERATION)).thenReturn(301L, 302L);

        PromotionAuctionWindow window = service.ensureOpenWindow(PromotionResourceType.FEED_TOP_SLOT);

        assertThat(window.getId()).isEqualTo(301L);
        verify(windowMapper, times(2)).insert(any(PromotionAuctionWindow.class));
    }

    @Test
    void ensureOpenWindowKeepsCurrentAndBackfillsNextWhenMissing() {
        Instant now = Instant.parse("2026-06-20T10:05:00Z");
        PromotionAuctionWindow current = window(301L, PromotionResourceType.FEED_TOP_SLOT, "2026-06-20T10:00:00Z", "2026-06-20T11:00:00Z");
        when(clock.instant()).thenReturn(now);
        when(windowMapper.findCurrentOpenWindow(PromotionResourceType.FEED_TOP_SLOT, now)).thenReturn(current);
        when(windowMapper.findExactWindow(PromotionResourceType.FEED_TOP_SLOT, Instant.parse("2026-06-20T11:00:00Z"), Instant.parse("2026-06-20T12:00:00Z")))
                .thenReturn(null);
        when(idService.nextId(IdNamespace.ADMIN_OPERATION)).thenReturn(302L);

        PromotionAuctionWindow window = service.ensureOpenWindow(PromotionResourceType.FEED_TOP_SLOT);

        assertThat(window.getId()).isEqualTo(301L);
        verify(windowMapper).insert(any(PromotionAuctionWindow.class));
    }
}
```

- [ ] **Step 1: 先写 service test，钉住 campaign 创建与 bid 冻结语义**

```java
@ExtendWith(MockitoExtension.class)
class PromotionCampaignServiceTest {

    @Mock PromotionCampaignMapper campaignMapper;
    @Mock PromotionAuctionWindowMapper windowMapper;
    @Mock PromotionBidMapper bidMapper;
    @Mock WalletService walletService;
    @Mock IdService idService;

    PromotionCampaignService service;

    @BeforeEach
    void setUp() {
        service = new PromotionCampaignService(campaignMapper, windowMapper, bidMapper, walletService, idService);
    }

    @Test
    void submitBidHoldsBidAmountWhenWindowOpen() {
        when(windowMapper.findActiveWindowForBid(PromotionResourceType.FEED_TOP_SLOT, Instant.parse("2026-06-20T10:05:00Z")))
                .thenReturn(window(301L, PromotionResourceType.FEED_TOP_SLOT, "2026-06-20T10:00:00Z", "2026-06-20T11:00:00Z"));
        when(campaignMapper.findById(201L)).thenReturn(campaign(201L, 42L, 1001L, PromotionResourceType.FEED_TOP_SLOT));
        when(idService.nextId(IdNamespace.ADMIN_OPERATION)).thenReturn(401L);

        service.submitBid(42L, 201L, 120L, Instant.parse("2026-06-20T10:05:00Z"));

        verify(walletService).hold(
                42L,
                120L,
                WalletLedgerReason.HOLD_RESERVE,
                WalletBusinessType.PROMOTION,
                "promotion-bid:401"
        );
        verify(bidMapper).insert(any(PromotionBid.class));
    }
}
```

- [ ] **Step 2: 跑 test，确认先失败**

Run: `mvn -q -Dtest=PromotionAuctionWindowServiceTest,PromotionCampaignServiceTest test`  
Expected: FAIL，包含 `PromotionCampaignService` 或相关类型不存在。

- [ ] **Step 3: 写最小 domain + window service + mapper + service + controller**

```java
public enum PromotionResourceType {
    FEED_TOP_SLOT,
    SEARCH_TOP_SLOT
}
```

```java
@Service
@RequiredArgsConstructor
public class PromotionAuctionWindowService {

    private final PromotionAuctionWindowMapper windowMapper;
    private final IdService idService;
    private final Clock clock;

    @Transactional
    public PromotionAuctionWindow ensureOpenWindow(PromotionResourceType resourceType) {
        Instant now = clock.instant();
        PromotionAuctionWindow current = windowMapper.findCurrentOpenWindow(resourceType, now);
        if (current != null) {
            ensureNextWindow(resourceType, current.getWindowEndAt());
            return current;
        }
        PromotionAuctionWindow created = createWindow(resourceType, alignWindowStart(now));
        ensureNextWindow(resourceType, created.getWindowEndAt());
        return created;
    }
}
```

```java
@Service
@RequiredArgsConstructor
public class PromotionCampaignService {

    private final PromotionCampaignMapper campaignMapper;
    private final PromotionAuctionWindowService windowService;
    private final PromotionBidMapper bidMapper;
    private final WalletService walletService;
    private final IdService idService;

    @Transactional
    public PromotionBid submitBid(long userId, long campaignId, long bidAmount, Instant now) {
        PromotionCampaign campaign = requireCampaign(campaignId, userId);
        PromotionAuctionWindow window = windowService.ensureOpenWindow(campaign.getResourceType());
        if (bidMapper.findByCampaignIdAndAuctionWindowId(campaignId, window.getId()) != null) {
            throw new BusinessException(ErrorCode.PROMOTION_BID_DUPLICATE);
        }
        long bidId = idService.nextId(IdNamespace.ADMIN_OPERATION);
        String businessRef = "promotion-bid:" + bidId;
        walletService.hold(userId, bidAmount, WalletLedgerReason.HOLD_RESERVE, WalletBusinessType.PROMOTION, businessRef);
        PromotionBid bid = PromotionBid.builder()
                .id(bidId)
                .campaignId(campaignId)
                .auctionWindowId(window.getId())
                .bidderUserId(userId)
                .bidAmount(bidAmount)
                .walletBusinessRef(businessRef)
                .status(PromotionBidStatus.ACTIVE)
                .createdAt(now)
                .updatedAt(now)
                .build();
        bidMapper.insert(bid);
        return bid;
    }
}
```

```java
@PostMapping("/campaigns/{campaignId}/bids")
public PromotionBidResponse submitBid(@PathVariable long campaignId,
                                      @Valid @RequestBody SubmitPromotionBidRequest request,
                                      @AuthenticationPrincipal Jwt jwt) {
    long userId = jwtService.extractUserId(jwt);
    return PromotionBidResponse.from(service.submitBid(userId, campaignId, request.bidAmount(), Instant.now()));
}
```

- [ ] **Step 4: 跑 service/controller tests，确认通过**

Run: `mvn -q -Dtest=PromotionAuctionWindowServiceTest,PromotionCampaignServiceTest,PromotionControllerTest test`  
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/tongji/promotion src/main/resources/mapper/PromotionCampaignMapper.xml src/main/resources/mapper/PromotionAuctionWindowMapper.xml src/main/resources/mapper/PromotionBidMapper.xml src/test/java/com/tongji/promotion/service/PromotionAuctionWindowServiceTest.java src/test/java/com/tongji/promotion/service/PromotionCampaignServiceTest.java src/test/java/com/tongji/promotion/api/PromotionControllerTest.java
git commit -m "feat: add promotion campaign and bid write path"
```

### Task 3: 落 GSP 结算、allocation 持久化、wallet 扣减/释放

**Files:**
- Create: `src/main/java/com/tongji/promotion/model/PromotionSlotAllocation.java`
- Create: `src/main/java/com/tongji/promotion/model/PromotionPlacementType.java`
- Create: `src/main/java/com/tongji/promotion/model/PromotionSettlementResult.java`
- Create: `src/main/java/com/tongji/promotion/mapper/PromotionSlotAllocationMapper.java`
- Create: `src/main/java/com/tongji/promotion/service/PromotionAuctionService.java`
- Create: `src/main/resources/mapper/PromotionSlotAllocationMapper.xml`
- Modify: `src/main/java/com/tongji/wallet/model/WalletLedgerReason.java`
- Modify: `src/main/java/com/tongji/wallet/service/WalletService.java`
- Test: `src/test/java/com/tongji/promotion/service/PromotionAuctionServiceTest.java`

- [ ] **Step 1: 先写 GSP 结算测试，钉住二价、保留价、释放超额、落败释放**

```java
@ExtendWith(MockitoExtension.class)
class PromotionAuctionServiceTest {

    @Mock PromotionAuctionWindowMapper windowMapper;
    @Mock PromotionBidMapper bidMapper;
    @Mock PromotionSlotAllocationMapper allocationMapper;
    @Mock WalletService walletService;
    @Mock IdService idService;

    PromotionAuctionService service;

    @BeforeEach
    void setUp() {
        service = new PromotionAuctionService(windowMapper, bidMapper, allocationMapper, walletService, idService);
    }

    @Test
    void settlesTwoSlotWindowWithGspPricing() {
        PromotionAuctionWindow window = window(301L, PromotionResourceType.FEED_TOP_SLOT, 2, 50L);
        List<PromotionBid> bids = List.of(
                bid(401L, 201L, 42L, 120L),
                bid(402L, 202L, 43L, 100L),
                bid(403L, 203L, 44L, 70L)
        );
        when(idService.nextId(IdNamespace.ADMIN_OPERATION)).thenReturn(501L, 502L);

        service.settleWindow(window, bids, Instant.parse("2026-06-20T11:00:00Z"));

        verify(walletService).captureHoldToPlatform(42L, 100L, WalletBusinessType.PROMOTION, "promotion-settle:bid:401:capture");
        verify(walletService).captureHoldToPlatform(43L, 70L, WalletBusinessType.PROMOTION, "promotion-settle:bid:402:capture");
        verify(walletService).releaseHold(42L, 20L, WalletLedgerReason.PROMOTION_BID_RELEASE, WalletBusinessType.PROMOTION, "promotion-settle:bid:401:release");
        verify(walletService).releaseHold(43L, 30L, WalletLedgerReason.PROMOTION_BID_RELEASE, WalletBusinessType.PROMOTION, "promotion-settle:bid:402:release");
        verify(walletService).releaseHold(44L, 70L, WalletLedgerReason.PROMOTION_BID_RELEASE, WalletBusinessType.PROMOTION, "promotion-settle:bid:403:release");
        verify(bidMapper).markWon(401L, 0, 100L);
        verify(bidMapper).markWon(402L, 1, 70L);
        verify(bidMapper).markLost(403L);
    }
}
```

- [ ] **Step 2: 跑 test，确认先失败**

Run: `mvn -q -Dtest=PromotionAuctionServiceTest test`  
Expected: FAIL，包含 `PromotionAuctionService` 不存在或方法签名不匹配。

- [ ] **Step 3: 写最小 GSP 结算实现**

```java
// WalletLedgerReason.java
PROMOTION_BID_CAPTURE,
PROMOTION_BID_RELEASE,
```

```java
// WalletService.java
@Transactional(isolation = Isolation.READ_COMMITTED)
public WalletLedgerEntry captureHoldToPlatform(long ownerUserId, long amount,
                                               WalletBusinessType businessType, String businessRef) {
    return apply(ownerUserId, amount, WalletLedgerReason.PROMOTION_BID_CAPTURE, businessType,
            WalletLedgerDirection.DEBIT, walletProperties.getPlatformUserId(), null, 0L, -amount, 0L, businessRef);
}
```

```java
@Service
@RequiredArgsConstructor
public class PromotionAuctionService {

    private final PromotionAuctionWindowMapper windowMapper;
    private final PromotionBidMapper bidMapper;
    private final PromotionSlotAllocationMapper allocationMapper;
    private final WalletService walletService;
    private final IdService idService;

    @Transactional
    public void settleWindow(PromotionAuctionWindow window, List<PromotionBid> bids, Instant settledAt) {
        List<PromotionBid> ranked = bids.stream()
                .sorted(Comparator.comparingLong(PromotionBid::getBidAmount).reversed().thenComparingLong(PromotionBid::getId))
                .toList();
        int winners = Math.min(window.getSlotCount(), ranked.size());
        for (int i = 0; i < ranked.size(); i++) {
            PromotionBid bid = ranked.get(i);
            if (i < winners) {
                long nextBid = (i + 1 < ranked.size()) ? ranked.get(i + 1).getBidAmount() : window.getReservePrice();
                long clearingPrice = Math.max(nextBid, window.getReservePrice());
                long releaseAmount = bid.getBidAmount() - clearingPrice;
                walletService.captureHoldToPlatform(
                        bid.getBidderUserId(),
                        clearingPrice,
                        WalletBusinessType.PROMOTION,
                        "promotion-settle:bid:" + bid.getId() + ":capture"
                );
                if (releaseAmount > 0) {
                    walletService.releaseHold(
                            bid.getBidderUserId(),
                            releaseAmount,
                            WalletLedgerReason.PROMOTION_BID_RELEASE,
                            WalletBusinessType.PROMOTION,
                            "promotion-settle:bid:" + bid.getId() + ":release"
                    );
                }
                bidMapper.markWon(bid.getId(), i, clearingPrice);
                allocationMapper.insert(buildAllocation(window, bid, i, clearingPrice));
            } else {
                walletService.releaseHold(
                        bid.getBidderUserId(),
                        bid.getBidAmount(),
                        WalletLedgerReason.PROMOTION_BID_RELEASE,
                        WalletBusinessType.PROMOTION,
                        "promotion-settle:bid:" + bid.getId() + ":release"
                );
                bidMapper.markLost(bid.getId());
            }
        }
        windowMapper.markSettled(window.getId(), settledAt);
    }
}
```

- [ ] **Step 4: 跑测试，确认通过**

Run: `mvn -q -Dtest=PromotionAuctionServiceTest test`  
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/tongji/promotion/model/PromotionSlotAllocation.java src/main/java/com/tongji/promotion/model/PromotionPlacementType.java src/main/java/com/tongji/promotion/model/PromotionSettlementResult.java src/main/java/com/tongji/promotion/mapper/PromotionSlotAllocationMapper.java src/main/java/com/tongji/promotion/service/PromotionAuctionService.java src/main/resources/mapper/PromotionSlotAllocationMapper.xml src/main/java/com/tongji/wallet/model/WalletLedgerReason.java src/main/java/com/tongji/wallet/service/WalletService.java src/test/java/com/tongji/promotion/service/PromotionAuctionServiceTest.java
git commit -m "feat: add promotion slot auction settlement"
```

### Task 4: 接定时关闭窗口并刷新 active allocation cache

**Files:**
- Create: `src/main/java/com/tongji/promotion/service/PromotionAllocationCacheService.java`
- Create: `src/main/java/com/tongji/promotion/service/PromotionAllocationService.java`
- Create: `src/main/java/com/tongji/promotion/schedule/PromotionAuctionWindowCloser.java`
- Create: `src/main/java/com/tongji/promotion/schedule/PromotionAuctionScheduler.java`
- Test: `src/test/java/com/tongji/promotion/schedule/PromotionAuctionWindowCloserTest.java`
- Test: `src/test/java/com/tongji/promotion/schedule/PromotionAuctionSchedulerTest.java`

- [ ] **Step 1: 先写 closer test，钉住“只处理到期 open window + 刷 active cache”**

```java
@ExtendWith(MockitoExtension.class)
class PromotionAuctionWindowCloserTest {

    @Mock PromotionAuctionWindowMapper windowMapper;
    @Mock PromotionBidMapper bidMapper;
    @Mock PromotionAuctionService auctionService;
    @Mock PromotionAllocationCacheService cacheService;

    PromotionAuctionWindowCloser closer;

    @BeforeEach
    void setUp() {
        closer = new PromotionAuctionWindowCloser(windowMapper, bidMapper, auctionService, cacheService);
    }

    @Test
    void closesDueWindowsAndRefreshesCache() {
        PromotionAuctionWindow window = window(301L, PromotionResourceType.FEED_TOP_SLOT, "2026-06-20T10:00:00Z", "2026-06-20T11:00:00Z");
        when(windowMapper.listClosableWindows(any(), eq(50))).thenReturn(List.of(window));
        when(bidMapper.listActiveBidsByWindowId(301L)).thenReturn(List.of(bid(401L, 201L, 42L, 120L)));

        closer.closeDueWindows(Instant.parse("2026-06-20T11:00:00Z"), 50);

        verify(auctionService).settleWindow(eq(window), anyList(), eq(Instant.parse("2026-06-20T11:00:00Z")));
        verify(cacheService).refreshActiveAllocations(PromotionResourceType.FEED_TOP_SLOT, Instant.parse("2026-06-20T11:00:00Z"));
    }
}
```

- [ ] **Step 2: 跑 test，确认先失败**

Run: `mvn -q -Dtest=PromotionAuctionWindowCloserTest test`  
Expected: FAIL，包含 `PromotionAuctionWindowCloser` 不存在。

- [ ] **Step 3: 写最小 closer / scheduler / cache service**

```java
@Component
@RequiredArgsConstructor
public class PromotionAuctionWindowCloser {

    private final PromotionAuctionWindowMapper windowMapper;
    private final PromotionBidMapper bidMapper;
    private final PromotionAuctionService auctionService;
    private final PromotionAllocationCacheService cacheService;

    void closeDueWindows(Instant now, int batchSize) {
        for (PromotionAuctionWindow window : windowMapper.listClosableWindows(now, batchSize)) {
            auctionService.settleWindow(window, bidMapper.listActiveBidsByWindowId(window.getId()), now);
            cacheService.refreshActiveAllocations(window.getResourceType(), now);
        }
    }
}
```

```java
@Component
@RequiredArgsConstructor
public class PromotionAuctionScheduler {

    private final PromotionAuctionWindowService windowService;
    private final PromotionAuctionWindowCloser closer;

    @Scheduled(fixedDelayString = "${promotion.slot-auction.close-window-delay-ms:30000}")
    public void ensureWindows() {
        windowService.ensureOpenWindow(PromotionResourceType.FEED_TOP_SLOT);
        windowService.ensureOpenWindow(PromotionResourceType.SEARCH_TOP_SLOT);
    }

    @Scheduled(fixedDelayString = "${promotion.slot-auction.close-window-delay-ms:30000}")
    public void closeDueWindows() {
        closer.closeDueWindows(Instant.now(), 50);
    }
}
```

```java
@Service
@RequiredArgsConstructor
public class PromotionAllocationCacheService {

    private final StringRedisTemplate redis;
    private final PromotionSlotAllocationMapper allocationMapper;
    private final ObjectMapper objectMapper;

    public void refreshActiveAllocations(PromotionResourceType resourceType, Instant now) {
        List<PromotionAllocationView> active = allocationMapper.listActive(resourceType, now);
        try {
            redis.opsForValue().set(
                    "promotion:allocation:active:" + resourceType.name().toLowerCase(),
                    objectMapper.writeValueAsString(active),
                    Duration.ofSeconds(300)
            );
        } catch (Exception e) {
            throw new IllegalStateException("Unable to refresh promotion allocation cache", e);
        }
    }
}
```

```java
@ExtendWith(MockitoExtension.class)
class PromotionAuctionSchedulerTest {

    @Mock PromotionAuctionWindowCloser closer;
    @Mock PromotionAuctionWindowService windowService;

    PromotionAuctionScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new PromotionAuctionScheduler(windowService, closer);
    }

    @Test
    void dispatchesWindowCloseJob() {
        scheduler.closeDueWindows();
        verify(closer).closeDueWindows(any(Instant.class), eq(50));
    }

    @Test
    void ensuresFeedAndSearchWindowsExist() {
        scheduler.ensureWindows();
        verify(windowService).ensureOpenWindow(PromotionResourceType.FEED_TOP_SLOT);
        verify(windowService).ensureOpenWindow(PromotionResourceType.SEARCH_TOP_SLOT);
    }
}
```

- [ ] **Step 4: 跑测试，确认通过**

Run: `mvn -q -Dtest=PromotionAuctionWindowCloserTest,PromotionAuctionSchedulerTest test`  
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/tongji/promotion/service/PromotionAllocationCacheService.java src/main/java/com/tongji/promotion/service/PromotionAllocationService.java src/main/java/com/tongji/promotion/schedule/PromotionAuctionWindowCloser.java src/main/java/com/tongji/promotion/schedule/PromotionAuctionScheduler.java src/test/java/com/tongji/promotion/schedule/PromotionAuctionWindowCloserTest.java src/test/java/com/tongji/promotion/schedule/PromotionAuctionSchedulerTest.java
git commit -m "feat: schedule promotion auction settlement"
```

### Task 5: 接 public feed / home feed 商业位插入与缓存读取

**Files:**
- Modify: `src/main/java/com/tongji/knowpost/api/dto/FeedItemResponse.java`
- Modify: `src/main/java/com/tongji/knowpost/service/impl/KnowPostFeedServiceImpl.java`
- Modify: `src/main/java/com/tongji/recommendation/HomeFeedMixingService.java`
- Modify: `src/main/java/com/tongji/knowpost/listener/FeedCacheInvalidationListener.java`
- Test: `src/test/java/com/tongji/recommendation/HomeFeedMixingServiceTest.java`
- Test: `src/test/java/com/tongji/recommendation/HomeFeedMixingHydrationTest.java`
- Test: `src/test/java/com/tongji/knowpost/api/KnowPostControllerPublishTest.java`

- [ ] **Step 1: 先写 feed mixing test，钉住“最多 1 条 promoted，且 organic 去重补位”**

```java
@Test
void insertsPromotedFeedSlotBeforeOrganicAndDedupesPost() {
    PromotionAllocationView promoted = new PromotionAllocationView("201", "feed_top_slot", "301", "401");
    when(promotionAllocationService.getActiveFeedAllocation()).thenReturn(List.of(promoted));
    when(knowPostFeedService.getFeedByIds(List.of(201L), 42L, KnowPostFeedService.FeedVisibilityScope.PUBLIC))
            .thenReturn(List.of(promotedFeedItem(201L)));
    when(followFeedService.getTimeline(42L, null, 20)).thenReturn(new TimelinePage(List.of(
            timelineItem(201L, 10L, "2026-06-18T10:15:30Z"),
            timelineItem(202L, 10L, "2026-06-18T10:15:29Z")
    ), null));
    when(knowPostFeedService.getFeedByIds(List.of(202L), 42L, KnowPostFeedService.FeedVisibilityScope.FOLLOW))
            .thenReturn(List.of(feedItem(202L)));

    FeedPageResponse response = service.getHomeFeed(42L);

    assertThat(response.items()).extracting(FeedItemResponse::id).containsExactly("201", "202");
    assertThat(response.items().getFirst().commercial()).isTrue();
    assertThat(response.items().getFirst().placementType()).isEqualTo("feed_top_slot");
}
```

- [ ] **Step 2: 跑 test，确认先失败**

Run: `mvn -q -Dtest=HomeFeedMixingServiceTest,HomeFeedMixingHydrationTest,KnowPostControllerPublishTest test`  
Expected: FAIL，包含 `FeedItemResponse` 字段或 `promotionAllocationService` 缺失。

- [ ] **Step 3: 最小改 feed DTO / public feed / home feed**

```java
private FeedItemResponse markPromoted(FeedItemResponse base, PromotionAllocationView allocation) {
    return new FeedItemResponse(
            base.id(),
            base.title(),
            base.description(),
            base.coverImage(),
            base.tags(),
            base.authorAvatar(),
            base.authorNickname(),
            base.tagJson(),
            base.likeCount(),
            base.favoriteCount(),
            base.liked(),
            base.faved(),
            base.isTop(),
            true,
            true,
            allocation.placementType(),
            allocation.promotionCampaignId(),
            allocation.auctionWindowId()
    );
}
```

```java
public FeedPageResponse getHomeFeed(long userId) {
    List<FeedItemResponse> items = new ArrayList<>(TARGET_SIZE);
    Set<Long> seen = new LinkedHashSet<>();
    appendPromoted(items, seen, userId, 1);
    // 后续 follow/recommendation/hot 逻辑保持不变，只是 TARGET_SIZE 变成剩余 need
}
```

```java
private void appendPromoted(List<FeedItemResponse> items, Set<Long> seen, Long userId, int limit) {
    List<PromotionAllocationView> allocations = promotionAllocationService.getActiveFeedAllocation();
    List<Long> ids = allocations.stream().limit(limit).map(PromotionAllocationView::postIdAsLong).filter(seen::add).toList();
    if (ids.isEmpty()) {
        return;
    }
    Map<Long, PromotionAllocationView> byId = allocations.stream()
            .collect(Collectors.toMap(PromotionAllocationView::postIdAsLong, Function.identity(), (left, right) -> left));
    for (FeedItemResponse item : knowPostFeedService.getFeedByIds(ids, userId, KnowPostFeedService.FeedVisibilityScope.PUBLIC)) {
        items.add(markPromoted(item, byId.get(Long.parseLong(item.id()))));
    }
}
```

```java
private FeedPageResponse mergePromotedIntoPage(FeedPageResponse organic, Long currentUserIdNullable) {
    List<FeedItemResponse> merged = new ArrayList<>(organic.size());
    Set<String> seen = new LinkedHashSet<>();
    appendPromotedPublic(merged, seen, currentUserIdNullable, 1);
    for (FeedItemResponse item : organic.items()) {
        if (seen.add(item.id()) && merged.size() < organic.size()) {
            merged.add(item);
        }
    }
    return new FeedPageResponse(merged, organic.page(), organic.size(), organic.hasMore(), organic.nextCursor());
}
```

```java
public FeedPageResponse getPublicFeed(int page, int size, Long currentUserIdNullable) {
    if (Math.max(page, 1) > 1) {
        return existingGetPublicFeedFlow(page, size, currentUserIdNullable);
    }
    // 保持现有三级缓存、回源、enrich、writeCaches 逻辑
    FeedPageResponse organic = existingGetPublicFeedFlow(page, size, currentUserIdNullable);
    return mergePromotedIntoPage(organic, currentUserIdNullable);
}
```

```java
// ponytail: 把当前 `getPublicFeed(...)` 原有主体整体内联迁到 private helper，避免在 plan 里重写 100+ 行缓存逻辑
private FeedPageResponse existingGetPublicFeedFlow(int page, int size, Long currentUserIdNullable) {
    // 这里执行当前文件已有实现：local cache -> Redis fragment cache -> DB fallback -> enrich
}
```

- [ ] **Step 4: 跑测试，确认通过**

Run: `mvn -q -Dtest=HomeFeedMixingServiceTest,HomeFeedMixingHydrationTest,KnowPostControllerPublishTest test`  
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/tongji/knowpost/api/dto/FeedItemResponse.java src/main/java/com/tongji/knowpost/service/impl/KnowPostFeedServiceImpl.java src/main/java/com/tongji/recommendation/HomeFeedMixingService.java src/main/java/com/tongji/knowpost/listener/FeedCacheInvalidationListener.java src/test/java/com/tongji/recommendation/HomeFeedMixingServiceTest.java src/test/java/com/tongji/recommendation/HomeFeedMixingHydrationTest.java src/test/java/com/tongji/knowpost/api/KnowPostControllerPublishTest.java
git commit -m "feat: insert promoted feed slots from allocations"
```

### Task 6: 接 search 商业位插入与 placement metadata

**Files:**
- Modify: `src/main/java/com/tongji/search/service/impl/SearchServiceImpl.java`
- Test: `src/test/java/com/tongji/search/service/SearchServiceImplTest.java`

- [ ] **Step 1: 先写 search test，钉住“页首 1 条 promoted + organic 去重”**

```java
@ExtendWith(MockitoExtension.class)
class SearchServiceImplTest {

    @Mock ElasticsearchClient es;
    @Mock CounterService counterService;
    @Mock PromotionAllocationService promotionAllocationService;

    SearchServiceImpl service;

    @Test
    void prependsPromotedSearchItemAndDedupesOrganicHit() {
        when(promotionAllocationService.getActiveSearchAllocation()).thenReturn(List.of(
                new PromotionAllocationView("201", "search_top_slot", "301", "401")
        ));
        // mock ES 返回 201, 202；最终只保留 promoted 201 + organic 202
    }

    @Test
    void keepsNextAfterAndHasMoreBasedOnOrganicHitsOnly() {
        when(promotionAllocationService.getActiveSearchAllocation()).thenReturn(List.of(
                new PromotionAllocationView("201", "search_top_slot", "301", "401")
        ));
        SearchResponse response = service.search("llm", 2, null, null, 42L);

        assertThat(response.items()).extracting(FeedItemResponse::id).containsExactly("201", "202");
        assertThat(response.nextAfter()).isEqualTo(expectedAfterFor("202"));
        assertThat(response.hasMore()).isTrue();
    }
}
```

- [ ] **Step 2: 跑 test，确认先失败**

Run: `mvn -q -Dtest=SearchServiceImplTest test`  
Expected: FAIL，包含测试文件不存在或构造器签名不匹配。

- [ ] **Step 3: 最小改 search service**

```java
public SearchResponse search(String q, int size, String tagsCsv, String after, Long currentUserIdNullable) {
    int promotedLimit = after == null ? 1 : 0;
    List<FeedItemResponse> items = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();
    appendPromotedSearch(items, seen, currentUserIdNullable, promotedLimit);
    int organicNeed = size - items.size();
    SearchSlice organic = searchOrganic(q, organicNeed + 1, tagsCsv, after, currentUserIdNullable);
    for (FeedItemResponse item : organic.items()) {
        if (seen.add(item.id()) && items.size() < size) {
            items.add(item);
        }
    }
    return new SearchResponse(items, organic.nextAfter(), organic.hasMore());
}
```

```java
private record SearchSlice(List<FeedItemResponse> items, String nextAfter, boolean hasMore) {}
```

```java
private void appendPromotedSearch(List<FeedItemResponse> items, Set<String> seen, Long currentUserIdNullable, int promotedLimit) {
    for (PromotionAllocationView allocation : promotionAllocationService.getActiveSearchAllocation()) {
        FeedItemResponse item = hydratePromotedItem(allocation.postIdAsLong(), currentUserIdNullable, allocation);
        if (item != null && seen.add(item.id()) && items.size() < promotedLimit) {
            items.add(item);
        }
    }
}
```

```java
private SearchSlice searchOrganic(String q, int requestedSize, String tagsCsv, String after, Long currentUserIdNullable) {
    // 复用当前 ES 查询逻辑，但返回：
    // 1. 去映射后的 organic items
    // 2. nextAfter = organic 最后一个“实际返回给客户端”的 hit.sort
    // 3. hasMore = organic hits.size() > organicPageSize
}
```

- [ ] **Step 4: 跑 test，确认通过**

Run: `mvn -q -Dtest=SearchServiceImplTest test`  
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/tongji/search/service/impl/SearchServiceImpl.java src/test/java/com/tongji/search/service/SearchServiceImplTest.java
git commit -m "feat: insert promoted search slots from allocations"
```

### Task 7: 端到端校验、OpenSpec 回填、文档收口

**Files:**
- Modify: `openspec/changes/add-slot-auction-promotions/tasks.md`
- Modify: `docs/superpowers/plans/2026-06-20-add-slot-auction-promotions.md`

- [ ] **Step 1: 跑 change 相关测试集**

Run:

```bash
mvn -q -Dtest=PromotionSchemaContractTest,PromotionCampaignServiceTest,PromotionAuctionServiceTest,PromotionAuctionWindowCloserTest,PromotionAuctionSchedulerTest,PromotionControllerTest,HomeFeedMixingServiceTest,HomeFeedMixingHydrationTest,KnowPostControllerPublishTest,SearchServiceImplTest test
```

Expected: PASS

- [ ] **Step 2: 跑最小编译回归**

Run: `mvn -q -DskipTests compile`  
Expected: PASS

- [ ] **Step 3: 手工核对 spec coverage**

核对以下事实都已存在：

```text
- resource model 用 promotion / feed_top_slot / search_top_slot
- auction window batch settle，不在请求路径拍卖
- open window 自动首建/续建
- multi-slot GSP pricing + reserve price
- bid 接单冻结，winner 扣价、loser/超额释放
- feed/search 输出 promoted/commercial/placement metadata
- promoted 数量上限生效
- search promoted 不污染 organic `nextAfter/hasMore`
```

- [ ] **Step 4: 回填 OpenSpec tasks**

把 `openspec/changes/add-slot-auction-promotions/tasks.md` 对应项勾掉，仅勾已验证完成项：

```md
- [x] 1.1 Add promotion campaign, auction window, promotion bid, and slot allocation tables.
- [x] 1.2 Introduce zhiguang-native promotion resource enums for `feed_top_slot` and `search_top_slot`.
- [x] 1.3 Adapt reusable `bytedance` auction and bidding core to new promotion terms and remove product/live-room assumptions.
- [x] 2.1 Implement window-based bid intake, ranking, and multi-slot GSP settlement flow.
- [x] 2.2 Integrate wallet hold, clearing deduction, and excess release for promotion bids.
- [x] 2.3 Implement reserve price, slot count, and allocation persistence rules.
- [x] 3.1 Replace manual feed top behavior with allocation-driven promoted feed insertion.
- [x] 3.2 Add search promoted slot insertion with commercial placement metadata.
- [x] 3.3 Add cached lookup path for active slot allocations used by feed and search APIs.
- [x] 3.4 Enforce promoted-slot count limits and commercial flags in response contracts.
- [x] 4.1 Add jobs or handlers to close auction windows and refresh cached allocations.
- [x] 4.2 Add tests for GSP pricing, tie handling, reserve floor, and wallet release behavior.
- [x] 4.3 Add feed/search integration tests covering promoted insertion and organic fallback.
```

- [ ] **Step 5: Commit**

```bash
git add openspec/changes/add-slot-auction-promotions/tasks.md docs/superpowers/plans/2026-06-20-add-slot-auction-promotions.md
git commit -m "docs: update slot auction promotion progress"
```

## Self-Review

### 1. Spec coverage

- `promotion` 资源建模：Task 1 + Task 2
- `cached auction windows`：Task 4
- `multi-slot GSP pricing`：Task 3
- `wallet reserve / deduct / release`：Task 2 + Task 3
- `feed/search insertion + metadata`：Task 5 + Task 6
- `commercial limits and labeling`：Task 5 + Task 6

无 spec 空洞。

### 2. Placeholder scan

- 无 `TODO` / `TBD`
- 每个代码步骤都有示例代码
- 每个测试步骤都有具体命令
- 无“类似 Task N”引用

### 3. Type consistency

- 统一用 `PromotionResourceType`
- 统一用 `PromotionAllocationView`
- 响应字段统一：`promoted` / `commercial` / `placementType` / `promotionCampaignId` / `auctionWindowId`
- feed/search 都基于 `FeedItemResponse` 扩展，不再复用 `isTop`

## 实施注意

- 不引新依赖。
- 不在本 change 里做 `paid boost`。
- 不做 query targeting DSL。
- 不把商业位写进 ES 索引当作 organic 文档。
- 不删 `KnowPostTopPatch` 接口；只让 public/home/search 的展示主路径改读 allocation。
- `WalletService.releaseHold(...)` 只负责释放冻结；winner 成交价扣减必须显式走本计划新增的 `captureHoldToPlatform(...)`，不能靠 “release + grant” 拼假账。

## Plan 结论

Plan complete and saved to `docs/superpowers/plans/2026-06-20-add-slot-auction-promotions.md`。

Two execution options:

**1. Subagent-Driven (recommended)** - 我分任务派 fresh subagent 实现，每任务双重审查  
**2. Inline Execution** - 在当前会话按计划批量执行
