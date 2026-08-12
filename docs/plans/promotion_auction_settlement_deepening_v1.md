# Plan：promotion-auction-settlement-deepening-v1 竞价结算模块深化

| 字段 | 值 |
|---|---|
| **plan_id** | `promotion-auction-settlement-deepening-v1` |
| **plan_version** | `1.0.0` |
| **status** | implemented（2026-08-12；验证证据见 §7） |
| **created** | 2026-08-12 |
| **scope** | `com.tongji.promotion` 结算、Redis Stream 终态投影、settled-window 对账、allocation 重建、`promotion_auction_window` schema |
| **目标** | 将分散的第一价格结算规则、钱包影响、分配事实和幂等语义收进一个深模块，以模块接口作为测试表面 |

## 0. 已确认决策

1. 新建 `com.tongji.promotion.settlement` 深模块；不继续把共享结算语义放在宽泛的 `promotion.service` 或 `promotion.bprime` 包中。
2. 深模块同时拥有确定性结算事实推导与事务性结算写集；调用方不再自行解释赢家、第一价格、授权余额、allocation 周期或 businessRef。
3. 生产结算权威输入为 Redis Stream 终态裁决：`AUCTION_SOLD` / `AUCTION_NO_BID`。
4. 恢复权威输入为 MySQL settled facts：`promotion_bid`、`promotion_bid_escrow`、`promotion_auction_window`。事实不足或冲突时直接 `dead`，不读取 Redis/WebSocket 反推。
5. 删除 `LEGACY_BROKER` 及整个 `decisionPath/decision_path` 模型，不保留恒为 `REDIS_STREAM` 的浅字段或兼容分支；用户明确选择不做存量 preflight。
6. 结算写集内聚于深模块；projection checkpoint 仍由外层投影事务拥有，使结算写集与 checkpoint 同次提交，且不把 Stream 消费语义泄漏进结算模块。
7. 对账复用同一不可变结算事实，但 reconciliation task 创建、ledger/allocation 比较仍留在 reconciliation 适配器；promotion 不反向依赖 reconciliation。
8. allocation rebuild 只补建全缺失 allocation；不再次 capture/release、不改 bid/window 状态。钱包缺失继续由独立 wallet repair task 处理。
9. `AUCTION_NO_BID` 显式建模为结算事实：无赢家、无 allocation、释放全部 active escrow。
10. 并发与幂等使用窗口行锁 + 状态守卫；allocation 唯一约束与 WalletService businessRef 是第二道防线。已 SETTLED 且事实一致时幂等返回，冲突时失败。
11. 模块对外保持三个领域动作：生产结算、从 settled facts 推导预期结算、仅补建缺失 allocation。本文不冻结具体 Java 签名。
12. 第一价格、赢家校验、无出价释放、授权余量、allocation 周期、businessRef、重复结算与冲突测试集中到深模块；适配器测试只验证转换/委托，另保留真实 MySQL 事务集成测试。
13. 契约先行：先修改 `ARCHITECTURE_CONTRACT.md` 与 active OpenSpec，再 clean cutover 代码、schema 和测试。

## 1. 目标模块形状

```text
Redis Stream terminal decision ──→ 生产输入适配器 ──┐
                                                     │
MySQL settled facts ─────────────→ 恢复输入适配器 ──┼──→ promotion.settlement 深模块
                                                     │      ├─ 推导 SettlementFacts
allocation rebuild task ─────────→ 重建适配器 ──────┘      ├─ 校验权威输入与持久化事实
                                                            ├─ 执行完整结算写集
                                                            └─ 仅补建缺失 allocation

深模块内部写适配器：
- PromotionAuctionWindowMapper（窗口行锁、状态守卫）
- PromotionBidMapper（winner/loser 状态）
- PromotionBidEscrowMapper（授权额、关闭状态）
- PromotionSlotAllocationMapper（唯一 allocation）
- WalletService（businessRef 幂等 capture/release）
- PromotionAllocationCacheService（事务提交后的缓存刷新触发）
```

`SettlementFacts` 是内部共享的不可变领域事实，至少表达：

- window / resource / allocation period；
- terminal kind：sold 或 no-bid；
- winner（可空）与 first-price amount；
- winner/loser bid transitions；
- 每个 campaign 的 capture/release effect 与稳定 businessRef；
- allocation facts（0 或 1 条）；
- escrow close facts。

不把 mapper、reconciliation task、Redis payload `Map<String,Object>` 或 checkpoint 暴露为模块接口。

## 2. 分阶段实施

### A. 契约先行

修改：

- `docs/contracts/ARCHITECTURE_CONTRACT.md`
  - D10：明确生产终态裁决进入统一结算模块；删除按 `decision_path` 双路径关窗。
  - D11/§7.12：明确对账从 MySQL settled facts 调用同一结算事实推导，allocation rebuild 仅补 allocation。
  - §5.1：从 `promotion_auction_window` 删除 `decision_path`。
  - §7.8：删除 `LEGACY_BROKER` 排空语义；记录深结算模块、窗口行锁、事务与 checkpoint 归属。
- `openspec/specs/slot-auction-promotions/spec.md`
  - 增加生产结算与恢复推导必须共享同一结算事实的 requirement/scenarios。
  - 明确 no-bid 结算事实与 allocation-only rebuild。
- `openspec/specs/bprime-position-auctions/spec.md`
  - 明确 terminal decision 是生产结算权威；projection checkpoint 与结算同事务。
- `openspec/specs/data-reconciliation/spec.md`（若现有 requirement 覆盖 promotion）或对应 active change
  - 明确恢复只使用 MySQL settled facts；冲突直接 dead。

验收：文档中不再把 `LEGACY_BROKER` 或 `decision_path` 描述为有效路径；D10/D11/§7.8 与 OpenSpec 无冲突。

### B. 删除路径模型与 schema clean cutover

删除/修改：

- 删除 `src/main/java/com/tongji/promotion/model/PromotionDecisionPath.java`。
- `PromotionAuctionWindow` 删除 `decisionPath`。
- `PromotionBidRoute` 删除 `decisionPath`；`PromotionCommandSubmissionService` 删除 REDIS_STREAM 分支判断。
- `PromotionAuctionWindowService` 不再写 decisionPath。
- `PromotionBidEscrowTransactionService` / `PromotionBidEscrowService` 删除路径判断与透传。
- `PromotionAuctionWindowMapper`：`listActiveRedisStreamWindows` 重命名为按状态/未追平事实表达的查询；新增 `findByIdForUpdate`。
- `PromotionAuctionWindowMapper.xml` 删除列映射、insert 默认值和 `WHERE decision_path='REDIS_STREAM'`。
- `db/schema.sql` 删除 `promotion_auction_window.decision_path`。
- `PromotionBPrimeSchemaInitializer` 删除新增 decision_path 的兼容逻辑。
- `PromotionSchemaContractTest` 改为断言 schema 不含 decision_path。

用户决策：不做旧窗口只读 preflight 或在线迁移。实施必须是 clean cutover，不留 alias、兼容枚举、默认值或双路径。

验收：全仓除历史计划/归档文档外无 `LEGACY_BROKER`、`PromotionDecisionPath`、运行时代码 `decisionPath`、schema `decision_path` 引用。

### C. 建立 `promotion.settlement` 深模块

建议文件职责（名称在实现前可按仓库习惯微调）：

- `PromotionAuctionSettlementModule`：三个领域动作的模块入口；控制事务性结算写集。
- `PromotionAuctionSettlementFacts`：不可变结算事实及 winner、wallet effect、allocation 子事实。
- `PromotionAuctionSettlementDeriver`：内部实现；从生产终态输入或 MySQL settled facts归一化后推导同一 facts。
- `PromotionAuctionTerminalInput`：生产输入的强类型内部模型，消除 `Map<String,Object>` 在结算模块内传播。
- `PromotionAuctionSettledFactsReader`：内部读取适配器，集中 allocation period、bids、escrows 的加载。

迁移并删除：

- `PromotionAuctionSettlementPlanner`、`PromotionAuctionSettlementPlan` 的规则进入深模块；原文件删除。
- `PromotionAuctionService` 的钱包、bid、allocation 写入进入深模块；原文件删除。
- `PromotionDecisionProjectionService.settleWindow` 的独立结算实现删除，改为把强类型 terminal input 交给深模块。

核心不变量：

1. SOLD 的 terminal winner/price 必须与 MySQL accepted bid/escrow 一致，否则事务失败。
2. winner capture `winningAmount`，release `authorizedAmount-winningAmount`；loser/unused escrow release 全授权额。
3. NO_BID 无 allocation，release 全部 active escrow。
4. allocation period 始终从原 `window_end_at` 开始，跨度等于原窗口时长；反狙击实际结束时间不移动 allocation 起点。
5. 每个 effect 的 businessRef 在一个位置生成：`promotion-bprime:{window}:{campaign}:{capture|release}`。
6. 完整生产结算在窗口行锁下执行；状态转移由条件 update 守卫。
7. 写集完成后关闭 escrow、标记 window SETTLED；缓存刷新不得破坏事务原子性，优先在提交后触发。

### D. 适配投影与窗口关闭

- `PromotionDecisionProjectionService`
  - 保留 Stream ID、version、duplicate、checkpoint 语义。
  - BID_ACCEPTED 仍投影轻量 bid/currentHold。
  - AUCTION_EXTENDED 仍只推进 checkpoint。
  - AUCTION_SOLD/AUCTION_NO_BID 转换成强类型 terminal input，调用深模块完整生产结算。
  - 外层 `@Transactional` 继续覆盖深模块写集和 checkpoint upsert。
- `PromotionAuctionWindowCloser`
  - 删除 `PromotionBidMapper`、`PromotionAuctionService`、decisionPath 分支。
  - 所有 due window 只调用 `PromotionRedisWindowCloser.close`；不在 scheduler 内直接结算。
- `PromotionRedisClosingScanner` 行为不变，仍以 Redis TIME/Lua 产生终态裁决。

验收：生产只有一条关窗/结算权威链：Redis close/batch Lua → Stream terminal decision → projection → 深结算模块 → checkpoint。

### E. 适配对账与 allocation rebuild

- `PromotionAuctionCompensationService`
  - 删除独立 `expectedWalletEffects`、businessRef 拼接和 decisionPath 分支。
  - 调用深模块的“从 MySQL settled facts 推导预期结算”动作。
  - 仍负责读取实际 allocation/ledger、比较、创建 repair/dead task。
- `PromotionAllocationRebuildReconciler`
  - 不再调用完整 settlement。
  - 确认 window=SETTLED 且 allocation 全缺失后，调用“仅补建缺失 allocation”。
  - allocation 部分存在继续直接 NonRetryable/dead。
- `PromotionWalletEffectRepairReconciler`
  - 保持独立，只执行缺失的单个 CAPTURE/RELEASE；payload 来自共享结算事实。

验收：allocation rebuild 不调用 WalletService、不改 bid/window/escrow；对账与生产对同一 settled facts 推导相同 winner、amount、period 和 businessRef。

### F. 测试重构

新增深模块行为测试，覆盖：

1. SOLD 单赢家第一价格与 loser 全释放。
2. terminal winner 与 MySQL top bid 冲突失败。
3. winner 授权额小于 winningAmount 失败。
4. winner 授权余量正确释放。
5. 有 bid 但无对应 active escrow：视为 durable facts 冲突；生产结算事务失败，恢复推导返回不可自动修复冲突并创建 dead task，禁止回退到 `bidAmount` 猜测授权额。
6. unused escrow 全额释放。
7. NO_BID 释放全部 escrow、不写 allocation。
8. allocation 固定从原 window end 开始。
9. 重复结算：已 SETTLED 且事实一致幂等；冲突失败。
10. allocation-only rebuild 只插入 allocation。
11. 稳定 businessRef 与 ledger identity。

迁移测试：

- `PromotionDecisionProjectionServiceTest`：只保留 Stream/version/checkpoint、terminal input 转换及委托断言；删除重复的钱包/分配规则断言。
- `PromotionAuctionServiceTest`：规则迁移后删除原测试文件。
- `PromotionAuctionWindowCloserTest`：删除 legacy 分支用例，只验证 due window 全部走 Redis closer、单窗失败不阻塞其他窗口。
- `PromotionAuctionCompensationServiceTest`：验证共享 facts 的比较和 repair/dead task，不再自测结算算法。
- `PromotionAllocationRebuildReconcilerTest`：断言只调用 allocation rebuild 动作，无完整结算。
- escrow/route/schema 测试删除 decisionPath 构造与断言。

真实验证：

- 扩展 `PromotionMysqlIntegrationTest` 或新增聚焦测试，使用真实 MySQL 验证窗口 `SELECT ... FOR UPDATE`、条件状态迁移、allocation 唯一性及事务回滚。
- 使用现有 Redis 集成测试产生 SOLD/NO_BID terminal decision，再调用投影路径，验证最终 MySQL/window/checkpoint 事实；若 WalletService 真实依赖已在现有集成环境可用，则连同 ledger 验证，否则增加聚焦事务测试。

## 3. 文件级变更清单

### 新增

- `src/main/java/com/tongji/promotion/settlement/**`（深模块与内部事实模型）
- `src/test/java/com/tongji/promotion/settlement/**`（模块行为测试）
- 可选：聚焦 MySQL 事务集成测试文件（仅当扩展现有测试会混杂职责）

### 删除

- `src/main/java/com/tongji/promotion/model/PromotionDecisionPath.java`
- `src/main/java/com/tongji/promotion/service/PromotionAuctionSettlementPlanner.java`
- `src/main/java/com/tongji/promotion/service/PromotionAuctionSettlementPlan.java`
- `src/main/java/com/tongji/promotion/service/PromotionAuctionService.java`
- `src/test/java/com/tongji/promotion/service/PromotionAuctionServiceTest.java`

### 重点修改

- `docs/contracts/ARCHITECTURE_CONTRACT.md`
- promotion / reconciliation OpenSpec
- `db/schema.sql`
- `PromotionBPrimeSchemaInitializer`
- `PromotionAuctionWindow`、`PromotionBidRoute`
- `PromotionAuctionWindowMapper(.xml)`、bid/escrow/allocation mapper（按深模块需要增加行锁/守卫 SQL）
- `PromotionAuctionWindowService`
- `PromotionCommandSubmissionService`
- `PromotionBidEscrowTransactionService`、`PromotionBidEscrowService`
- `PromotionDecisionProjectionService`
- `PromotionAuctionWindowCloser`
- `PromotionRedisStreamProjector`（活跃窗口查询重命名）
- `PromotionAuctionCompensationService`
- `PromotionAllocationRebuildReconciler`
- 相关单元、schema contract、MySQL/Redis 集成测试

## 4. 实施顺序与提交门禁

1. 契约/OpenSpec 更新完成且内部一致。
2. 新建深模块 facts/deriver，并用行为测试锁定所有不变量。
3. 投影切到深模块；运行投影与深模块测试。
4. 对账/rebuild 切到共享 facts 与 allocation-only 动作；运行 reconciliation 聚焦测试。
5. 删除旧 planner/service 与 LEGACY/decisionPath 全链路。
6. schema/initializer/mapper clean cutover；运行 schema contract 与 MySQL mapper 测试。
7. 运行 promotion + reconciliation 聚焦测试集。
8. 启动真实应用，构造一个 SOLD 和一个 NO_BID 窗口，观察 Stream terminal → projection → wallet/allocation/window/checkpoint；重复处理相同 terminal 事件验证幂等。
9. 最后更新架构契约版本、计划状态与验证证据。

## 5. 完成标准

- 一个深结算模块拥有全部第一价格结算实现；调用方不再自行拼接规则或 businessRef。
- 生产结算与恢复推导共享同一不可变结算事实。
- 生产写集与 projection checkpoint 同事务。
- allocation rebuild 只补 allocation；wallet repair 仍按 effect 独立幂等执行。
- `LEGACY_BROKER`、`PromotionDecisionPath`、运行时 `decisionPath`、schema `decision_path` 全部 clean cutover 删除。
- 深模块接口成为唯一规则测试表面；适配器测试不复制结算规则。
- SOLD、NO_BID、重复终态、事务回滚和 allocation-only rebuild 均有可复现验证证据。

## 6. 变更性质、影响与发布门禁

### 6.1 不是纯架构搬迁

深模块、共享结算事实与测试表面收敛属于架构重构；以下是有意的契约、schema 或恢复行为变更：

1. 删除 `LEGACY_BROKER`、`PromotionDecisionPath` 与数据库 `decision_path`。
2. allocation rebuild 从“重放完整结算写集”收窄为“只补全缺失 allocation”。
3. 缺失 active escrow 不再回退到 `bidAmount`，而是生产失败、恢复 `dead`。
4. 重复结算从 allocation-count 早退改为窗口行锁、状态守卫与事实一致性判等。

正常 REDIS_STREAM 窗口的业务结果必须保持不变：单赢家、英式第一价格、winner capture/余量 release、loser/unused escrow 全释放、NO_BID 无 allocation、固定 allocation 起点。

### 6.2 性能影响预期

- 出价热链路不得增加 Redis/MySQL 调用、固定等待或队列；`PromotionWindowBidCombiner` 与批量 Lua 不在本次重构范围。删除 `decisionPath` 分支只减少一次本地判断。[INFERENCE] 正常竞价吞吐和 ACK 延迟应基本不变。
- 终态结算每窗口增加一次 `SELECT ... FOR UPDATE` 窗口行锁；该成本仅发生在终态投影/重复结算竞争时，用于换取明确的并发幂等。[INFERENCE] 单次结算事务会有微小固定开销，但不影响逐出价上限。
- allocation rebuild 不再调用 WalletService 或重写 bid/window/escrow，[INFERENCE] 恢复任务的数据库与钱包写入会减少。
- allocation 缓存刷新移到事务提交后，避免把缓存回源工作放在结算事务内。[INFERENCE] 事务持锁时间应缩短；allocation 对读路径可见时间可能晚一个提交后回调调度间隔。

### 6.3 部署前提

用户已明确选择不实现旧窗口 preflight 或在线迁移。因此 clean cutover 假定目标数据库不存在尚未结算且依赖 `LEGACY_BROKER` 的窗口。实现不会检查该假设；若假设不成立，旧窗口无法由删除后的路径正确关窗。这是已接受的发布风险，不是模块内部可恢复场景。

### 6.4 功能与性能门禁

1. 深模块行为测试覆盖 SOLD、NO_BID、授权余量、unused escrow、固定 allocation 周期、重复结算和冲突。
2. 真实 MySQL 测试证明结算写集与 projection checkpoint 同事务提交/回滚，窗口行锁和状态守卫生效。
3. Redis terminal → projection 烟测分别完成一个 SOLD 与 NO_BID，并重复投递验证幂等。
4. allocation rebuild 验证只写 allocation，WalletService、bid、escrow、window 均无调用或状态变化。
5. 复跑现有 promotion WebSocket realistic 场景；ACK missing/backpressure 不增加，P95/P99 不出现可重复退化，Redis EVAL/commands-per-batch 与改造前一致。
6. 终场延迟继续满足现有 `< 2s` 门禁；单独记录 terminal projection 事务耗时、锁等待和 allocation 可见时间。

## 7. 实施与验证证据（2026-08-12）

- clean cutover：生产代码、schema 与 loadtest seed 已删除 `LEGACY_BROKER`、`PromotionDecisionPath`、`decisionPath/decision_path`；历史 plan/enhancement 中仅保留原决策记录。
- 功能聚焦集：`mvn -Dtest=PromotionAuctionSettlementModuleTest,PromotionDecisionProjectionServiceTest,PromotionAllocationRebuildReconcilerTest,PromotionAuctionCompensationServiceTest,PromotionAuctionWindowCloserTest,PromotionSchemaContractTest,PromotionMysqlIntegrationTest,PromotionRedisDecisionAdapterRedisIntegrationTest,PromotionRedisStreamProjectorRedisIntegrationTest test`，47 tests，0 failures/errors/skips。
- 真实 MySQL + Redis 集成：`PromotionMysqlIntegrationTest`、`PromotionRedisDecisionAdapterRedisIntegrationTest`、`PromotionRedisStreamProjectorRedisIntegrationTest`，26 tests，0 failures/errors/skips；覆盖窗口行锁/状态守卫、settlement+checkpoint 回滚、SOLD/NO_BID terminal 与 Stream 投影读取。
- 全量回归：`mvn test`，BUILD SUCCESS。
- 应用烟测：本机 MySQL/Redis/Kafka/Cassandra/Elasticsearch/MinIO 下以 `promotion.bprime.enabled=true` 启动，8080 health ready；WebSocket realistic 场景完成 20 VU、4,018 bids/acks，missing=0、UNAVAILABLE=0、protocol error=0，accepted=26，ack p95/p99=2ms/2ms，public delta p95/p99=200.1ms/201ms，全部阈值通过。
- allocation-only：`PromotionAllocationRebuildReconcilerTest` 验证仅委托 `rebuildMissingAllocation`；深模块测试验证已有 allocation 拒绝、无钱包/bid/window/escrow 写入。
- 终场 `<2s` 门禁未以独立到期窗口重新计时；本次代码未修改 close.lua/scanner，且 realistic 场景窗口未在 20s 负载内到期。该项沿用 2026-08-12 英式迁移报告中 500/500 `WINDOW_CLOSED` 与负载下完整结算证据，不把本轮未观测项表述为新测量。
