## Context

zhiguang 已有推荐 feed 与关注 feed，但两者都只表达 organic 分发。PRD 明确把“付费加权”定义为非拍卖：没有 winner，没有 GSP，没有多槽位清算。它更接近“创作者购买额外分发权重”而不是购买离散曝光位。因此这个 change 必须与 `slot-auction-promotions` 明确分域。

## Goals / Non-Goals

**Goals:**
- 建立非拍卖 `paid boost` 推广能力。
- 在推荐排序中接入 boost 权重。
- 在关注流触达受限时接入 boost 优先级。
- 复用统一钱包账务，不重复发明支出语言。

**Non-Goals:**
- 不引入 winner、second price、slot allocation。
- 不把 paid boost 改写成伪拍卖。
- 不修改推荐引擎外部协议本身，只改本地排序与触达逻辑。

## Decisions

### 1. `paid boost` 独立于拍卖域

`paid boost` 与 `slot auction` 共享“推广”顶层语义，但下层模型分开：
- `slot auction` 关心窗口、排名、赢家、清算价
- `paid boost` 关心投放窗口、预算、boost 值、支出结算

这样可以避免把非拍卖业务塞进 `AuctionSession/BidRankingEntry` 之类旧模型。

### 2. paid boost 以预算和 boost 值表达，不以 winner 表达

每个 boost 活动描述：
- 目标内容
- 生效渠道：推荐排序或关注触达
- 投放窗口
- boost 值
- 预算

预算用于约束最大可消费额度；boost 值用于影响排序或触达优先级。

### 3. 预算先冻结，按实际生效结算，窗口结束释放剩余

创建 boost 活动时冻结预算，活动运行期间按实际生效的计费规则结算，窗口结束释放未消费部分。这样能和统一钱包语言对齐，也能避免单纯“预扣到底”导致退款语义缺失。

### 4. 推荐排序改为 organic score + boost effect

推荐结果仍以本地或外部引擎给出的 organic relevance 为基础，再叠加 paid boost 效果。boost 不能绕过可见性、删除状态或商业标识约束。

### 5. 关注流 boost 只作用于受限场景下的优先级

PRD 里 boost 作用于“收件箱容量受限时”的触达优先级。实现上不要求把所有 organic 行为都改成广告分发，而是在候选过多、配额受限或截断时，优先保留 boost 更高的内容。

## Risks / Trade-offs

- [预算结算规则需要落地] → 规格先锁定“冻结-消费-释放”骨架，具体计费公式在实现中需保持可配置。
- [修改 recommendation-feed] → 要避免把商业信号直接污染外部推荐引擎协议；商业加权应留在本地混排层。
- [关注流受限场景不常显式存在] → 需要在实现中把“受限”落到明确配额或截断点，否则规则落不实。
