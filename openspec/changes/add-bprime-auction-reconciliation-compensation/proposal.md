## Why

B' 推广竞价已经把关键事实分散到 RocketMQ command、Redis hot state、Kafka decision log、MySQL projection、wallet ledger 和 slot allocation。当前对账只覆盖 decision projection replay 和 allocation rebuild，不能发现或修复 Redis 热状态漂移、钱包动作漏做、全链路断点等深层问题。

现在 WebSocket/fanout 已进入本期链路，B' 竞价需要一个专门的对账补偿 change，把“展示实时性”和“事实最终一致”分开：WebSocket 负责显示，对账负责发现并修复事实链路偏差。

## What Changes

- 新增 B' 推广竞价深度对账补偿能力，范围只覆盖 promotion auction，不做全平台统一对账升级。
- 扩展现有 `reconciliation_task` 框架，不另造第二套任务系统。
- 增加 Redis hot ranking / command replay / window state drift 检测和标记。
- 增加 Redis hot state rebuild：从 Kafka decision log 与 MySQL final projection/wallet facts 重建 active 或 recently closed auction window 的热排名与 campaign state。
- 增加 wallet effect repair：按 B' decision wallet effects 和 wallet businessRef 检查 HOLD / CAPTURE / RELEASE 是否存在且参数一致，缺失则补偿，冲突则进入 dead/error。
- 增加 B' chain audit：按 auction window 串联 command、Kafka decision log、projection checkpoint、wallet ledger、slot allocation，生成 repair tasks。
- 保留当前已有最小修复：decision projection replay 和 allocation rebuild。
- 不把 WebSocket delivery 纳入事实修复；fanout 失败只影响展示，客户端可用 snapshot 恢复。

## Capabilities

### New Capabilities
- `bprime-auction-compensation`: Deep reconciliation and compensation for B' promotion auction Redis hot state, wallet effects, and cross-store auction chain drift.

### Modified Capabilities
- `data-reconciliation`: Extend existing reconciliation requirements from first-phase projection repair to B' deep drift detection, hot-state rebuild, wallet effect repair, and chain audit.
- `bprime-position-auctions`: Clarify durable authority boundaries used by compensation: Kafka decision log and MySQL projection/wallet facts are repair sources, Redis and WebSocket are not final authority.
- `wallet-ledger`: Clarify B' wallet effect repair must use existing businessRef idempotency and reject mismatched existing ledger facts.

## Impact

- Backend: reconciliation task types, scan types, reconcilers, promotion B' mappers/services, wallet lookup/repair helpers, and scheduler.
- Data: may add reconciliation task types and optional drift/error metadata; should reuse `reconciliation_task`, `reconciliation_checkpoint`, and `reconciliation_error_log`.
- Infra: reuses existing MySQL, Redis, Kafka, RocketMQ metadata where available; no new middleware.
- Product behavior: normal bid/feed/search/WebSocket flows remain non-blocking; compensation is eventual and operator-visible.
