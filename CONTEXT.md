# CONTEXT — zhiguang glossary

A glossary of domain terms. No implementation details — for those, see `openspec/` and `docs/superpowers/`.

## Feed / Timeline

- **inbox** — per-follower timeline of posts pushed to that user (the "push" half of the follow feed). Backed by Cassandra `feed_inbox` (+ Redis cache-aside). Not the same as the MySQL transactional outbox.
- **author_feed** — per-author recent-posts list, the "pull" source that followers of a large author read from. Backed by Cassandra `feed_author_feed`. This term **deliberately replaces the overloaded word "outbox" on the feed side** to avoid collision with the table below.
- **outbox (transactional)** — the MySQL `outbox` table (`aggregate_type`/`aggregate_id`/`type`/`payload`/`created_at`) used for reliable event publishing; `content_published` is written here, then Canal CDC bridges the row to the `canal-outbox` Kafka topic. Unrelated to the feed inbox/author_feed.

## Post lifecycle & visibility

- **status** — `draft` | `publishing` | `published` | `publish_failed` | `deleted`. The follow feed and public feed include only `published` rows.
- **visible** — `public` | `followers` | `school` | `private` | `unlisted`. The follow timeline shows `published` posts with `visible ∈ {public, followers}`; `school` requires a viewer-scope check; `private` and `unlisted` are excluded from feeds.

## Author tiers (fanout)

- **normal author** — `< threshold` followers (default 10000): publish is pushed into every follower's inbox.
- **large author** — `≥ threshold` followers: publish is written only to `author_feed`; followers pull at read time (cached).

## 商业化 / 竞价（竞价系统接入 PRD 引入）

- **虚拟货币** — 本期唯一出价单位；封闭内循环，平台发放，本期不可提现/充值。出价即承诺扣减账户余额。_Avoid_: 质量分（作为货币时，见下）、金币、credit、代币。
- **质量分** — 平台计算的声誉/资质分，**不可消耗**，仅用于排序与权限，**不作为出价单位**。PRD 旧文将其与虚拟货币混用，现统一：出价一律用虚拟货币。
- **托管（escrow）** — 悬赏流程中平台代持的虚拟货币；按状态机可走释放（release）/ 退款（refund）/ 没收（forfeiture）/ 取消（cancel），区别于普通账户余额。PRD v0.2 已删除补偿池，故无补偿去向。
- **位竞价（slot auction）** — 离散广告位（feed 置顶、search 置顶）的正向拍卖；M 个位，top M 出价占位、付第 M+1 价（GSP）。本期首批落地的正向拍卖。
- **推广（promotion）** — 创作者为获得曝光或分发优势而发起的商业化投放语义总称，上承位竞价与付费加权两类机制；不是固定 SKU 套餐。_Avoid_: package、product、广告商品
- **竞价窗口（auction window）** — 某一类推广资源在给定时间窗内完成收单、排序、定价与出位分配的批次单位。_Avoid_: product auction、live session
- **位分配（slot allocation）** — 竞价窗口结算后得到的具体占位结果，描述某推广在某资源位与某时间窗中的最终展示归属。_Avoid_: winner（在多槽位场景下未必准确）、成交商品
- **付费加权（paid boost）** — 出价线性/分段影响排序或投递权重（推荐位权重、粉丝触达优先级）；**非拍卖**，无赢家无第二价，区别于位竞价。
- **出价值（quoted boost value / effective boost value）** — 付费加权中由竞价系统适配层按创作者出价换算出的最终加权值；它服务于排序或触达优先级，不等同于用户原始出价。 _Avoid_: 手填 boost 值、最终出价
- **boost 活动（paid boost campaign）** — 创作者针对推荐排序（`home_recommendation`）或关注触达（`follow_delivery`）开启的非拍卖推广投放，含出价、有效 boost 值、单价、总预算、消耗与投放窗口；创建即冻结预算，不产拍卖赢家。 _Avoid_: 拍卖活动、auction campaign
- **boost 投放事实（paid boost delivery）** — 内容一次被 boost 规则实际送达（返回给登录客户端）后的可结算记录；同一 viewer 在同一 delivery bucket 内重复送达只累计 `delivery_count` 到同一条事实，由定时聚合器扣费，不在读路径直接扣 wallet，也不等于拍卖赢家。
- **悬赏问答（bounty Q&A）** — 提问者付费求解、回答者竞标的知识付费；**方向逆向（1 买方 N 卖方），机制为招投标（非拍卖）**：提问者人工评标选人，系统只给参考分。
- **悬赏单（bounty）** — 围绕某个求解问题建立的付费求解业务单据，承载赏金、投标、锁定、交付、结算与评分状态；通常绑定提问型 `knowpost`，但不等同于帖子本身。_Avoid_: 悬赏评论、逆向拍卖单
- **投标书（bounty bid）** — 回答者对悬赏单提交的结构化密封投标，包含战绩、方法路径、里程碑、报价与质量分参考，不是公开评论，也不包含完整答案。_Avoid_: comment、reply、公开回答
- **交付物（bounty submission）** — 中标者在赏金锁定后提交的完整答案或解决方案，独立于投标书存在。_Avoid_: 投标正文、comment content
- **招投标（tender）** — 逆向方向 + **人工定标**；区别于「逆向拍卖」（机器按 min 报价 / max 综合分自动定标）。悬赏采用此机制：**人工定标，但投标质量分由竞价系统逆向模式计算**（接入现成竞价系统为硬性产品目标）。
- **接入竞价系统（硬性产品目标）** — 复用团队现成竞价系统是本期**硬性指标**，非可选项。各场景角色：位竞价 = 正向模式（选位+定价）；悬赏 = 逆向模式（算投标质量分，人工仍定标）；付费加权 = 竞价系统提供出价值。具体形态（服务/库、能否改、支持哪些模式）待确认。
- **zhiguang 业务优先** — 商业化能力以 zhiguang 现有内容社区语义为主域；竞价系统是被融入、被改造的能力来源，不反向定义 zhiguang 的业务模型。_Avoid_: 竞价系统优先、按原拍卖系统建模
- **用户** — zhiguang 平台内唯一主身份主体，贯穿内容、关系、评论、钱包与商业化流程。_Avoid_: account（除非特指底层账本账户行）、buyer、seller
- **平台账本主体** — 钱包/托管系统内专用系统主体，用于发币、发补贴、接收推广位成交收入与接收罚没；不用于 P2P 托管放款过桥，不是普通用户身份，也不是独立认证主体。_Avoid_: 平台用户、商业账户、运营账号
- **投标质量分（bid quality score）** — 系统按"合法性审查 + 领域匹配分 + 历史资质分"算出的投标参考分，**仅辅助提问者评标，不自动决定中标**。
- **投标质押（bid deposit）** — 回答者投标时锁定的少量虚拟货币；中标后跑单则没收，未中标或正常交付则退还。区别于悬赏主赏金（由提问者付）。
- **历史战绩 / 信誉（track record）** — 回答者在历史悬赏交付中累积的评价（提问者评分）聚合分，复用 `counter` 存聚合；正向影响投标质量分与能否中标。在"无退款"结算模型下，这是交付垃圾答案唯一的（软）威慑。
- **粗方向（rough direction）** — 投标允许暴露的内容：相关经验/战绩、方法路径（方法论层面，≤N 字）、预估里程碑、报价；**不含**解题步骤/源数据/可运行代码/完整推导。安全性靠防线一×防线二复合保证（问题本身 LLM 答不好，故粗方向泄露也补不出答案）。
- **完整答案（full answer）** — 锁定赏金后中标者单独提交的交付物，独立于投标实体；锁定前平台不持有，故提问者无法提前看到（防线二本质自洽）。
- **钱包（wallet）** — 统一账户服务：每用户余额 + 冻结(hold) + 托管(escrow) 三态 + 只追加交易日志（事实源，喂 `add-data-reconciliation`）。三条商业化线都调它，不自持资金。本期货币仅平台发放，无充值/提现。
- **账本账户行** — 钱包持久化中的余额归属记录，语义上从属于 `用户`，不是独立认证或独立业务主体。_Avoid_: 商业账户、account（未加限定时）
- **冻结（hold）** — 出价 / 投标质押时锁定的余额，成交或解约前不可用；GSP 第二价成交后退多余冻结。
- **delivery bucket** — 付费加权分发结算的聚合时间桶；同一用户在同一时间桶内反复看到同一活动内容，只记一条分发事实，不按刷新次数重复计费。 _Avoid_: 单次请求计费、逐刷新计费
- **抽成（commission）** — 平台对 P2P 交易（悬赏等后续 P2P 场景）抽取的佣金。**本期 0%**：悬赏全额到收款方。推广位出价是**位竞价中标价（买曝光位的售价），非抽成**。v1 平台是经济运营方（发币+托管），非抽利方；真佣金留待真金白银阶段。
