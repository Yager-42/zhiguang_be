## Why

B' 推广竞价已经把关键事实分散到 RocketMQ command、Kafka decision log、MySQL projection、wallet ledger 和 slot allocation。当前对账只覆盖 decision projection replay 和 allocation rebuild，但没有把 settled window 的最终事实修复边界讲清楚：什么时候可以只靠 MySQL settled facts 重建 allocation，什么时候可以补 capture/release，什么时候必须直接暴露 dead task。

这个 change 要把范围收紧到结算后事实一致性：只处理 settled window 的 projection、allocation、wallet settlement，对进行中竞价热态、WebSocket 展示和 RocketMQ 历史 command 不做自动修复。

## What Changes

- 新增 B' 推广竞价 settled-window 对账补偿能力，范围只覆盖 promotion auction，不做全平台统一对账升级。
- 扩展现有 `reconciliation_task` 框架，不另造第二套任务系统。
- 增加 settled window 扫描：只扫描已结算窗口，直接产出 projection replay、allocation rebuild 和 wallet effect repair 任务，不引入第二层 audit task。
- 增加 wallet effect repair：只覆盖 settled window 的 `CAPTURE` / `RELEASE`，按 wallet businessRef 检查是否缺失或冲突；缺失则补偿，冲突则进入 dead/error。
- 保留当前已有最小修复：decision projection replay 和 allocation rebuild。
- 不把 Redis hot state、WebSocket delivery 或 RocketMQ 历史 command 纳入事实修复；它们不是本 change 的自动补偿目标。

## Capabilities

### New Capabilities
- `bprime-auction-compensation`: Settled-window reconciliation and compensation for B' promotion auction allocation and wallet settlement facts.

### Modified Capabilities
- `data-reconciliation`: Extend existing reconciliation requirements from first-phase projection repair to settled-window allocation rebuild, wallet effect repair, and operator rerun for B' promotion auctions.
- `bprime-position-auctions`: Clarify durable authority boundaries used by compensation: Kafka decision log and MySQL settled projection/wallet facts are repair sources; command records are audit-only, Redis and WebSocket are not compensation targets in this change.
- `wallet-ledger`: Clarify B' wallet effect repair must use existing businessRef idempotency and reject mismatched existing ledger facts.

## Impact

- Backend: reconciliation task types, settled-window scan type, reconcilers, promotion B' mappers/services, wallet lookup/repair helpers, scheduler, and operator rerun endpoint logic.
- Data: may add reconciliation task types and settled-window checkpoint metadata; should reuse `reconciliation_task`, `reconciliation_checkpoint`, and `reconciliation_error_log`.
- Infra: reuses existing MySQL, Redis, Kafka, RocketMQ metadata where available; no new middleware.
- Product behavior: normal bid/feed/search/WebSocket flows remain non-blocking; compensation is eventual, settled-only, and operator-visible.
