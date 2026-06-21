## Context

zhiguang 现有内容分发链路已经有推荐 feed、关注 feed 和搜索，但没有商业化离散槽位。PRD 已经确定 `feed 置顶位` 和 `search 置顶位` 都属于位竞价，而不是付费加权。`bytedance` 现有拍卖系统具备出价、排名、冻结和结算基础，但其原模型围绕商品与直播竞拍，需要改造成内容推广资源模型。

## Goals / Non-Goals

**Goals:**
- 建立 zhiguang-native 的 `promotion` 位竞价模型，覆盖 feed/search 两类离散资源。
- 支持时间窗收单、批量排序、GSP 二价结算、位分配缓存读取。
- 用钱包冻结与结算对接推广支出。
- 在 feed/search 返回中提供商业位标识与分配结果。

**Non-Goals:**
- 不在请求内实时运行竞价。
- 不引入 `bytedance` 商品、直播房间、WebSocket、RocketMQ 高并发链路。
- 不把付费加权塞进拍卖域。

## Decisions

### 1. 顶层业务术语统一为 `promotion`

zhiguang 业务优先，资源不是 `product`，而是内容分发中的推广位。统一使用：
- `promotion campaign`
- `auction window`
- `promotion bid`
- `slot allocation`

不保留：
- `product`
- `auction_operator`
- `buyer_account`

### 2. `feed_top_slot` 与 `search_top_slot` 属于同一 capability

两者都满足相同机制：离散槽位、时间窗收单、GSP 定价、一次性结算、缓存读路径接入。差异只在资源枚举和渲染入口，因此放在同一 change 和同一 capability 下。

### 3. 竞价在时间窗闭合时批量结算，不在请求路径内执行

每个资源按时间窗收单并缓存结算结果。请求读取 feed 或 search 时，只读取当前有效窗口的位分配结果，不触发拍卖计算。这样保持内容读路径的稳定延迟，也符合 PRD 的“排期级 / 时间窗”要求。

### 4. 结算采用多槽位 GSP 二价，并支持保留价

窗口关闭后按出价排序，前 M 名获得 M 个槽位。每个中标者支付其后一个有效出价或资源保留价中的较高者。创建出价时冻结申报价，结算时扣除成交价并释放多余冻结。

备选：
- 一价拍卖：更简单，但与 PRD 决策不符。
- 请求内动态竞价：会把读路径变成重计算路径。

### 5. `KnowPostTopPatch` 由人工置顶能力转为推广位分配读取

feed 置顶位不再依赖人工给帖子打永久 `top` 标。推广位结果来自当前窗口的 `slot allocation`。如果保留人工 override，也只能作为后台运营兜底，而不是主业务路径。

### 6. 读路径必须输出商业标识与频控约束

推广内容读取时带显式商业标识，feed/search 都要遵守资源侧数量上限。后端输出结果要区分 promoted 与 organic，避免前端或调用方把商业位误当普通内容。

## Risks / Trade-offs

- [改造旧拍卖域命名] → 需要系统性替换旧术语，否则代码和文档会同时表达两套业务模型。
- [窗口批量结算] → 结果不是实时更新，用户体验取决于窗口粒度；通过短窗口与结果缓存平衡。
- [feed/search 同 change] → 改动面较广；但拆开只会复制同一拍卖能力。
- [人工置顶迁移] → 需要明确旧 `top` 行为的新角色，避免双轨规则冲突。
