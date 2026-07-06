---
doc_type: feature-design-review
feature: 2026-07-06-frontend-follow-feed
status: passed
reviewed: 2026-07-06
round: 2
---

# frontend-follow-feed feature design 审查报告（round 2）

## 1. Scope And Inputs

- Design: .codestable/features/2026-07-06-frontend-follow-feed/frontend-follow-feed-design.md
- Checklist: 同目录 frontend-follow-feed-checklist.yaml（3 step / 13 check）
- Intent / brainstorm: none
- Roadmap: none
- Related docs: compound/comment-async-submit.md、compound/counter-likecount-read-sds-not-mysql.md、round 1 design-review.md
- Code facts checked: KnowPostController.java、FeedPageResponse.java、HomePage.tsx、knowpostService.ts（feed:86）、CommentSection.tsx:277、commentService/searchService/relationService（URLSearchParams 惯例）

### Independent Review

- Status: completed
- Detection: native-agent（general-purpose subagent，无 Paseo，同类降级）
- Provider / agent: general-purpose（agentId a5613184d7b5a659d）
- Raw output: 见本报告第 3 节（已逐条本地事实核验）
- Merge policy: round 1 五条核心（B1/I1/I2/I3/I4）逐条核验已消化；round 2 新 finding 1 important（I-2.1）+ 2 nit + 1 suggestion + 1 residual-risk 已本轮补修
- Gate effect: 无 blocking，可交用户整体 review

## 2. Design Summary

- Goal: 首页加"关注"tab，接 follow feed（cursor + 加载更多按钮），复用 CourseCard
- Key contracts: followFeed(cursor, token) service（URLSearchParams）+ HomePage useAuth + tab 切换 + 加载更多按钮 + 空态引导 + 未登录隐藏
- Steps: 3（service+types → tab+首屏+空态 → 加载更多+harden）
- Checks: 13，覆盖 7 验收场景 + 反向核对
- Baseline: npm run lint + curl follow feed + 浏览器

## 3. Findings

### round 1 blocking/important 消化核验

- **B1 无限滚动→加载更多按钮** ✅ D2/2.2/2.3 M3/2.4 step3/S2/反向核对全改；CommentSection:277 模式核验属实
- **I1 cursor 编码** ✅ service 改 URLSearchParams，S7 降级"cursor 正确回传"，4 处惯例核验属实
- **I2 空态** ✅ S3/2.2 明确"不区分，统一引导，接受体验瑕疵"
- **I3 state 隔离** ✅ 2.2 明确"顶层 useState + 切渲染分支不 unmount"
- **I4 useAuth** ✅ 1.4/1.6 + checklist step2 补"引入 useAuth() 取 tokens"

### round 1 important/nit/suggestion 处理

- N1 行号 → round 2 发现 round 1 给的 :63-64 也错（实际 :86-87），已本轮修正（见 I-2.1）
- N2 page/size 占位 ✅ | S1 错误态 ✅ | S2 后端 git diff 仓库路径 ✅

### blocking
none。

### important
none（I-2.1 行号错误已本轮修正）。

### round 2 新 finding（已本轮补修）

- **I-2.1 行号事实错误（已修）**：round 1 N1 说 feed 在 :63-64（本身错），design 照改仍错。本地核验 feed 实际在 `:86-87`（:63-64 是 setTop）。已改 design 2.1 为 `:86-87`。
- **N-2.1 三处"无限滚动"措辞残留（已修）**：design :35/:62/:153 仍写"无限滚动"，与 B1 冲突。已全改为"加载更多按钮"。
- **N-2.2 attention 候选与 I1 矛盾（已修）**：design :192 写"cursor 含: 必须编码"，与 I1 修订矛盾。已改为"无（URLSearchParams 是惯例，无需约定）"。
- **S-2.1 state 隔离未点名禁止 && 渲染（已修）**：2.2 补"用三目/早返回，不用 {cond && <Comp/>}"。
- **RR-2.1 首屏 fetch 触发时机（已修）**：2.2 补"首屏 fetch 只在首次切关注 tab 时跑（hasFetched ref），切回不重 fetch"。

### nit
none（round 2 nit 已本轮补修）。

### suggestion
none（S-2.1 已补修）。

### learning

- L1 round 1 reviewer 给的"正确行号"本身也可能错——主 agent 必须本地核验外部结论，不能照抄。本次 I-2.1 坐实：round 1 说 :63-64，实际 :86-87。
- L2 跨仓库边界声明到位，1.4 已验证事实全部代码核验通过。

### praise

- P1 B1 修订方向优于 reviewer 建议：改用仓库现成"加载更多按钮"（CommentSection:277）而非引入 IntersectionObserver，顺带解决 I3 state 隔离——一石二鸟。
- P2 round 2 对 round 1 finding 的二次核验发现了 round 1 自身的行号错误，体现"外部结论须经本地事实核验"原则。

### residual-risk

- RR1 HomePage 到 ~170 行两套 feed 共存 → design 2.5 已标后续 cs-refactor 抽 hook
- RR2 首屏 fetch 触发时机已补约束（hasFetched ref），实现细节归 implement 自决

## 4. User Review Focus

- 用户需重点拍板：无（owner 已定 tab/加载更多/空态/未登录四决策，review 无新增产品取舍）
- implement 需重点遵守：followFeed 用 URLSearchParams；HomePage 引入 useAuth；关注 tab state 顶层 useState + 三目渲染（非 &&）；首屏 fetch 用 hasFetched ref 防重；加载更多按钮复用 CommentSection 模式
- code review/QA/acceptance 重点复核：B1 按钮模式、I3 state 隔离（三目渲染）、I4 useAuth、RR2 首屏 fetch 不重跑

## 5. Evidence Confidence Ledger

| Check | Verdict | Evidence Class | Basis | Follow-up |
|---|---|---|---|---|
| Acceptance Coverage Matrix | pass | E | 7 场景+矩阵全，S3 不区分注明、S7 降级 cursor 回传 | none |
| DoD Contract | pass | E | Design/Impl/Review/QA/Acceptance + 命令 + 产物齐全 | none |
| Steps and checks traceability | pass | E | 3 step/13 check 可追溯，step3 加载更多按钮 | none |
| Roadmap contract compliance | n/a | E | 非 roadmap 起头 | none |
| Module interface design | pass | C | followFeed URLSearchParams + useAuth 引入 + state 三目渲染 | none |
| Validation and artifacts | pass | E | 命令+产物齐 | none |

Summary: E=5, C=1, H=0, H-only core checks=none。

## 6. Residual Risk

- RR1 HomePage 膨胀 → 后续 cs-refactor 抽 hook
- RR2 首屏 fetch 触发时机已约束，实现细节归 implement

## 7. Verdict

- Status: **passed**
- round 1 五条核心（B1/I1/I2/I3/I4）全部 ✅已消化；round 2 新 finding（I-2.1 行号 + N-2.1/N-2.2 措辞 + S-2.1/RR-2.1 约束）已本轮补修。
- reviewer: native-agent（环节 A 完成，OCR 不可用跳过环节 B）
- Next: 交给用户整体 review。用户确认后回 cs-feat-design 标 approved → cs-feat-impl。
