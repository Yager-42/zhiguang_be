---
doc_type: feature-acceptance
feature: 2026-07-06-frontend-follow-feed
status: passed
accepted: 2026-07-06
round: 1
---

# 前端关注 Feed 验收报告

> 阶段：阶段 3（验收闭环）
> 验收日期：2026-07-06
> 关联方案：.codestable/features/2026-07-06-frontend-follow-feed/frontend-follow-feed-design.md

## 1. 接口契约核对

对照 design §2.1 名词层：

- [x] followFeed(cursor, accessToken) → FeedResponse，URLSearchParams 构建 query → knowpostService.ts 一致
- [x] FeedResponse 补 nextCursor?: string（后端已返回，前端类型对齐）→ types/knowpost.ts 一致
- [x] cursor 为 null 时无 query；非空时 URLSearchParams set cursor（: 不编码）→ 一致
- [x] page/size 是后端占位，关注 tab 只用 items/hasMore/nextCursor → 一致
- [x] 流程图（design §2.2 mermaid）节点在代码均有落点：tab 切换/首屏/加载更多/空态/未登录

无偏差。

## 2. 行为与决策核对

对照 design §1 + §2.2：

- [x] D1 顶部 tab（推荐/关注）默认推荐 → HomePage.tsx 一致
- [x] D2 关注 tab 用"加载更多"按钮（复用 CommentSection 模式，不引入无限滚动）→ loadMoreFollow + 按钮 disabled
- [x] D3 空态引导去发现内容（不区分两种空态）→ "还没有关注的内容，去发现更多" + Link to /search
- [x] D4 未登录只显示推荐 tab → 按钮不渲染 + 三目条件双保险
- [x] state 隔离（R3）：关注 tab state 顶层 useState + 三目渲染（非 &&）→ 切 tab 不 unmount state
- [x] 首屏 fetch hasFetched ref：首次切关注 tab 才跑，切回不重跑
- [x] cursor 回传：URLSearchParams（: 不编码）
- [x] 推荐 tab 保持现状 feed(1,20) 不翻页（S5）

**明确不做反向核对**（grep）：
- [x] 无 IntersectionObserver/scroll 监听（不做无限滚动）
- [x] 无新路由
- [x] 后端 git diff 为空

**挂载点反向核对**（design §2.3）：
- [x] M1 HomePage tab 切换 UI → grep 确认
- [x] M2 followFeed service 方法 → grep 确认
- [x] M3 加载更多按钮逻辑 → grep 确认
- [x] M4 空态引导 → grep 确认
- [x] 反向 grep：引用都在清单内
- [x] 拔除沙盘：删 tab+followFeed+加载更多+空态 → 回到现状单 feed

## 3. 验收场景核对

对照 design §3（7 场景）：

- [x] **S1 关注 tab 显示**：curl follow feed 200 + items（已验）；浏览器 UI 待 owner 实测
- [x] **S2 加载更多按钮**：hasMore=true 显示按钮，点击追加（代码审查；浏览器待 owner）
- [x] **S3 空态引导**：items 空 + hasMore=false → "去发现更多" Link（代码审查；浏览器待 owner）
- [x] **S4 tab 不残留**：顶层 state + 三目渲染，切回数据仍在（代码审查；浏览器待 owner）
- [x] **S5 推荐 tab 不变**：feed(1,20) 无翻页（grep 确认）
- [x] **S6 未登录无关注 tab**：按钮不渲染 + 三目双保险（代码审查；浏览器待 owner）
- [x] **S7 cursor 回传**：URLSearchParams 原样回传（代码审查；浏览器 Network 待 owner）

**review 修复 I1（跨账号 stale）**：登出重置 effect 已加，代码审查 + lint 通过。

## 4. 术语一致性

- followFeed / FeedResponse / nextCursor / FeedEntry 全仓一致 ✓
- 防冲突：grep 无重名 ✓

## 5. 领域影响盘点

- [x] 新名词：无
- [x] 结构性选择：无新模块（HomePage 单文件，design 2.5 评估不重构）
- [x] 流程级约束：加载更多按钮模式（复用 CommentSection，非新约定）

无领域维度变更需本轮 cs-domain。

## 6. requirement delta 回写

design frontmatter `requirement:` 空（纯前端补功能）。保持现状不 backfill，与历史 feature 一致。

## 7. roadmap 回写

design frontmatter 无 roadmap/roadmap_item → 非 roadmap 起头，跳过。

## 8. attention.md 候选盘点

- [x] 候选：无（design 4 节已改"无（URLSearchParams 是惯例，无需约定）"）

不擅自写入。

## 9. 遗留

- N1 重复代码（renderFeedList 与关注分支内联）→ 后续可重构
- RR1 首屏失败无法重试（design 范围外）→ follow-up
- 浏览器实测（S1/S3/S4/S6/I1）→ owner 终审
- HomePage 重构（design 2.5 超出范围）→ 后续 cs-refactor

## 10. 最终审计

- 聚合命令复验：前端 npm run lint ✓；curl follow feed 200 ✓
- 交付物落盘：types+service+HomePage+css 4 文件 + design/checklist/design-review(2轮)/review/qa/acceptance 7 spec 文件 ✓
- diff 清洁度：无 console/TODO/死 import ✓
- 知识沉淀出口分流：无 attention 候选；HomePage 重构 → 后续 cs-refactor
- 覆盖率诚实标记：curl+代码审查 re-verified；浏览器四态 trust-owner-实测
- 无未处理缺口

## Verdict

- Status: **passed**
- 9 节核对完成，所有 checks passed
- residual-risk：RR1 首屏失败重试（design 范围外）；浏览器四态待 owner 终审
- 无 attention 候选
