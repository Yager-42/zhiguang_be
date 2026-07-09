---
doc_type: issue-fix
issue: 2026-07-08-promotion-decision-hash-mismatch
status: fixed
fixed: 2026-07-08
path: fast-track
tags: [promotion, bprime, auction, kafka, hash, jackson, settlement]
---

# bprime 结算链路 decision hash mismatch 修复记录

## 1. 问题描述

推广竞价模块（feature 2026-07-07-frontend-promotion）浏览器实测：出价 → 实时排名能看到、窗口能到 SETTLED，但**结算后置顶效果无法体现**——`promotion_slot_allocation` 表无记录，帖子未被置顶。

链路追溯：`WINDOW_CLOSED` decision 投到 Kafka topic `zhiguang.promotion.auction.decisions.v2`，projection 消费者 `PromotionDecisionProjectionKafkaListener.onMessage` 调 `support.requireDecision(...)` 时抛 `IllegalArgumentException: decision hash mismatch` → `ack.acknowledge()` 不执行 → projection 消费停滞 → `PromotionDecisionProjectionService.settleWindow` 不执行 → allocation 不生成。

`BID_ACCEPTED` decision 消费正常（bid 写入、排名显示正常），唯独 `WINDOW_CLOSED` 失败。

## 2. 根因

`PromotionDecisionHasher` 用一个 `CANONICAL_MAPPER` 序列化 `PromotionAuctionDecision` 再 SHA-256 算 hash：

```java
private static final ObjectMapper CANONICAL_MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
        .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);  // ← 关键
```

`PromotionAuctionDecision.payload` 字段类型是 `Map<String, Object>`。`WINDOW_CLOSED` decision（`PromotionAuctionWindowCloser.java:76-84`）往 payload 塞了两个 **record list**：

- `payload.finalRanking` = `List<PromotionRankingItem>`（`:77`）
- `payload.walletEffects` = `List<PromotionWalletEffect>`（`:80`）

hash 校验是 **生产者算 hash → envelope 序列化进 Kafka → 消费者反序列化 envelope 重建 decision → 再算 hash 比对**。问题出在这两次序列化不幂等：

| 侧 | payload 里 value 的运行时类型 | CANONICAL_MAPPER 序列化字段顺序 |
|---|---|---|
| 生产者（Java 构造） | `PromotionWalletEffect` / `PromotionRankingItem` **record** | record 声明顺序（`ORDER_MAP_ENTRIES_BY_KEYS` 对 record **无效**） |
| 消费者（Kafka 反序列化 `Map<String,Object>`） | `LinkedHashMap`（record 作为 Object value 退化） | key 字母序（`ORDER_MAP_ENTRIES_BY_KEYS` **生效**） |

以 `PromotionWalletEffect` 为例：
- record 声明顺序：`ownerUserId, amount, effectType, businessRef`
- 字母序：`amount, businessRef, effectType, ownerUserId`

两边 JSON 字符串不同 → SHA-256 不同 → `requireDecision` 抛 `decision hash mismatch`。

**为什么 `BID_ACCEPTED` 没问题**：`BID_ACCEPTED` 的 `ranking` 是 `PromotionAuctionDecision` 的**顶层强类型字段** `List<PromotionRankingItem>`（不是 `Map` 里的 value），生产者和消费者两侧都是 record，序列化顺序一致 → hash 幂等 → bid 能写、排名能显示。这解释了"能看到排名和 SETTLED，但 allocation 不生成"的现象。

### 复现测试坐实

新增 `PromotionDecisionHashRoundTripReproTest`（保留为回归测试）：
- `windowClosedDecisionHashSurvivesRoundTrip`：构造含 record list payload 的 WINDOW_CLOSED decision，走 envelope 序列化→反序列化 round-trip，断言 hash 不变。**修复前 FAILED**（`d04a5527… ≠ 5b7fa332…`）。
- `bidAcceptedDecisionHashSurvivesRoundTrip`：对照 组，BID_ACCEPTED decision round-trip。**修复前就 PASSED**，印证根因只在 payload 内 record。

### 误判澄清

feature 2026-07-07-frontend-promotion 的 commit message 和上下文曾怀疑"snapshot DTO long→String 改动影响了 hash 计算"。**实测证伪**：long→String 在 Jackson 序列化→反序列化循环里是幂等的（数字 `123` ↔ 字符串 `"123"` 各自稳定）。这是 bprime **既有 bug**，b366e54 只是让用户首次走到窗口关闭路径、触发 `WINDOW_CLOSED` decision 才暴露它。

## 3. 修复方案

给 2 个 record 加 `@JsonPropertyOrder(alphabetic = true)`，让 record 的序列化字段顺序 = 字母序 = LinkedHashMap 退化后的序列化顺序，使 hash round-trip 幂等。

为什么选这个方案：
- **最小改动**：2 行注解，不改 `PromotionDecisionHasher`、不改 `PromotionAuctionDecision` payload 类型、不改生产者/消费者逻辑。
- **对齐根因**：直接消除"record 声明顺序 vs 字母序"的不一致源头。
- **无副作用**：`PromotionRankingItem` / `PromotionWalletEffect` 的 JSON 字段名不变、值不变，只重排字段顺序；前端/外部消费 JSON 不受字段顺序影响。

备选方案（未采用）：
- 改 `PromotionAuctionDecision.payload` 为强类型 record 而非 `Map<String,Object>`——改动大、影响 `settleWindow` 的 `winners`/`walletEffects` 解析逻辑，超出最小修复。
- 去掉 `CANONICAL_MAPPER` 的 `ORDER_MAP_ENTRIES_BY_KEYS`——会削弱 hash 的规范化程度（Map 字段顺序不再稳定），降低 hash 防篡改语义。

## 4. 改动文件清单

- `src/main/java/com/tongji/promotion/bprime/model/PromotionWalletEffect.java` — 加 `@JsonPropertyOrder(alphabetic = true)` + 注释说明原因
- `src/main/java/com/tongji/promotion/bprime/model/PromotionRankingItem.java` — 加 `@JsonPropertyOrder(alphabetic = true)` + 注释说明原因
- `src/test/java/com/tongji/promotion/bprime/model/PromotionDecisionHashRoundTripReproTest.java` — 新增回归测试（WINDOW_CLOSED + BID_ACCEPTED hash round-trip）

无范围外改动。前端、其他后端模块未触碰。

## 5. 验证结果

- [x] **复现测试验证**：`PromotionDecisionHashRoundTripReproTest` 修复前 `windowClosed` FAILED（`d04a5527… ≠ 5b7fa332…`），修复后 PASSED（`HASH_BEFORE == HASH_AFTER == 38bb07a2…`）；`bidAccepted` 修复前后均 PASSED。
- [x] **promotion 全量回归**：`mvn -Dtest='PromotionDecisionHashRoundTripReproTest,**/promotion/**,**/Promotion*' test` → **117 测试全过，BUILD SUCCESS**。含 snapshot DTO 测试、projection 服务测试、redis 集成测试、fanout 测试、reconciliation 测试。
- [x] **影响面回归**：
  - `PromotionSnapshotService`（snapshot 读 Redis ranking + allocation）——`PromotionSnapshotServiceTest` 通过，ranking 字段顺序变化不影响读取（按 hash key 取值）。
  - `PromotionRedisDecisionAdapter`（Lua JSON → `treeToValue`）——`PromotionRedisDecisionAdapterRedisIntegrationTest` 通过，反序列化按字段名匹配不依赖顺序。
  - `PromotionDecisionProjectionService.settleWindow`（消费 payload winners/walletEffects）——`PromotionDecisionProjectionServiceTest` 通过，`requiredLong`/`requiredString` 按 key 取值不依赖顺序。
  - `PromotionDecisionFanoutService`（realtime 事件）——`PromotionDecisionFanoutServiceTest` 通过。
- [x] **前端不受影响**：JSON 字段名/值不变，仅字段顺序变化；前端 `RankingItem`/`WalletBalance` 按字段名解析。未改前端，无需浏览器回归。
- [x] **端到端浏览器实测**（owner 2026-07-09，`PROMOTION_BPRIME_ENABLED=true` 起后端 + 清理历史烂账后）：
  - 出价：campaign 16005 / postId 332500000000000002，bid 20 积分（userId=14）
  - bid 写入 ✅：`promotion_bid id=20005, bidder=14, bidAmount=20, ACTIVE`
  - 钱包 hold ✅：available 70→50（-20），held 30→50（+20）
  - 后端日志 0 mismatch / 0 version gap / 0 钱包不存在
  - 手动触发结算（窗口 15044 end_at 改到过去，closer 30s 内扫到结算）
  - 窗口 SETTLED ✅ + allocation 生成 ✅：`id=20006, campaign=16005, post=332500000000000002, slot_index=0, clearing_price=1`
  - 钱包结算对账 ✅：available 50→69（释放 19），held 50→30（capture 1 给平台），GSP clearing price=reserve=1 正确

## 6. 遗留事项

- **bprime hash 规范化设计**：根因是 `Map<String,Object>` payload + record value 的序列化顺序不一致。长期更稳健的做法是把 `payload` 改成强类型 record（消除 `Map<String,Object>` 退化歧义），但属重构，不在本 issue 范围。可后续开 cs-refactor。
- **`PromotionAuctionWindowCloser` 的 `winners`/`clearingPrices` payload**：这两个是 `List<Map<String,Object>>`（非 record），`Map.of` 构造，round-trip 时 Map 本身就按字母序，无此 bug。本次无需改。

> 顺手发现：7/7 测试遗留的幽灵用户脏数据——`promotion_bid` 表有一批 `bidder_user_id IN (42,43,44)` 的 bid，但 `users` 表和 `wallet_account` 表里没有这仨用户（钱包表只有 owner_user_id 1~15）。这些窗口结算时 `captureHoldToPlatform` 抛 `BusinessException: 钱包不存在`，会卡住 projection 消费线。本次实测时已把这 24 个幽灵窗口手动标 SETTLED + 清 Redis version + 清 checkpoint + reset consumer offset 跳过历史消息，临时止血。根因（出价时未校验用户/钱包存在）是独立 bug，不在本 issue 范围，可后续另开 issue。

> 顺手发现：`PromotionMysqlIntegrationTest.bidLifecycleAndActiveListing` 全量跑时偶发 `Duplicate entry '...' for key 'promotion_campaign.PRIMARY'`（snowflake ID 碰撞 / 测试间共享 `promotion_campaign` 表数据污染）。撤掉本 issue 改动全量跑同样 fail，单跑 pass，与本 issue 无关。不在本次范围，可后续另开 issue。

## 7. 提交

改动文件：PromotionWalletEffect.java + PromotionRankingItem.java + PromotionDecisionHashRoundTripReproTest.java + fix-note.md

分支：`merge-origin-plan-20260707`（与远程同步，owner 指定在此分支做）。

worktree gate：跳过（attention.md 既定约定——本地可逆改动无需 worktree 隔离；本 fix 2 注解 + 1 测试，git 可追踪可回退，已验证 117 测试全过）。
