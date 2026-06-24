## Context

zhiguang 现有 `data-reconciliation` 已有任务表、checkpoint、error log、scheduler、scan service 和多个 `Reconciler`。B' promotion 已有两类最小修复：Kafka decision replay 驱动 projection，`PROMOTION_ALLOCATION_REBUILD` 重建 slot allocation。

缺口在 settled window 的最终事实修复边界：allocation 全缺失时能否自动重建，capture/release 缺失时能否只靠 MySQL settled facts 修复，历史 command 缺失是否算真正故障，部分脏数据又应该何时直接暴露 dead task。这个 change 只补 B' promotion auction 结算后对账补偿，不做全平台对账大改。

## Goals / Non-Goals

**Goals:**
- 复用现有 `reconciliation_task` 框架，不新建任务系统。
- 只扫描 `SETTLED` promotion window，并按 settled facts 直接产出 repair task。
- 增加 wallet effect repair，只覆盖 settled window 的 `CAPTURE`、`RELEASE`。
- 让修复全部幂等：重复任务不重复扣钱、不重复建 allocation、不重复写 projection。
- 把冲突暴露为 `dead` task 和 error log，不用反向流水掩盖错误。
- 支持 operator 对单个 `promotion_auction_window` 手动 rerun。

**Non-Goals:**
- 不做全平台 reconciliation rewrite。
- 不扫描 `OPEN` window，也不修进行中竞价热态。
- 不做 Redis drift detection 或 Redis rebuild。
- 不修 WebSocket delivery；WebSocket 是展示层，snapshot 可恢复。
- 不从 Redis 反写 Kafka/MySQL/wallet 事实。
- 不自动修复缺失的 RocketMQ 历史 command；command 只是入口，decision log 才是确认边界。
- 不做 settled 阶段的 `HOLD` 自动补偿。
- 不做跨集群 SLA、灾难演练、真实 broker 全链路压测。

## Decisions

### 1. Durable facts first

补偿权威顺序固定：

1. Kafka decision log
2. MySQL projection checkpoint / `promotion_bid` / `promotion_auction_window` / `promotion_slot_allocation`
3. Wallet ledger/account
4. RocketMQ command record（仅入口审计）
5. Redis hot state / WebSocket delivery（仅派生展示）

`promotion_auction_decision` 不再存在，也不是补偿事实源。Redis 和 WebSocket 永远不是事实源。command record 缺失但 settled 链完整时，只记告警，不阻断补偿成功。

替代方案：拿 command 或 Redis 去反推 settled 事实。拒绝。入口审计和热状态都不配覆盖 durable facts。

### 2. 新增最小任务类型，不新增任务表或新 target 主键

扩展 `ReconciliationTaskType`：
- `promotion_wallet_effect_repair`

保留现有 `ReconciliationTargetType`：
- `promotion_decision`：用于 projection replay
- `promotion_auction_window`：用于 allocation rebuild 和 wallet effect repair

`promotion_wallet_effect_repair` 不引入新的字符串主键。继续挂在 `promotion_auction_window`，把 `businessRef`、`effectType`、`bidderUserId`、`expectedAmount`、`expectedReason` 放进 `taskPayload`，靠 `dedupeScope(taskType,targetType,targetId,payloadDigest)` 去重。

### 3. 状态机不新增 failed

继续沿用现有四态：
- `pending`
- `running`
- `succeeded`
- `dead`

可重试错误回 `pending` 走指数退避；超过最大重试次数进入 `dead`；明确不可自动修复的冲突第一次就直接 `dead`。不新增 `failed`。

### 4. Scanner 直接产出 repair task

不保留独立的 `promotion_chain_audit` 二层任务。scanner 直接扫描 `SETTLED` window：
- 扫到 projection 缺失：创建 `promotion_decision_projection`
- 扫到 allocation 全缺失：创建 `promotion_allocation_rebuild`
- 扫到单个 capture/release 缺失：创建 `promotion_wallet_effect_repair`
- 扫到部分 allocation、字段推不全、wallet identity 冲突：直接记 `dead`/`error_log`

这样少一层任务分发，职责更短。

### 5. 只扫 settled window，按 settled_at + id 推进

scan source 固定为：
- `promotion_auction_window.status = SETTLED`
- 排序 `settled_at ASC, id ASC`
- checkpoint = `last_settled_at + last_window_id`
- 只扫 `settled_at >= now - configuredLookback`

`configuredLookback` 必须与 Kafka decision topic 的实际 retention 配置对齐，不把“7 天”写死成业务真理。

### 6. Allocation rebuild 只处理全缺失

`promotion_allocation_rebuild` 只处理：
- `promotion_slot_allocation` 全缺失

如果出现这些情况，直接 `dead`：
- allocation 已有部分行但数量不对
- slot 不连续
- 需要依赖已过期 Kafka 明细且 MySQL settled facts 不足

allocation 重建时不盲信 `promotion_bid.status`、`slot_index`、`clearing_price`，而是从 `promotion_bid.bid_amount`、`slot_count`、`reserve_price`、window 时间窗重新计算赢家和 clearing price。

### 7. Wallet repair 只处理 settled 结果

wallet repair 调用现有 `WalletService` 的 businessRef 幂等判等。规则固定：
- 只修 `CAPTURE` / `RELEASE`
- 不修 `HOLD`
- 一个 effect 一个 repair task
- 只为缺失 effect 建任务，不做整窗全量重放
- 同 businessRef 已存在但 owner、amount、reason、businessType、delta 不同，直接 `dead`

如果修 `CAPTURE` / `RELEASE` 所需的 winner、loser、clearing price、businessRef、ownerUserId、amount 任意关键字段推不全，直接 `dead`。

### 8. Manual rerun 也是 settled-only

operator rerun 一个 `promotion_auction_window` 时：
- 先按 MySQL settled facts 重算赢家与 clearing price
- allocation 全缺失时创建一个 `promotion_allocation_rebuild`
- 对每个应有 `CAPTURE` / `RELEASE`：
  - ledger 已存在且身份一致：跳过
  - ledger 缺失：创建单 effect repair task
  - ledger 已存在但身份不一致：直接 `dead`

## Risks / Trade-offs

- [重复修钱包导致多扣] -> 只通过 WalletService businessRef 幂等入口修复，测试覆盖重复 task。
- [只扫 settled 漏掉进行中热态问题] -> 这是主动砍掉的范围；OPEN 热态一致性以后单开 change。
- [MySQL facts 不足导致无法自动修复] -> 直接 `dead`，暴露脏数据，不发明半吊子补偿。
- [任务 payload 复杂] -> payload 使用 JSON，包含 businessRef、effectType、expected ledger identity；dedupeScope 包含 payload digest。
- [历史 command 缺失引起误判] -> command 只记审计告警，不作为 settled repair 的硬失败条件。

## Migration Plan

1. 扩展 OpenSpec 和枚举：settled-window scan type、`promotion_wallet_effect_repair` task type。
2. 增加 settled-window scanner：按 `settled_at + id` 扫描窗口，直接创建 projection/allocation/wallet repair task。
3. 收紧 allocation rebuild：只处理全缺失，部分脏数据直接 `dead`。
4. 实现 wallet effect repair：只修 `CAPTURE` / `RELEASE`，复用 WalletService 幂等判等。
5. 增加 operator manual rerun 对 `promotion_auction_window` 的支持。
6. 补单元测试和局部集成测试，不要求真实 RocketMQ/Kafka 全链路 E2E。

## Open Questions

无阻塞问题。范围已确认：只做 B' 推广竞价 settled-window 对账补偿，不做全平台统一对账。
