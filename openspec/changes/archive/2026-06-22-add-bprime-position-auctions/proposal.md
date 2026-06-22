## Why

zhiguang 商业化推广统一改为固定数量位置竞拍，需完整接入 `/Volumes/lexar/revive/bytedance` 的 B' 高并发竞价链路，而不是继续维护非拍卖 paid boost 和本地批量结算两条分发模型。

现有 archived `add-slot-auction-promotions` 只做 MySQL 窗口批量 GSP，不包含 bytedance 的 ordered command、Redis Lua 权威决策、Kafka decision log、projection 和实时恢复链路；active `add-paid-boost-promotions` 的“买排序权重”模型也与完整竞价链路冲突。

## What Changes

- **BREAKING**: 废止 active `add-paid-boost-promotions`，本期不支持 paid boost / paid ranking weight / `organic score + boost effect`。
- 新增 B' position auction 推广能力：创作者竞拍固定数量商业位置，而不是购买排序权重。
- 将 `feed_top_slot`、`search_top_slot` 建模为固定 `slotCount` 的 position auction resource。
- 引入 bytedance B' 链路：HTTP bid command -> RocketMQ ordered command -> Redis Lua decision -> Kafka decision log -> MySQL projection -> slot allocation / realtime snapshot。
- 保留多槽位 GSP 业务定价：TopN 赢得固定位置，清算价由下一有效出价或 reserve price 决定。
- 将 feed/search 商业分发统一改为读取已投影的 slot allocation；读路径不执行竞价。
- 扩展钱包语义以支持 B' command 幂等、热冻结、winner capture、loser release、超额释放和 projection 对账。
- 扩展对账能力以校验 RocketMQ/Kafka/Redis/MySQL/wallet/slot allocation 一致性。

## Capabilities

### New Capabilities

- `bprime-position-auctions`: bytedance B' 风格固定位置竞拍链路，覆盖 command、Redis Lua decision、decision log、projection、snapshot 和窗口固化。

### Modified Capabilities

- `slot-auction-promotions`: 从本地批量结算升级为 B' position auction；仍输出 slot allocation。
- `recommendation-feed`: 移除 paid boost weighting 方向；feed 只消费 fixed position allocation 商业位。
- `wallet-ledger`: 增加 position auction command/decision/projection 下的冻结、扣减、释放和幂等要求。
- `data-reconciliation`: 增加 B' 链路跨 Redis、Kafka、RocketMQ、MySQL 与钱包事实的对账补偿要求。

## Impact

- OpenSpec: 删除 active `add-paid-boost-promotions`，新 change 取代 archived `add-slot-auction-promotions` 的实现方向。
- API: 推广出价写路径从直接 MySQL submit bid 改为 command submission；查询增加 command status / auction snapshot。
- Infra: 引入 RocketMQ；复用现有 Kafka 和 Redis；新增 Redis Lua 脚本、Kafka decision topic、projection consumer、RocketMQ command topic。
- DB: 扩展 promotion command、decision projection、projection checkpoint、hot-state recovery、slot allocation 固化相关表。
- Wallet: 推广竞拍冻结与清算按 decision/projection 幂等驱动。
- Feed/Search: 仅读取 allocation，不读取 paid boost effect。
- Docs/PRD: 商业化推广从“位竞价 + 付费加权”调整为“固定数量位置竞拍”；paid boost 进入非本期范围。
