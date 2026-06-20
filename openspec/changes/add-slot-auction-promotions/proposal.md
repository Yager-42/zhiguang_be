## Why

PRD 已确定 feed/search 离散推广位采用时间窗 GSP 位竞价，而 zhiguang 现有系统只有手动置顶和普通搜索结果，没有可计费、可冻结、可结算的推广位分配能力。需要把 `bytedance` 可复用竞价核心改造成符合 zhiguang 内容社区语义的位竞价推广能力。

## What Changes

- 新增面向内容社区的推广活动、竞价窗口、推广出价与位分配能力。
- 支持 `feed_top_slot` 与 `search_top_slot` 两类离散资源在时间窗内批量竞价与 GSP 二价结算。
- 用钱包冻结与结算替代现有手动 `KnowPostTopPatch` 置顶语义。
- 在 feed/search 读路径插入带商业标识的已分配推广位，不做请求内实时拍卖。
- 移除 `bytedance` 原 `product/auction_operator/buyer_account/live room` 等场景术语，改为 zhiguang `promotion` 语义。

## Capabilities

### New Capabilities
- `slot-auction-promotions`: 推广活动、竞价窗口、推广出价、GSP 定价与位分配。

### Modified Capabilities
- `recommendation-feed`: 首页 feed 需要在 organic 混排前插入已结算的 feed 置顶推广位。

## Impact

- 新增推广、竞价窗口、出价、位分配表与相关 API。
- 改造 `bytedance` auction/bidding/wallet reservation 核心，但不引入其商品、直播房间、WebSocket、独立认证代码。
- `knowpost` feed 渲染与 `search` 查询结果需要接入商业位读取与标识输出。
- 依赖 `add-wallet-and-escrow` 提供冻结与结算。
