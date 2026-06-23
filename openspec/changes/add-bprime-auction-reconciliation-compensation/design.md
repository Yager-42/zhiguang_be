## Context

zhiguang 现有 `data-reconciliation` 已有任务表、checkpoint、error log、scheduler、scan service 和多个 `Reconciler`。B' promotion 已有两类最小修复：`PROMOTION_DECISION_PROJECTION` 重放 decision projection，`PROMOTION_ALLOCATION_REBUILD` 重建 slot allocation。

缺口在更深层：Redis hot ranking / replay state 可能漂移，wallet HOLD/CAPTURE/RELEASE 可能缺失或参数冲突，promotion auction window 可能在 command、decision、projection、wallet、allocation 之间断链。这个 change 只补 B' promotion auction 对账补偿，不做全平台对账大改。

## Goals / Non-Goals

**Goals:**
- 复用现有 `reconciliation_task` 框架，不新建任务系统。
- 增加 B' chain audit：按 auction window 检查 decision、projection、wallet、allocation、Redis hot state。
- 增加 Redis drift detection 和 hot-state rebuild。
- 增加 wallet effect repair，覆盖 B' HOLD、CAPTURE、RELEASE。
- 让修复全部幂等：重复任务不重复扣钱、不重复建 allocation、不重复写 projection。
- 把冲突暴露为 failed/dead task 和 error log，不用反向流水掩盖错误。

**Non-Goals:**
- 不做全平台 reconciliation rewrite。
- 不修 WebSocket delivery；WebSocket 是展示层，snapshot 可恢复。
- 不从 Redis 反写 Kafka/MySQL/wallet 事实。
- 不自动修复缺失的 RocketMQ 历史 command；command 只是入口，decision log 才是确认边界。
- 不做跨集群 SLA、灾难演练、真实 broker 全链路压测。

## Decisions

### 1. Durable facts first

补偿权威顺序固定：

1. Kafka decision log 或 MySQL `promotion_auction_decision`
2. MySQL projection checkpoint / `promotion_bid` / `promotion_slot_allocation`
3. Wallet ledger/account
4. Redis hot state
5. WebSocket delivery

Redis 和 WebSocket 永远不是事实源。Redis 可重建，WebSocket 可丢。

替代方案：Redis 当前排名优先。拒绝。Redis 是热状态，拿它反改 ledger 或 decision 是数据腐败。

### 2. 新增任务类型，不新增任务表

扩展 `ReconciliationTaskType`：
- `promotion_redis_drift_mark`
- `promotion_redis_hot_rebuild`
- `promotion_wallet_effect_repair`
- `promotion_chain_audit`

必要时扩展 `ReconciliationTargetType`：
- `promotion_wallet_effect`
- `promotion_redis_hot_state`

现有 `dedupeScope(taskType,targetType,targetId,payloadDigest)` 已够用。不要加专用补偿表，除非实现时发现任务 payload 已经塞不下必要字段。

### 3. Chain audit 只负责发现和派发

`PromotionAuctionChainAuditReconciler` 不直接做所有修复。它读取一个 auctionWindowId，检查：
- accepted/closed decision 是否有 projection
- closed window 是否有 allocation
- decision walletEffects 是否有 ledger
- Redis ranking/campaign/replay 是否和 durable facts 对得上

发现问题后派发单点 repair task。这样函数短，责任清楚。

### 4. Wallet repair 必须整组判等

wallet repair 调用现有 `WalletService` 的幂等 businessRef 机制。若同 businessRef 已存在但 owner、amount、reason、businessType、delta 不同，任务失败并记录错误。

不要写“补一笔反向流水”来把余额调平。那是烂账。

### 5. Redis rebuild 从完整 durable facts 重建

Redis rebuild 只在 durable facts 没有明显 gap 时执行。重建内容：
- window state
- campaign bid state
- command replay / decision replay
- ranking zset/hash

如果 decision version 或 close facts 不完整，任务进入 failed/dead，不从半截事实拼 Redis。

### 6. Scan 策略分两层

轻扫描：周期性扫描 recently active / recently closed promotion windows，创建 `promotion_chain_audit`。

重修复：audit 派发具体 repair task。避免 scheduler 直接做深修复，也避免每次扫描都重建 Redis/wallet。

## Risks / Trade-offs

- [重复修钱包导致多扣] -> 只通过 WalletService businessRef 幂等入口修复，测试覆盖重复 task。
- [Redis rebuild 用了不完整事实] -> rebuild 前检查 decision/projection 连续性；发现 gap 就 dead task。
- [全链路 audit 太慢] -> 只扫最近窗口和异常窗口，使用 checkpoint 分批。
- [任务 payload 复杂] -> payload 使用 JSON，包含 decisionId、businessRef、effectType、expected ledger identity；dedupeScope 包含 payload digest。
- [与当前 WebSocket change 重叠] -> 本 change 不修 fanout；只保证 snapshot/rebuild 后客户端可恢复展示。

## Migration Plan

1. 扩展 OpenSpec 和枚举：task types、scan types、target types。
2. 增加 B' audit scanner：扫描 recent promotion auction windows，创建 chain audit task。
3. 实现 chain audit reconciler，只派发 projection/allocation/wallet/Redis repair tasks。
4. 实现 Redis drift marker 和 Redis hot-state rebuild。
5. 实现 wallet effect repair，复用 WalletService 幂等判等。
6. 增加 operator manual rerun 对 promotion window / decision / wallet effect 的支持。
7. 补单元测试和局部集成测试，不要求真实 RocketMQ/Kafka 全链路 E2E。

## Open Questions

无阻塞问题。范围已确认：只做 B' 推广竞价深度对账补偿，不做全平台统一对账。
