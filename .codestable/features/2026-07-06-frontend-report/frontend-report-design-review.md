---
doc_type: feature-design-review
feature: 2026-07-06-frontend-report
status: passed
reviewed: 2026-07-06
round: 2
---

# frontend-report feature design 审查报告（round 2）

## 1. Scope And Inputs

- Design: .codestable/features/2026-07-06-frontend-report/frontend-report-design.md
- Checklist: 同目录 frontend-report-checklist.yaml（2 step / 9 check）
- Related docs: compound/counter-likecount-read-sds-not-mysql.md、notification design 参考、round 1 design-review.md
- Code facts checked: ModerationReportController.java、ModerationReportResponse.java、ModerationReportServiceImpl.java（3 处构造）、ModerationReportControllerTest.java、ModerationReportServiceImplTest.java、CourseDetailPage.tsx

### Independent Review

- Status: completed
- Detection: native-agent（general-purpose subagent，无 Paseo，同类降级）
- Provider / agent: general-purpose（agentId ac5f47369c7a52aca）
- Raw output: 见本报告第 3 节（已逐条本地事实核验）
- Merge policy: round 1 三 blocking + 四 important 逐条核验已消化；round 2 新 finding I5（构造+测试）+ N3（design 2.4 状态码）已本轮补修
- Gate effect: 无 blocking，可交用户整体 review

## 2. Design Summary

- Goal: 帖子详情页加举报按钮 + 弹窗（单选原因+可选备注），接 POST /api/v1/moderation/reports（202）
- Key contracts: moderationService.report + ReportDialog（成功态 1.5s 自动关）+ CourseDetailPage 接入 + 后端 reportId Long→String
- Steps: 2（service+types+后端 DTO → 弹窗+接入+harden）
- Checks: 9，覆盖 7 验收场景 + 反向核对
- Baseline: npm run lint + curl 举报 + 后端 mvn test + 浏览器

## 3. Findings

### round 1 blocking/important 消化核验

- **B1 self-report** ✅ 1.4/R2/2.2 改"后端无校验，前端唯一防线"；S5 加 curl 反向核对；第4节遗留补。resolveOwner:101-114 核验无 owner==reporter 属实
- **B2 状态码 202** ✅（round 2 补 N3）：1.4/2.1/mermaid/S3/S5 注 202；checklist step1 exit_signal 改 2xx(202)；**design 2.4 step1 exit_signal round 1 漏改，round 2 已补**
- **B3 reportId** ✅ 选方案 a 后端 DTO Long→String；2.1/S7/第4节/checklist 同步
- **I1 toast** ✅ 2.2 改"弹窗内成功态 1.5s 自动关+重置表单，不引入 toast 组件"
- **I2 isLoggedIn** ✅ checklist 2.3/2.5 改 !!tokens?.accessToken
- **I3 路径** ✅ design 1.4/2.1 + checklist 1.3 改 /api/v1/moderation/reports
- **I4 description** ✅ 2.1 补空串 blankToNull

### round 1 nit/suggestion 处理

- N1 表单清空 ✅ | N2 同名易混 未处理（非阻塞）| S1 status 枚举 未采纳（合理）| S2 curl 修正 ✅

### blocking
none。

### important
none（I5 构造+测试已本轮补修）。

### round 2 新 finding（已本轮补修）

- **I5 reportId Long→String 构造处 + 测试适配未点名（已修）**：3 处 `new ModerationReportResponse` 构造（:69/:93/:98）需 String.valueOf；测试 5 处断言（ControllerTest:79/87 + ServiceImplTest:73/93/169）需适配；outbox payload :113 "reportId":21 不改（仍是 Long）。已补进 checklist step1 description + check 1.2。本地核验 3 处构造 + 5 处断言属实。
- **N3 design 2.4 step1 exit_signal 残留 200/201（已修）**：round 1 B2 修订漏改 design 2.4。已改"2xx（202 ACCEPTED）"。

### nit
- N4 design 1.4 reportId 类型表述拧（现状 Long vs 需改 String 相邻）→ 非阻塞，逻辑可读
- N5 "与 FollowButton 同条件"表述不准（FollowButton 内聚登录态，举报父级判）→ 非阻塞，实现者照 !!tokens?.accessToken 不会错

### suggestion
none。

### learning

- L1 后端 moderation 无 self-report 校验是系统性缺口——评论举报 feature 会复用，缺口放大（已记遗留）
- L2 reportId 响应精度是通知 B1 同类——snowflake id string 化需覆盖请求+响应两方向 + 构造处 + 测试断言（I5 教训：改 DTO 字段类型要追构造点和测试）

### praise

- P1 round 1 修订痕迹干净，B1/B2/B3/I1-I4 全消化
- P2 反向核对设计扎实（明确不做 + grep + checklist 2.6）
- P3 挂载点 M1-M4 可卸载性核验通过
- P4 round 2 对 round 1 漏掉的构造处+测试（I5）二次扫到，体现"改 DTO 要追构造点和测试"的深度

### residual-risk

- RR1 后端无 self-report 校验 → 第4节遗留已记（评论举报 feature 前评估补后端）
- RR2 reportId 响应精度 → B3 选方案 a 根治，已闭环

## 4. User Review Focus

- 用户需重点拍板：无（owner 已定 3 决策，round 1/2 无新增产品取舍；B1/B2/B3/I5 是事实/逻辑错误已修）
- implement 需重点遵守：后端 reportId Long→String + 3 处构造 String.valueOf + 5 处测试适配；前端 !!tokens?.accessToken；路径 /api/v1；状态码 202；弹窗内成功态 1.5s 自动关；前端隐藏是 self-report 唯一防线
- code review/QA/acceptance 重点复核：B1 self-report 前端隐藏、B3 reportId 响应 string、I5 构造+测试、I1 反馈机制

## 5. Evidence Confidence Ledger

| Check | Verdict | Evidence Class | Basis | Follow-up |
|---|---|---|---|---|
| Acceptance Coverage Matrix | pass | E | 7 场景+矩阵全，S5 含 curl 反向，S7 含响应 reportId | none |
| DoD Contract | pass | E | Design/Impl/Review/QA/Acceptance + 命令 + 产物齐全（含后端 DTO+构造+测试） | none |
| Steps and checks traceability | pass | E | 2 step/9 check 可追溯，step1 含构造+测试点名 | none |
| Roadmap contract compliance | n/a | E | 非 roadmap 起头 | none |
| Module interface design | pass | C | reportId string + 构造处 + 测试适配点名 | none |
| Validation and artifacts | pass | E | 命令+产物齐（含后端 3 文件+2 测试） | none |

Summary: E=5, C=1, H=0, H-only core checks=none。

## 6. Residual Risk

- RR1 后端无 self-report 校验 → 第4节遗留已记
- RR2 reportId 响应精度 → B3 方案 a 根治

## 7. Verdict

- Status: **passed**
- round 1 三 blocking + 四 important 全部 ✅已消化；round 2 新 finding I5（构造+测试）+ N3（design 2.4 状态码）已本轮补修。
- reviewer: native-agent（环节 A 完成，OCR 不可用跳过环节 B）
- Next: 交给用户整体 review。用户确认后回 cs-feat-design 标 approved → cs-feat-impl。
