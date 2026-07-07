---
doc_type: feature-review
feature: 2026-07-06-frontend-report
status: passed
reviewed: 2026-07-06
round: 1
reviewer: subagent
source: cs-feat-impl
---

# frontend-report 代码审查报告

## 1. 范围与输入

- 来源：cs-feat-impl（2 step 全 done）
- Design: .codestable/features/2026-07-06-frontend-report/frontend-report-design.md（approved）
- Checklist: 同目录（2 step done / 9 check passed）
- 改动文件：后端 4（DTO + ServiceImpl + 2 测试）；前端 6（types+service+ReportDialog+css+CourseDetailPage+css）
- reviewer 误报 B1"前端不存在"经本地核验驳回（见 §3 blocking 说明）

### 独立审查

- 环节 A（独立 Task agent）：completed。general-purpose subagent（agentId a42fb7ba7e9ee8491）
- 环节 B（OCR）：not-available
- reviewer: subagent

## 2. 改动摘要

- 后端：ModerationReportResponse reportId Long→String + ServiceImpl 3 处构造 String.valueOf + 2 测试适配（B3）
- 前端：types/moderation + moderationService（report）+ ReportDialog（单选原因+备注+成功态 1.5s 自动关+错误态不关）+ CourseDetailPage 举报按钮+弹窗

## 3. Findings

### blocking
none。

**reviewer B1"前端不存在"误报——本地核验驳回**：reviewer 称前端 4 文件不存在 + CourseDetailPage 无举报。本地核验：`ls` 确认 4 文件全在（moderation.ts/moderationService.ts/ReportDialog.tsx/.module.css），`grep` 确认 CourseDetailPage 有 `import ReportDialog` + 举报按钮 + `<ReportDialog>` 渲染，`git status` 显示 4 untracked + 2 modified。reviewer 可能用了错的 cwd 或 worktree 隔离问题。前端实际完整交付。

### important

#### I1 isSelf 昵称 fallback 误剥夺举报权（已修）
- Evidence: CourseDetailPage isSelf 含 `authorNickname === user.nickname` fallback（为 FollowButton 留的兜底）。举报按钮沿用同 isSelf → 昵称相同的不同用户被误判 self 隐藏举报按钮（误剥夺举报权）。后端无 self-report 校验，前端隐藏是唯一防线，fallback 让防线脆弱。
- 处置：**已修**。举报按钮单独判 `isSelfForReport = !!(derivedId && user?.id === derivedId)`（去昵称 fallback，宁可显示也别误隐藏）。FollowButton 保留原 isSelf（follow 场景 fallback 影响小）。lint 绿。

### nit

- N1 outbox payload reportId 仍是 Long（design 明确不改，正确）→ residual：若后续有 JS 消费 outbox 事件需评估统一 string 化。

### suggestion
none。

### learning

- L1 后端 Long→String 改造要追构造点（3 处）+ 测试断言（5 处）+ outbox payload 边界（不改）——本次覆盖完整（P1）。
- L2 reviewer 误报"前端不存在"——主 agent 必须本地核验外部结论，不能照抄。本次 `ls`/`grep`/`git status` 三重确认前端全在。

### praise

- P1 后端 Long→String 改造覆盖完整：DTO 字段 + 3 处构造 String.valueOf（:69/:93/:98）+ 5 处测试断言适配 + outbox payload 边界正确不动。后端 mvn test moderation 9/9 绿。
- P2 ReportDialog 成功态/错误态/防重复设计完整：成功 1.5s 自动关+重置表单，错误态不关可重试，loading+reason 双 disabled。
- P3 反向核对扎实：CommentSection 无 ReportDialog、无状态查询、后端只改 reportId 相关。

### residual-risk

- RR1 后端缺 self-report 强校验（design S5 已记遗留）→ 评论举报 feature 前补后端
- RR2 前端 0 测试，ReportDialog timer cleanup/错误态重试/防重复全靠手工验收（QA focus）
- RR3 outbox payload reportId 仍是 Long（N1）→ 后续 JS 消费 outbox 时评估

## 4. Test And QA Focus

QA 必须复核（前端补齐后）：
1. **成功态 1.5s 自动关+重置**：提交 202 → 成功态 → 1.5s 自动关 → 重开表单空
2. **timer cleanup**：成功态中路由跳走 → clearTimeout 防泄漏
3. **错误态可重试**：catch → 错误态不关 → 改 reason 重试
4. **防重复提交**：双击只发一次；success 态按钮不渲染
5. **targetId string**：Network 请求体 targetId 带引号
6. **isSelf 边界（I1 修复后）**：作者看自己帖无按钮；昵称相同的不同用户看对方帖有按钮（不被误隐藏）
7. **未登录无按钮**：清 token 后按钮消失
8. **重复举报幂等**：同帖二次 → 成功态不报错
9. **description 边界**：>512 字 maxlength 拦截；纯空格传 undefined
10. **后端回归**：mvn test 全量确认 DTO 变更未波及其它模块

## 5. Verdict

- Status: **passed**
- reviewer B1"前端不存在"误报，本地核验驳回（前端 4 文件 + CourseDetailPage 接入全在）；I1（isSelf 昵称 fallback）已修。
- reviewer: subagent（环节 A 完成，OCR 不可用跳过环节 B）
- Next: 进入 `cs-feat-qa`
