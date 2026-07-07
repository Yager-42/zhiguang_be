---
doc_type: feature-review
feature: 2026-07-06-frontend-follow-feed
status: passed
reviewed: 2026-07-06
round: 1
reviewer: subagent
source: cs-feat-impl
---

# frontend-follow-feed 代码审查报告

## 1. 范围与输入

- 来源：cs-feat-impl（3 step 全 done）
- Design: .codestable/features/2026-07-06-frontend-follow-feed/frontend-follow-feed-design.md（approved）
- Checklist: 同目录（3 step done / 13 check passed）
- 改动文件：types/knowpost.ts、services/knowpostService.ts、pages/HomePage.tsx、pages/HomePage.module.css；后端零改动

### 独立审查

- 环节 A（独立 Task agent）：completed。general-purpose subagent（agentId a49b6c48a1991ffb1），对抗式审查
- 环节 B（OCR）：not-available（ocr CLI 未安装）
- reviewer: subagent

## 2. 改动摘要

- types：FeedResponse 补 nextCursor?
- service：followFeed(cursor, accessToken) URLSearchParams 构建
- HomePage：useAuth + 顶部 tab + 关注 tab state 顶层 useState + 三目渲染 + hasFetched ref + 加载更多按钮 + 空态引导 + 未登录隐藏
- css：tabs/tab/tabActive 样式

## 3. Findings

### blocking
none。

### important

#### I1 跨账号 stale data：followHasFetchedRef 登出不重置（已修）
- Evidence: HomePage.tsx:58 `if (followHasFetchedRef.current) return` + :60 置 true 后永不重置。用户 A 登录切关注 tab（ref=true, items=A 数据）→ 登出 → 用户 B 登录切关注 tab → effect 命中 :58 return → 显示 A 的 stale items + A 的 cursor。
- Impact: 跨账号数据展示 bug（B 看到 A 的关注 feed）。生产 P0 体验问题。
- 处置：**已修**。加登出重置 effect（accessToken 变 null 时重置 ref/items/cursor/hasMore + tab 回 recommend）。lint 绿。

#### I2 加载更多按钮缺 disabled（已修）
- Evidence: HomePage.tsx:207 按钮无 disabled（checklist 3.2 标 passed 但实际无）。loading 时按钮消失兜底了视觉防重复，但与 design D2"复用 CommentSection:277 模式"（disabled）不一致。
- 处置：**已修**。补 `disabled={followLoading}`。

### nit

- N1 `renderFeedList` 定义但关注分支内联渲染，~30 行重复代码 → 后续可让关注分支复用 renderFeedList + emptySlot 参数。非阻塞，记 follow-up。
- N2 空态 `<a href="/search">` 整页刷新非 SPA → **已修**，改 `<Link to="/search">`。

### suggestion

- S1 followFeed service trailing comma 风格略不一致 → 非阻塞。
- S2 登出不重置 tab → **已修**（I1 重置 effect 里一并 setTab("recommend")）。

### learning

- L1 三目渲染防 unmount 推理成立：关注 tab state 全在顶层 useState/useRef，切 tab unmount 分支组件不丢 state，S4 满足。
- L2 cursor null 边界安全：后端 hasMore 恒等于 nextCursor!=null，前端 guard 兜底。
- L3 外部 reviewer 结论须本地核验：本次 I1 经本地读 :58-60 确认属实才采纳。

### praise

- P1 service URLSearchParams 严格对齐 design 2.1，cursor null 不加 query，: 不编码
- P2 顶层 state + 三目渲染 + hasFetched ref 三件套实现 state 隔离，S4 满足
- P3 错误态保留已加载 items（loadMoreFollow catch 不动 followItems），design S1 满足
- P4 未登录隐藏关注 tab 双保险（按钮不渲染 + 三目条件），D4/S6 满足
- P5 FeedEntry = Pick<FeedItem,...> 覆盖渲染全部字段，tsc 绿印证类型安全

### residual-risk

- RR1 首屏 fetch 失败后 hasFetchedRef 已 true，无法重试（需刷新页面）——design S1 仅要求翻页中途失败保留，首屏失败重试非本次范围，QA 关注
- RR2 loadMoreFollow 无 cancelled 标志（对比首屏 effect 有），unmount 时 setState 作用已卸载组件——React 18 静默，非 bug，与首屏严谨度不一致
- RR3 后端 follow feed 在"关注了但作者全删帖"时 while 循环耗时上限未知——前端无感知，QA 关注

## 4. Test And QA Focus

QA 必须复核：
1. **跨账号（I1 修复后）**：A 登录切关注 → 登出 → B 登录切关注 → 应显示 B 的 feed（不残留 A）
2. **S4 tab 不残留**：关注加载 → 切推荐 → 切回 → 数据仍在
3. **S7 cursor 回传**：点加载更多，Network cursor 值 = 上次 nextCursor（含: 不编码）
4. **S6 未登录无关注 tab**
5. **加载更多 disabled**：loading 时按钮 disabled
6. **空态 Link 跳转**：新号无关注 → 点"去发现更多" → SPA 内跳 /search
7. **首屏失败重试（RR1）**：mock 500 → 切关注看错误 → 切走切回是否能重试（预期不能，需刷新）

## 5. Verdict

- Status: **passed**
- 无 blocking；I1（跨账号 stale）已修，I2（按钮 disabled）已修，N2（Link）已修，S2（登出重置 tab）已修。N1 重复代码记 follow-up。
- reviewer: subagent（环节 A 完成，OCR 不可用跳过环节 B）
- Next: 进入 `cs-feat-qa`
