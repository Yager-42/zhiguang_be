---
doc_type: feature-design
feature: 2026-07-07-frontend-promotion
status: approved
summary: 前端竞价模块——createCampaign + 竞价房间页（实时排名轮询 + 出价），入口在发布成功页 + 帖子详情页；余额前端文案改"积分"
tags: [frontend, promotion, auction, bprime]
requirement:
---

# 前端推广竞价 设计

## 0. 需求摘要与决策

- **用户目标**：创作者能为自己的帖子竞价买推荐位（首页/搜索置顶），在竞价房间看到实时排名 + 出价。
- **核心行为**：发布成功 / 帖子详情页点"推广" → createCampaign（选资源位 + 时间段）→ 进竞价房间页（当前窗口排名 + 出价表单 + 你的位次 + 剩余时间）→ 出价 → 实时排名更新。
- **成功标准**：能创建活动、进竞价房间、出价、看到 snapshot 排名、余额（积分）扣减。
- **明确不做**（grep / 测试可反向核对）：
  - 不做后端 wallet→points 重命名（前端文案改"积分"，后端不动；重命名走独立 cs-refactor）
  - 不做 WebSocket 实时推送（用 snapshot 轮询）
  - 不做活动管理列表页（创建即进房间，无"我的活动"列表）
  - 不做竞价历史/结算明细
- **复杂度档位**：偏离默认——多接口（5）+ 竞价房间页 + 实时轮询 + 后端 DTO string 化，但仍单 feature 可控。
- **关键决策**（owner 已拍板）：
  - D1 入口：CreatePage 发布成功段 + 帖子详情页作者区，两处"推广"按钮
  - D2 创建即进房间：createCampaign 提交后直接跳竞价房间页（一页完成创建+竞价）
  - D3 实时排名：snapshot 每 4s 轮询（不做 WebSocket）
  - D4 余额前端文案改"积分"（后端 wallet 不动）
  - D5 资源位默认 feed_top_slot，可切 search_top_slot；时间段默认 现在~+24h（覆盖 allocation 窗口）

## 1. 决策与约束、风险与证据

### 1.1 结构归属

前端 `zhiguang_fe`。新建 `promotionService`（5 方法）+ types + 竞价房间页 `PromotionRoomPage.tsx` + createCampaign 表单（弹窗或房间页内联）。CreatePage + CourseDetailPage 加"推广"按钮。后端零功能改动，仅 snapshot DTO long→String（精度修复）。

### 1.2 Top 3 风险

- **R1（最可能实现偏）**：snapshot 的 auctionWindowId/campaignId/bidderUserId/postId 是后端 `long`（snowflake >2^53）→ 前端 JSON 解析丢精度。**后端 DTO 需改 String**（同通知/举报 B1/B3 先例）。
- **R2（最难回滚）**：createCampaign 时间段——startAt/endAt 必须覆盖 allocation 窗口（windowEnd + 1 窗口时长），否则 submitBid 报 WINDOW_CLOSED（之前踩的坑）。默认 现在~+24h 兜底。
- **R3（最易验收遗漏）**：出价后 snapshot 排名更新有延迟（bprime 异步：RocketMQ→Redis Lua→Kafka→projection），轮询 4s 可能看到旧排名。缓解：出价后立即轮询 + loading 态。

### 1.3 非显然依赖

- bprime 链路异步：submitBid 返回 `SUBMITTED + resultAvailable=false`（后端 `PromotionCommandSubmissionService:122` 恒 false，前端不依赖它判断决策完成），实际决策经 RocketMQ→Redis Lua→Kafka→projection，约 1-3s 后 snapshot 才更新
- **后端已有 STOMP WebSocket 实时推送**（`PromotionAuctionWebSocketConfig`，bprime=true 时激活，推 `/topic/promotion.auction.{windowId}`）——本期前端不接入（用 snapshot 轮询替代），理由：轮询实现简单且 snapshot 接口已 ready，STOMP 握手 + token 传递成本高于收益。前端 grep 无 `@stomp`/`stompjs` import
- snapshot 轮询：窗口 status=OPEN 时轮询，SETTLED 后停
- 钱包余额：`GET /wallet/me` → `WalletBalanceResponse`（4 字段，前端显示"积分"）；账户 status 非 ACTIVE 出价会 400
- 出价金额 ≤ 余额（前端校验 + 后端 hold 校验）
- idempotencyKey：每次出价 crypto.randomUUID()
- **前端 user.id 是 number**（`types/auth.ts:29`），与后端 string 化 id 比较需显式 `String(user.id)`

### 1.4 关键假设

- **已验证事实**（读后端 DTO + bprime 链路 curl 验证）：
  - createCampaign `POST /promotions/campaigns` body `{postId, resourceType, startAt:Instant, endAt:Instant}` → `PromotionCampaignResponse {id:String, postId:String, resourceType:String, status:String, startAt, endAt}`
  - submitBid `POST /promotions/campaigns/{id}/bids` body `{bidAmount:long, idempotencyKey}` → `{commandId:String, auctionWindowId:String, status, resultAvailable}`（id/windowId 已 String）
  - snapshot `GET /promotions/windows/{auctionWindowId}/snapshot` → `PromotionAuctionSnapshot {auctionWindowId:long⚠, status, ranking:[{campaignId:long⚠, bidderUserId:long⚠, postId:long⚠, bidAmount:long, rank:int}], serverTime, decisionVersion}` —— ⚠ 需后端改 String
  - allocations `GET /promotions/allocations/active?resourceType=` → `List<PromotionAllocationView>`
  - getCampaign `GET /promotions/campaigns/{id}` → `PromotionCampaignResponse`
  - 钱包 `GET /wallet/me` → `WalletBalanceResponse`（4 字段：availableBalance/heldBalance/escrowedBalance/status）
- 假设：bprime=true 已开启（本地 RocketMQ + Kafka 容器已起，defect 已修）；生产环境 bprime 默认开
- **decisionVersion 是窗口内单调递增计数**（Redis INCR，远小于 2^53），保留 number 类型（已核 `PromotionSnapshotService:71` Long.parseLong，无溢出风险）
- **bidAmount 是积分类小额整数**（保留价 1L，用户余额 100），远小于 2^53，前端 number 安全

### 1.5 必跑验证命令与基线风险

- 前端 `npm run lint`
- 后端 `mvn spring-boot:run`（bprime=true）+ curl 5 接口
- 浏览器肉眼验证竞价房间 + 出价 + 排名
- **基线风险**：bprime=true 需 RocketMQ + Kafka 容器（本地已起）；snapshot DTO 改 String 需后端重启；bprime 链路异步（submitBid→决策 1-3s）

### 1.6 交付物清单

- 新增：`services/promotionService.ts`（5 方法）
- 新增：`types/promotion.ts`
- 新增：`pages/PromotionRoomPage.tsx` + `.module.css`（竞价房间 + createCampaign 表单内联）
- 修改：`pages/CreatePage.tsx`（发布成功段加"推广"按钮）
- 修改：`pages/CourseDetailPage.tsx`（作者区加"推广"按钮）
- 修改：`App.tsx`（加 /promotion/:postId 路由）
- 后端：`PromotionAuctionSnapshot` + `PromotionRankingItem` long→String（R1 精度修复）+ 构造处 String.valueOf + 测试适配。构造处：`PromotionSnapshotService` 3 处 snapshot 构造（:37/:42/:45 auctionWindowId）+ 2 处 ranking 构造（:63/:83 campaignId/bidderUserId/postId）；测试 3 文件（PromotionSnapshotServiceTest / PromotionDecisionFanoutServiceTest / PromotionAuctionRealtimeContractTest）fixture 改 string
- 无后端功能改动（仅 DTO 精度修复）

### 1.7 清洁度规则

- 禁 `console.log` / TODO / FIXME / 注释代码 / 死 import

## 2. 名词层与编排层

### 2.1 名词层

**现状**：无竞价相关 service/types/页面。

**变化**：
```ts
// types/promotion.ts
export type PromotionResourceType = "feed_top_slot" | "search_top_slot";
export type PromotionCampaign = { id: string; postId: string; resourceType: PromotionResourceType; status: string; startAt: string; endAt: string };
export type CreateCampaignRequest = { postId: string; resourceType: PromotionResourceType; startAt: string; endAt: string };
export type SubmitBidRequest = { bidAmount: number; idempotencyKey: string };
export type SubmitBidResponse = { commandId: string; auctionWindowId: string; status: string; resultAvailable: boolean };
export type RankingItem = { campaignId: string; bidderUserId: string; postId: string; bidAmount: number; rank: number };
export type AuctionSnapshot = { auctionWindowId: string; status: string; ranking: RankingItem[]; serverTime: string; decisionVersion: number };
// 对齐后端 WalletBalanceResponse 4 字段（availableBalance/heldBalance/escrowedBalance/status）
export type WalletBalance = { availableBalance: number; heldBalance: number; escrowedBalance: number; status: string };  // 前端显示"积分"

// services/promotionService.ts
createCampaign(payload, token) → PromotionCampaign
getCampaign(id, token) → PromotionCampaign
submitBid(campaignId, payload, token) → SubmitBidResponse
snapshot(windowId, token) → AuctionSnapshot
activeAllocations(resourceType, token) → PromotionAllocationView[]
// walletService（新增）
balance(token) → WalletBalance
```

### 2.2 编排层

```mermaid
flowchart TD
  A[发布成功/详情页 点推广] --> B[/promotion/:postId 路由]
  B --> C[PromotionRoomPage]
  C --> D[createCampaign 表单 资源位+时间段]
  D --> E[提交 createCampaign]
  E --> F[拿到 campaignId + 当前窗口]
  F --> G[竞价房间视图]
  G --> H[显示 snapshot 排名 + 你的位次 + 剩余时间]
  G --> I[显示积分余额]
  H --> J{窗口 OPEN?}
  J-- 是 --> K[每 4s 轮询 snapshot]
  K --> H
  J-- SETTLED --> L[停轮询 显示结算结果]
  G --> M[出价表单 金额≤积分]
  M --> N[submitBid]
  N --> O[立即轮询 + loading]
  O --> K
```

**流程级约束**：
- createCampaign 时间段：**startAt 锁定 now（前端不可调）**，endAt 默认 now+24h 可调但下限 now+2h（覆盖最坏 allocationEnd=windowEnd+60min≈now+120min，留余量）。后端 `requireCampaignEligibleForWindow` 要求 `startAt<=windowEnd && endAt>=windowEnd+windowDuration`，+24h 充分覆盖
- startAt/endAt 序列化：用 `new Date().toISOString()`（ISO-8601 Z 串，后端 Jackson Instant 可解析）；前端校验用 `Date.now()` 毫秒比较
- 轮询：snapshot 每 4s，窗口 OPEN 时轮询，SETTLED 停；useEffect cleanup 清 interval
- 出价：idempotencyKey=crypto.randomUUID()；bidAmount ≤ availableBalance（前端校验）；submitBid 200 即结束出价 loading（不等排名更新——bprime 异步 1-3s）；立即触发一次轮询（可能旧排名，可接受）；后续靠 4s 轮询收敛；若 8s 后排名仍不含我的出价，显示"出价处理中"提示（非 loading）。`resultAvailable` 后端恒 false（`PromotionCommandSubmissionService:122`），前端不依赖它判断决策完成
- 你的位次高亮：`String(user.id) === bidderUserId`（user.id 是 number，bidderUserId 是 string，显式 String() 归一化，B1）
- 积分显示：余额接口 GET /wallet/me，文案"积分"（D4）；账户 status 非 ACTIVE 时出价会 400，前端显示后端错误信息
- 路由：/promotion/:postId（postId 是要推广的帖子）

### 2.3 挂载点清单（删了它 feature 是否消失）

- M1 CreatePage 发布成功段"推广"按钮——删 → 无发布后入口
- M2 CourseDetailPage 作者区"推广"按钮——删 → 无详情页入口
- M3 /promotion/:postId 路由 + PromotionRoomPage——删 → 无竞价页
- M4 promotionService 5 方法——删 → 无接口
- M5 后端 snapshot DTO long→String——删 → 精度丢失（但 feature 仍跑，仅 id 错）

反向核对：grep `promotionService` / `PromotionRoomPage` 落点都在清单内。

### 2.4 推进策略（paradigm 切片）

- **step1 service + types + 后端 DTO string 化**：promotionService 5 方法 + walletService.balance + types；后端 PromotionAuctionSnapshot/PromotionRankingItem long→String + 构造 + 测试。`exit_signal`：lint 绿 + mvn test 通过 + curl 5 接口返回 string id。
- **step2 PromotionRoomPage + createCampaign 表单**：竞价房间页（createCampaign 表单内联：资源位 + 时间段 + 提交）+ 跳转。`exit_signal`：lint 绿 + 浏览器点推广进房间 + createCampaign 成功拿到 campaignId。
- **step3 竞价视图 + 排名轮询 + 出价 + 积分**：snapshot 排名展示 + 4s 轮询 + 你的位次 + 剩余时间 + 出价表单 + 积分余额 + 出价后立即轮询。`exit_signal`：浏览器出价 + snapshot 排名更新 + 积分扣减。
- **step4 入口接入 + harden + 清洁度**：CreatePage + CourseDetailPage 推广按钮 + App 路由 + 清洁度。`exit_signal`：两处入口可见 + grep 清洁 + lint 绿。

### 2.5 结构健康度与微重构

评估前查 compound：无目录组织 convention。

- **文件级**：PromotionRoomPage 新建单文件（createCampaign 表单 + 竞价视图 + 轮询），预计 ~250 行，偏大但逻辑内聚（一个房间页）。结论：**不做微重构**——若膨胀再拆 usePromotionRoom hook（同 usePublishStatus 模式）。
- **目录级**：pages/ 已有多页，新增符合结构。无 convention 候选。
- **超出范围的观察**：后端 wallet→points 重命名（D4 不做，独立 cs-refactor）。

## 3. 验收契约

- **S1 发布成功推广按钮**：CreatePage 发布成功后显示"推广"按钮，点击跳 /promotion/:postId。证据：浏览器
- **S2 详情页推广按钮**：帖子详情页作者区显示"推广"按钮（作者可见），点击跳 /promotion/:postId。证据：浏览器
- **S3 createCampaign**：进 PromotionRoomPage 选资源位 + 时间段 + 提交 → createCampaign 成功 → 进竞价视图。证据：浏览器 Network
- **S4 排名展示 + 轮询**：竞价视图显示 snapshot 排名（campaignId/postId/bidAmount/rank）+ 你的位次 + 剩余时间；每 4s 轮询更新。证据：浏览器 Network
- **S5 出价**：出价表单输入金额（≤积分）+ 提交 → submitBid 200 → 立即轮询 → snapshot 排名含你的出价。证据：浏览器 Network
- **S6 积分余额**：显示"积分: N"（availableBalance）；出价后余额减少（hold）。证据：浏览器
- **S7 id string 精度**：snapshot 响应 auctionWindowId/campaignId/bidderUserId/postId 全 string（后端 DTO 改 String）。证据：浏览器 Network
- **S8 窗口 SETTLED 停轮询**：窗口结算后停轮询，显示结算结果。证据：浏览器（难自然触发，可信任代码审查）
- **反向核对（明确不做）**：grep 前端无 `@stomp`/`stompjs` import（不做 WebSocket）；无 wallet→points 后端重命名（后端 wallet 代码不变）；无活动管理列表页

### Acceptance Coverage Matrix

| 场景 | step | 证据类型 | 命令 / 动作 |
|---|---|---|---|
| S1 发布成功按钮 | 4 | 浏览器 | 发布成功看 |
| S2 详情页按钮 | 4 | 浏览器 | 作者看详情页 |
| S3 createCampaign | 2 | 浏览器 Network | 提交看 POST |
| S4 排名轮询 | 3 | 浏览器 Network | 4s 看轮询 |
| S5 出价 | 3 | 浏览器 Network | 出价看排名更新 |
| S6 积分余额 | 3 | 浏览器 | 看积分 + 出价后减 |
| S7 id string | 1 | 浏览器 Network | snapshot id 带引号 |
| S8 停轮询 | 3 | 代码审查 | SETTLED 停 |

### DoD Contract

- Design DoD：名词层 / 编排层 / 挂载点 / 验收契约 / steps 全填，本文件 approved
- Implementation DoD：4 step 全 done
- Review DoD：`cs-code-review` passed
- QA DoD：`cs-feat-qa` passed
- Acceptance DoD：8 节核对 + 最终审计
- Validation Commands：`npm run lint`、mvn test、curl 5 接口、浏览器
- Required Artifacts：promotionService.ts、types/promotion.ts、PromotionRoomPage.tsx、CreatePage.tsx、CourseDetailPage.tsx、App.tsx、后端 2 DTO+构造+测试、design.md、checklist.yaml

## 4. 后续衔接

- **attention 候选**：bprime 链路异步（submitBid→决策 1-3s 延迟）—— 候选 cs-note
- **compound 候选**：snowflake id string 化项目级约定（评论/通知/举报/竞价都改了）—— 候选 cs-keep
- **遗留**：后端 wallet→points 重命名（D4 不做，独立 cs-refactor）；WebSocket 实时推送（不做）；活动管理列表页（不做）；竞价历史/结算明细（不做）
