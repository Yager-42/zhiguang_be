# 业务对象词典

## 这份词典解决什么问题

同一个仓库里，最容易腐化的不是代码本身，而是名词、对象层级和真相源被混写。

这份词典先统一三件事：

1. 当前对象到底是什么
2. 它的真相源在哪里
3. 它是不是别的对象可以依赖的唯一业务定义

## 结构化条目 Schema

后续词典条目统一采用以下字段：

- `id`
- `canonical`
- `aliases`
- `definition`
- `relationships`
- `keywords`
- `tier`
- `status`
- `source`

## 对象层级

| 层级 | 用途 |
| --- | --- |
| 主记录 | 最终业务事实 |
| 运行态 | 当前处理中的临时状态、游标、缓存或锁 |
| 派生结果 | 聚合计数、投影、搜索索引、推荐候选、通知桶 |
| 契约 | DTO、事件、配置、枚举、外部接口边界 |

## 真相源层级

| 真相源 | 适合承载什么 |
| --- | --- |
| MySQL | 最终主记录、事务内写模型、投影表 |
| Redis | 高并发计数、临时状态、缓存、锁、热点保护 |
| Kafka / MQ | 事件传播与异步边界，不是最终事实 |
| Cassandra | feed / 文本类高吞吐存储 |
| MinIO / S3 | 内容对象本体 |
| Elasticsearch | 搜索与联想视图，不是主记录 |
| Java 常量 / 配置 | 状态枚举、ID 命名空间、基础运行参数 |

## 已迁移术语

### `feed.inbox`

- `id`: `feed.inbox`
- `canonical`: `inbox`
- `aliases`: `follow feed push side`
- `definition`: 每个 follower 的 timeline，内容发布后直接推送到用户侧。
- `relationships`: 关联 `feed.author_feed`，与事务 `outbox` 不是同一概念。
- `keywords`: `inbox` `follow feed` `fanout`
- `tier`: `domain`
- `status`: `active`
- `source`: Cassandra `feed_inbox` + Redis cache-aside

### `feed.author_feed`

- `id`: `feed.author_feed`
- `canonical`: `author_feed`
- `aliases`: `pull side recent posts`
- `definition`: 大作者场景下按作者维护的最近内容列表，读时由 follower 拉取。
- `relationships`: 是 `large author` 的内容读取源。
- `keywords`: `author_feed` `pull` `fanout`
- `tier`: `domain`
- `status`: `active`
- `source`: Cassandra `feed_author_feed`

### `event.outbox.transactional`

- `id`: `event.outbox.transactional`
- `canonical`: `outbox (transactional)`
- `aliases`: `mysql outbox`
- `definition`: 事务内写入的可靠事件发布表，用于 CDC / Kafka 桥接。
- `relationships`: 与 feed 侧 `inbox` / `author_feed` 无关。
- `keywords`: `outbox` `cdc` `canal` `kafka`
- `tier`: `domain`
- `status`: `active`
- `source`: MySQL `outbox`

### `knowpost.status`

- `id`: `knowpost.status`
- `canonical`: `status`
- `aliases`: `draft lifecycle`
- `definition`: `draft | publishing | published | publish_failed | deleted`。
- `relationships`: feed 和公开流只包含 `published`。
- `keywords`: `draft` `publishing` `published` `deleted`
- `tier`: `contract`
- `status`: `active`
- `source`: `knowpost` 领域状态定义与写模型

### `knowpost.visible`

- `id`: `knowpost.visible`
- `canonical`: `visible`
- `aliases`: `visibility`
- `definition`: `public | followers | school | private | unlisted`。
- `relationships`: follow timeline 只收 `published` 且 `visible ∈ {public, followers}`；`school` 需要 viewer-scope 检查。
- `keywords`: `visibility` `public` `followers` `school`
- `tier`: `contract`
- `status`: `active`
- `source`: `knowpost` 领域可见性定义

### `author.normal`

- `id`: `author.normal`
- `canonical`: `normal author`
- `aliases`: `small author`
- `definition`: follower 数小于阈值的作者，发布时直接 fanout 进每个 follower 的 inbox。
- `relationships`: 与 `author.large` 配套定义。
- `keywords`: `normal author` `fanout threshold`
- `tier`: `domain`
- `status`: `active`
- `source`: feed fanout 规则

### `author.large`

- `id`: `author.large`
- `canonical`: `large author`
- `aliases`: `big author`
- `definition`: follower 数达到阈值的作者，发布时只写 `author_feed`，读时再拉取。
- `relationships`: 与 `author.normal` 配套定义。
- `keywords`: `large author` `pull feed`
- `tier`: `domain`
- `status`: `active`
- `source`: feed fanout 规则

### `wallet.virtual-currency`

- `id`: `wallet.virtual-currency`
- `canonical`: `虚拟货币`
- `aliases`: `出价单位`
- `definition`: 当前阶段唯一出价单位；封闭内循环，平台发放，不支持提现 / 充值。
- `relationships`: 与 `质量分` 明确区分。
- `keywords`: `虚拟货币` `wallet` `出价`
- `tier`: `domain`
- `status`: `active`
- `source`: 商业化业务定义

### `platform.quality-score`

- `id`: `platform.quality-score`
- `canonical`: `质量分`
- `aliases`: `声誉分` `资质分`
- `definition`: 平台计算的排序 / 权限参考分，不可消耗，不作为出价单位。
- `relationships`: 可影响投标质量评估，但不替代货币。
- `keywords`: `质量分` `bid quality score`
- `tier`: `domain`
- `status`: `active`
- `source`: 商业化业务定义

### `wallet.escrow`

- `id`: `wallet.escrow`
- `canonical`: `托管`
- `aliases`: `escrow`
- `definition`: 平台代持的虚拟货币状态，可走 `release / refund / forfeiture / cancel`。
- `relationships`: 区别于普通余额和冻结。
- `keywords`: `escrow` `release` `refund` `forfeiture`
- `tier`: `domain`
- `status`: `active`
- `source`: `wallet` 领域状态机

### `promotion.slot-auction`

- `id`: `promotion.slot-auction`
- `canonical`: `位竞价`
- `aliases`: `slot auction` `position auction`
- `definition`: 离散商业位置的正向拍卖；固定 M 个位置，top M 占位，按第 M+1 价结算。
- `relationships`: 是当前推广主机制。
- `keywords`: `slot auction` `gsp` `promotion`
- `tier`: `domain`
- `status`: `active`
- `source`: `promotion` 领域业务定义

### `promotion.auction-chain`

- `id`: `promotion.auction-chain`
- `canonical`: `B' 竞价链路`
- `aliases`: `B' auction chain`
- `definition`: `HTTP bid command -> ordered MQ command -> Redis Lua 决策 -> Kafka decision log -> MySQL projection / slot allocation`。
- `relationships`: 固定位置推广直接依赖该链路。
- `keywords`: `B'` `decision log` `projection` `Redis Lua`
- `tier`: `domain`
- `status`: `active`
- `source`: `promotion/bprime`

### `promotion.campaign`

- `id`: `promotion.campaign`
- `canonical`: `推广`
- `aliases`: `promotion campaign`
- `definition`: 创作者为获得固定商业位置而发起的竞价投放；本期不包含 `paid boost`。
- `relationships`: 通过 `auction window` 和 `slot allocation` 生效。
- `keywords`: `promotion` `campaign` `slot`
- `tier`: `domain`
- `status`: `active`
- `source`: `promotion` 领域业务定义

### `promotion.auction-window`

- `id`: `promotion.auction-window`
- `canonical`: `竞价窗口`
- `aliases`: `auction window`
- `definition`: 某类推广资源在固定时间窗内完成收单、排序、定价和分配的批次单位。
- `relationships`: 产出 `slot allocation`。
- `keywords`: `auction window` `snapshot`
- `tier`: `domain`
- `status`: `active`
- `source`: `promotion` 领域业务定义

### `promotion.slot-allocation`

- `id`: `promotion.slot-allocation`
- `canonical`: `位分配`
- `aliases`: `slot allocation`
- `definition`: 竞价窗口结算后的最终占位结果。
- `relationships`: 依赖 `auction window` 和 `decision projection`。
- `keywords`: `allocation` `winner` `slot`
- `tier`: `domain`
- `status`: `active`
- `source`: `promotion` 投影结果

### `promotion.paid-boost`

- `id`: `promotion.paid-boost`
- `canonical`: `付费加权`
- `aliases`: `paid boost`
- `definition`: 历史方案，表示出价影响推荐排序或粉丝触达优先级，但不产生 winner / GSP / slot allocation；当前范围不做。
- `relationships`: 与 `promotion.slot-auction` 明确区分。
- `keywords`: `paid boost` `排序加权`
- `tier`: `domain`
- `status`: `out-of-scope`
- `source`: 商业化 PRD 术语

### `bounty.qna`

- `id`: `bounty.qna`
- `canonical`: `悬赏问答`
- `aliases`: `bounty Q&A`
- `definition`: 提问者付费求解、回答者竞标的知识付费场景；采用招投标，不是拍卖。
- `relationships`: 依赖 `bounty`、`bounty bid`、`bounty submission`。
- `keywords`: `bounty` `tender`
- `tier`: `domain`
- `status`: `planned`
- `source`: 商业化 PRD 术语

### `bounty.bounty`

- `id`: `bounty.bounty`
- `canonical`: `悬赏单`
- `aliases`: `bounty`
- `definition`: 围绕求解问题建立的业务单据，承载赏金、投标、锁定、交付、结算与评分。
- `relationships`: 通常绑定提问型 `knowpost`，但不等同于帖子本身。
- `keywords`: `bounty` `knowpost`
- `tier`: `domain`
- `status`: `planned`
- `source`: 商业化 PRD 术语

### `bounty.bid`

- `id`: `bounty.bid`
- `canonical`: `投标书`
- `aliases`: `bounty bid`
- `definition`: 回答者提交的结构化密封投标，不是公开评论，也不包含完整答案。
- `relationships`: 中标后才会进入 `bounty submission`。
- `keywords`: `bid` `报价` `rough direction`
- `tier`: `domain`
- `status`: `planned`
- `source`: 商业化 PRD 术语

### `bounty.submission`

- `id`: `bounty.submission`
- `canonical`: `交付物`
- `aliases`: `full answer`
- `definition`: 中标者在赏金锁定后提交的完整答案或解决方案。
- `relationships`: 独立于投标书存在。
- `keywords`: `submission` `full answer`
- `tier`: `domain`
- `status`: `planned`
- `source`: 商业化 PRD 术语

### `bounty.tender`

- `id`: `bounty.tender`
- `canonical`: `招投标`
- `aliases`: `tender`
- `definition`: 逆向方向且人工定标的机制；系统可计算投标质量分，但不自动决定中标。
- `relationships`: 区别于逆向拍卖和正向拍卖。
- `keywords`: `tender` `人工定标`
- `tier`: `domain`
- `status`: `planned`
- `source`: 商业化 PRD 术语

### `platform.auction-integration-goal`

- `id`: `platform.auction-integration-goal`
- `canonical`: `接入竞价系统`
- `aliases`: `硬性产品目标`
- `definition`: 复用现成 bytedance B' 竞价链路是当前阶段硬性目标，非可选项。
- `relationships`: 固定位置推广用正向模式；悬赏场景用逆向评分辅助人工定标。
- `keywords`: `B'` `硬性目标`
- `tier`: `decision`
- `status`: `active`
- `source`: 商业化 PRD 术语

### `platform.zhiguang-priority`

- `id`: `platform.zhiguang-priority`
- `canonical`: `zhiguang 业务优先`
- `aliases`: `业务语义优先`
- `definition`: 商业化能力以 zhiguang 内容社区语义为主域，竞价系统是被融入的能力来源，不反向定义主业务模型。
- `relationships`: 约束 promotion / bounty 设计边界。
- `keywords`: `zhiguang priority` `主域`
- `tier`: `decision`
- `status`: `active`
- `source`: 商业化 PRD 术语

### `platform.user`

- `id`: `platform.user`
- `canonical`: `用户`
- `aliases`: `platform user`
- `definition`: zhiguang 平台内唯一主身份主体，贯穿内容、关系、评论、钱包和商业化流程。
- `relationships`: 与 `wallet.account-row` 不同。
- `keywords`: `user` `principal`
- `tier`: `domain`
- `status`: `active`
- `source`: `auth` / `user` / `profile` 主线语义

### `platform.ledger-subject`

- `id`: `platform.ledger-subject`
- `canonical`: `平台账本主体`
- `aliases`: `platform ledger subject`
- `definition`: 钱包 / 托管系统内的专用系统主体，用于发币、发补贴、接收推广收入与罚没；不是普通用户身份。
- `relationships`: 与 `platform.user` 区分。
- `keywords`: `ledger subject` `platform`
- `tier`: `domain`
- `status`: `planned`
- `source`: 商业化 PRD 术语

### `platform.bid-quality-score`

- `id`: `platform.bid-quality-score`
- `canonical`: `投标质量分`
- `aliases`: `bid quality score`
- `definition`: 系统按合法性审查、领域匹配和历史资质计算的投标参考分，仅辅助评标，不自动决定中标。
- `relationships`: 与 `质量分` 相关，但语义更贴近悬赏投标场景。
- `keywords`: `bid quality score` `投标`
- `tier`: `domain`
- `status`: `planned`
- `source`: 商业化 PRD 术语

### `wallet.bid-deposit`

- `id`: `wallet.bid-deposit`
- `canonical`: `投标质押`
- `aliases`: `bid deposit`
- `definition`: 回答者投标时锁定的少量虚拟货币；跑单可没收，未中标或正常交付则退还。
- `relationships`: 区别于主赏金。
- `keywords`: `bid deposit` `质押`
- `tier`: `domain`
- `status`: `planned`
- `source`: 商业化 PRD 术语

### `platform.track-record`

- `id`: `platform.track-record`
- `canonical`: `历史战绩 / 信誉`
- `aliases`: `track record`
- `definition`: 回答者在历史悬赏交付中累积的评价聚合分，正向影响投标质量分与中标机会。
- `relationships`: 可复用 `counter` 聚合能力。
- `keywords`: `track record` `信誉`
- `tier`: `domain`
- `status`: `planned`
- `source`: 商业化 PRD 术语

### `bounty.rough-direction`

- `id`: `bounty.rough-direction`
- `canonical`: `粗方向`
- `aliases`: `rough direction`
- `definition`: 投标允许暴露的有限内容：相关经验、方法路径、里程碑、报价；不含完整解题步骤、源数据、可运行代码和完整推导。
- `relationships`: 与 `full answer` 明确区分。
- `keywords`: `rough direction` `投标范围`
- `tier`: `contract`
- `status`: `planned`
- `source`: 商业化 PRD 术语

### `bounty.full-answer`

- `id`: `bounty.full-answer`
- `canonical`: `完整答案`
- `aliases`: `full answer`
- `definition`: 赏金锁定后由中标者单独提交的完整交付物，锁定前平台不持有。
- `relationships`: 与 `bounty.bid`、`bounty.rough-direction` 区分。
- `keywords`: `full answer` `submission`
- `tier`: `contract`
- `status`: `planned`
- `source`: 商业化 PRD 术语

### `wallet.wallet`

- `id`: `wallet.wallet`
- `canonical`: `钱包`
- `aliases`: `wallet service`
- `definition`: 统一账户服务，承载余额、冻结、托管三态与只追加交易日志；三条商业化线都调用它，不自持独立业务资金。
- `relationships`: 是 promotion / bounty 等商业化流程的资金基础能力。
- `keywords`: `wallet` `ledger` `hold` `escrow`
- `tier`: `domain`
- `status`: `active`
- `source`: `wallet` 领域定义

### `wallet.account-row`

- `id`: `wallet.account-row`
- `canonical`: `账本账户行`
- `aliases`: `ledger account row`
- `definition`: 钱包持久化中的余额归属记录，语义上从属于 `用户`，不是独立认证或独立业务主体。
- `relationships`: 从属于 `platform.user`。
- `keywords`: `ledger` `account row`
- `tier`: `domain`
- `status`: `planned`
- `source`: 商业化 PRD 术语

### `wallet.hold`

- `id`: `wallet.hold`
- `canonical`: `冻结`
- `aliases`: `hold`
- `definition`: 出价或投标质押时锁定的余额，成交或解约前不可用；第二价场景会退回多余冻结。
- `relationships`: 与 `wallet.escrow` 不同。
- `keywords`: `hold` `freeze`
- `tier`: `domain`
- `status`: `active`
- `source`: `wallet` 领域定义

### `platform.commission`

- `id`: `platform.commission`
- `canonical`: `抽成`
- `aliases`: `commission`
- `definition`: 平台对 P2P 交易抽取的佣金；当前阶段为 `0%`，推广位成交价不是抽成。
- `relationships`: 与推广中标价明确区分。
- `keywords`: `commission` `0%`
- `tier`: `domain`
- `status`: `planned`
- `source`: 商业化 PRD 术语
