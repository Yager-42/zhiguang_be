## Why

PRD 已将“付费加权”从拍卖中独立出来，作为推荐排序和关注触达优先级的商业化分发能力。zhiguang 当前推荐与关注流完全按 organic 信号运行，需要单独定义非拍卖推广能力，而不是把它硬塞进位竞价模型。

## What Changes

- 新增面向推荐排序与关注触达的 `paid boost` 推广能力。
- 支持创作者为内容声明 boost 窗口、预算与 boost 值，不产生 winner、second price 或槽位分配。
- 在推荐排序中引入 `organic score + boost effect` 组合逻辑。
- 在关注流触达受限时，引入基于 boost 的优先级决策。
- 复用钱包支出与推广投放记录，但不复用拍卖 winner 语义。

## Capabilities

### New Capabilities
- `paid-boost-promotions`: 非拍卖型推广投放、预算管理、boost 计算与支出结算。

### Modified Capabilities
- `recommendation-feed`: 推荐排序和关注触达逻辑需要接入 boost 加权与商业标识。

## Impact

- 新增 paid boost 活动、预算、结算与效果字段。
- 修改 recommendation/follow-feed 读写路径，引入 boost 影响逻辑。
- 依赖 `add-wallet-and-escrow` 提供预算冻结、扣减和退款。
- 明确与 `slot-auction-promotions` 平行存在，不共享拍卖 winner 语义。
