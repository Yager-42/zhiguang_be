## 1. Domain and Schema

- [x] 1.1 Add paid boost campaign, budget, spend, and status tables. — `promotion_boost_campaign` / `promotion_boost_delivery` in `db/schema.sql`（独立于拍卖表，无 winner/GSP/槽位）。
- [x] 1.2 Define campaign channels for recommendation ranking and follow delivery priority. — `PaidBoostChannel {HOME_RECOMMENDATION, FOLLOW_DELIVERY}`。
- [x] 1.3 Reuse wallet reservation and settlement primitives without importing auction winner semantics. — 复用 `WalletService.hold/releaseHold`；新增 `captureHoldToPlatform(reason, ...)` 重载 + `PAID_BOOST_CAPTURE/RELEASE` reason，与拍卖分账。

## 2. Budget and Ledger Flow

- [x] 2.1 Implement budget reservation when paid boost campaign activates. — `PaidBoostCampaignService.createCampaign` 创建即 `hold` 总预算，幂等 ref `paid-boost:{id}:reserve`。
- [x] 2.2 Implement spend settlement and remaining-budget release on campaign close. — `PaidBoostSettlementService`（结算已关闭 bucket 的 PENDING delivery，扣费上限 = 剩余预算；关闭到期活动释放剩余冻结）。
- [x] 2.3 Add idempotent business references for campaign budget movements. — reserve / `spend:{bucket}:{viewer}` / release 三类 ref 全局唯一且可重放。

## 3. Feed Integration

- [x] 3.1 Integrate paid boost weighting into recommendation ranking path. — `PaidBoostRankingService`（`finalScore = organicScore + min(boostValue, maxEffect)`），接入 `HomeFeedMixingService` 推荐阶段。
- [x] 3.2 Integrate paid boost priority into constrained follow-delivery selection logic. — `KnowPostController.followFeed` 受限选择阶段 + `FollowFeedServiceImpl` 支持 limit>20。
- [x] 3.3 Expose commercial promotion markers for boosted items in feed responses. — 复用 `FeedItemResponse.withPromotion`（`home_recommendation_boost` / `follow_delivery_boost`，`auctionWindowId=null`）。

## 4. Verification

- [x] 4.1 Add tests proving paid boost does not create winner or GSP semantics. — `PaidBoostCampaignServiceTest` / `PaidBoostControllerTest`（无 winner/window/clearing 字段；GET 响应只含预算进度）。
- [x] 4.2 Add ranking tests for organic score plus boost effect behavior. — `PaidBoostRankingServiceTest`（organic+boost 排序、maxEffect 封顶、无 boost 原样返回）。
- [x] 4.3 Add follow-delivery tests for constrained priority selection and non-constrained fallback. — `KnowPostControllerPublishTest#followFeedPrefersBoostedItemWithinSelectionWindow`；无 boost 路径与 organic 等价（既有 followFeed 用例全绿）。
- [x] 4.4 Add wallet settlement tests for partial budget consumption and release. — `PaidBoostSettlementServiceTest`（capture 上限、剩余预算释放、耗尽跳过释放）；bucket 去重 `PaidBoostMysqlIntegrationTest#upsertAggregatesSameBucketViewerInsteadOfNewBillableFact`。

## 实现备注（与 plan 命名/结构的偏差，均不改 capability 语义）

- **organic 基线分采用按位置单调递减**（首个 = count），而非 plan 草图里的 gorse 平坦 100.0：平坦分会让 boost 排序把未被 boost 的 gorse 候选按 contentId 重排，破坏 gorse 召回相关度；递减分在叠加 boostEffect 后仍保留原始顺序，符合「organic score + boost effect」目标与「zhiguang 业务优先」。`hot` fallback 同样按位置递减。
- **未新增 `FeedItemResponse.withPromotionMetadata`**：现有 `withPromotion(placementType, campaignId, auctionWindowId)` 已支持 `auctionWindowId=null` 并同时置 `promoted/commercial=true`，paid boost 直接复用，避免重复方法（最小 diff）。
- **active boost 缓存测试拆为独立的 `PaidBoostCacheServiceTest`**（plan 草拟并入 `PaidBoostCampaignServiceTest`），保持单一 SUT。
- **结算只取已关闭 bucket**（`cutoff = now - deliveryBucketSeconds`），避免结算仍可能累计的开放 bucket 导致后续送达漏计费。
- **follow feed 单出口重构**：把 `KnowPostController.followFeed` 的多处早返回改为单出口，确保 delivery 只记录一次；无 boost 路径与 organic 行为逐字等价。
- **`FollowFeedServiceImpl` 缓存仅命中默认 20 页**（`DEFAULT_TIMELINE_PAGE_SIZE`），更大 limit 自然旁路，不污染默认页缓存；`cursorStaysStable` 等用例的 limit<20 绑定值已随 safeLimit 同步更新。
