---
doc_type: feature-design-review
feature: 2026-07-06-frontend-notification
status: passed
reviewed: 2026-07-06
round: 2
---

# frontend-notification feature design 审查报告（round 2）

## 1. Scope And Inputs

- Design: .codestable/features/2026-07-06-frontend-notification/frontend-notification-design.md
- Checklist: 同目录 frontend-notification-checklist.yaml（3 step / 13 check）
- Intent / brainstorm: none
- Roadmap: none
- Related docs: compound/counter-likecount-read-sds-not-mysql.md、follow-feed design 参考、round 1 design-review.md
- Code facts checked: NotificationController.java、3 DTO、NotificationCommandServiceImpl.java（createCommentNotification/createLikeNotification）、LikeNotificationConsumer.java、NotificationItemResponse.java、Sidebar.tsx

### Independent Review

- Status: completed
- Detection: native-agent（general-purpose subagent，无 Paseo，同类降级）
- Provider / agent: general-purpose（agentId af6f207b4b443d7ae）
- Raw output: 见本报告第 3 节（已逐条本地事实核验）
- Merge policy: round 1 三 blocking + 四 important 逐条核验已消化；round 2 新 finding I5+RR3+N4+N5 已本轮补修
- Gate effect: 无 blocking，可交用户整体 review

## 2. Design Summary

- Goal: 前端通知中心页 + Sidebar 未读徽章，接 4 接口，不显示 actor
- Key contracts: notificationService（4 方法）+ NotificationPage（双游标+跳转+文案）+ NotificationBadge（Sidebar 两时机拉取）
- Steps: 3（service+types → 列表页 → 徽章+标记已读+harden）
- Checks: 13，覆盖 8 验收场景 + 反向核对
- Baseline: npm run lint + curl 通知接口 + 浏览器

## 3. Findings

### round 1 blocking/important 消化核验

- **B1 comment 跳转** ✅ 2.2/1.4/S4/checklist 2.4 四处改 comment→/post/{entityId}（entityId=postId）；createCommentNotification:41-50 核验属实
- **B2 like+comment 漏分支** ✅ 2.2 补 like+comment→不跳；跳转改 type+entityType 二元组；LikeNotificationConsumer 处理 knowpost+comment 核验属实
- **B3 类型缺字段** ✅ NotificationItem 补 windowStart/windowEnd（string|null）
- **I1 markRead 失败** ✅ 2.2 补"失败不跳+显示错误+保持未读"
- **I2 Sidebar useAuth** ✅ 1.6 交付物补"引入 useAuth"；2.5 行数修正 ~60
- **I3 徽章时机** ✅ 2.2 补两时机；checklist 3.3 拆
- **I4 aggregateCount 边界** ✅ 2.2/S2/checklist 2.3 写死 =1 无前缀/>1 前缀

### round 1 nit/suggestion 处理

- N1 双游标成对传 ✅ | N2 follow 跳转遗留 ✅ | N3 step1 exit_signal 空数组 ✅ | S1 windowStart/End 保留不渲染 ✅ | S2 markRead 幂等 ✅

### blocking
none。

### important
none（I5 标记与跳转解耦已本轮补修）。

### round 2 新 finding（已本轮补修）

- **I5 markRead 标记与跳转耦合歧义（已修）**：2.2 原把"标记已读+跳转"绑一句，对 like+comment 不跳分支歧义。已拆成两独立动作：标记成功就 isRead+徽章减（无论跳否），跳转看映射。S4/checklist 2.4 同步。
- **RR3 like+comment 文案语义（已修）**：原"赞了你的文章"对赞评论语义错。已改 like+comment→"赞了你的评论"，2.2/S2/checklist 2.3 同步。
- **N4 checklist 3.3 重复（已修）**：3.3 末句"本地同步减"与 3.2 重叠，已删。
- **N5 Matrix 漏 S8 行（已修）**：Acceptance Coverage Matrix 补 S8 行。

### nit
none（round 2 nit 已本轮补修）。

### suggestion

- S3 design 4 遗留 like+comment 反查可补"需后端配合"——非阻塞，实现时不涉及。

### learning

- L1 通知 entityType 是自由 String 非枚举，跳转映射应基于 (type, entityType) 二元组——已落实进 design。
- L2 通知点击流程应分两层：markRead 副作用（标记+徽章）独立于跳转决策——I5 修订核心。

### praise

- P1 round 1 修订痕迹干净，无残留旧逻辑（grep 无旧跳转/旧文案/旧行数估算）
- P2 反向核对"明确不做"4 条都可 grep 核对
- P3 挂载点 M1-M4 可逆核验

### residual-risk

- RR1 无 WebSocket，停留页面时新通知徽章不更新（design 接受）
- RR2 cursorCreatedAt ISO 编码安全已核验，attention 候选可补

## 4. User Review Focus

- 用户需重点拍板：无（owner 已定 3 决策，round 1/2 无新增产品取舍；B1/B2/B3/I5/RR3 是事实/逻辑错误已修）
- implement 需重点遵守：跳转按 (type, entityType) 二元组；comment→/post/{entityId}；like+comment 不跳但仍标记已读；双游标成对传；markRead 失败不跳+显示错误；Sidebar 引入 useAuth；徽章两时机拉取；标记与跳转解耦
- code review/QA/acceptance 重点复核：B1/B2 跳转、I5 标记与跳转解耦、RR3 like+comment 文案、I3 徽章时机

## 5. Evidence Confidence Ledger

| Check | Verdict | Evidence Class | Basis | Follow-up |
|---|---|---|---|---|
| Acceptance Coverage Matrix | pass | E | 8 场景+矩阵全（S8 已补），S4 跳转分支全覆盖 | none |
| DoD Contract | pass | E | Design/Impl/Review/QA/Acceptance + 命令 + 产物齐全 | none |
| Steps and checks traceability | pass | E | 3 step/13 check 可追溯，3.3 去重 | none |
| Roadmap contract compliance | n/a | E | 非 roadmap 起头 | none |
| Module interface design | pass | C | NotificationItem 含 windowStart/windowEnd；Sidebar useAuth 声明；标记与跳转解耦 | none |
| Validation and artifacts | pass | E | 命令+产物齐，step1 exit_signal 空数组已改 | none |

Summary: E=5, C=1, H=0, H-only core checks=none。

## 6. Residual Risk

- RR1 无 WebSocket 徽章不实时 → design 接受
- RR2 cursorCreatedAt ISO 编码安全 → attention 候选补

## 7. Verdict

- Status: **passed**
- round 1 三 blocking + 四 important 全部 ✅已消化；round 2 新 finding（I5 标记解耦 / RR3 like+comment 文案 / N4 去重 / N5 Matrix 补行）已本轮补修。
- reviewer: native-agent（环节 A 完成，OCR 不可用跳过环节 B）
- Next: 交给用户整体 review。用户确认后回 cs-feat-design 标 approved → cs-feat-impl。
