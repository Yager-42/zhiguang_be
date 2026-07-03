---
doc_type: feature-design-review
feature: 2026-07-02-frontend-password-login
status: passed
reviewed: 2026-07-02
round: 1
---

# frontend-password-login feature design 审查报告

## 1. Scope And Inputs

- Design: `.codestable/features/2026-07-02-frontend-password-login/frontend-password-login-design.md`
- Checklist: `.codestable/features/2026-07-02-frontend-password-login/frontend-password-login-checklist.yaml`
- Intent / brainstorm: none
- Roadmap: none
- Related docs: `.codestable/attention.md`、`.codestable/compound/`（无登录相关沉淀）
- Code facts checked: `AuthService.java`、`IdentifierType.java`、`GlobalExceptionHandler.java`、`ErrorCode.java`、`LoginRequest.java`、`LoginPage.tsx`、`LoginPage.module.css`、`types/auth.ts`、`apiClient.ts`、`AuthContext.tsx`、`RegisterPage.tsx`、`IdentifierValidator.java`

### Independent Review

- Status: completed
- Detection: native-agent（无 Paseo：`~/.paseo/orchestration-preferences.json` 不存在、无 paseo CLI/MCP 工具）
- Provider / agent: 原生 Agent 工具（general-purpose subagent，agentId acca1dcc9378cea2b）
- Raw output: 见本节下方摘要（完整审查结果已由 reviewer 回传）
- Merge policy: 已逐条本地事实核验。F1-F10 全部经主 agent 读代码复核后合并；F1（blocking）经核验 `GlobalExceptionHandler.java:32` 确认 `ResponseEntity.badRequest()` 全返 400，接受并已修订 design/checklist。F5 reviewer 未读 CSS module，主 agent 补读 `LoginPage.module.css:55-56` 确认 `.select` 类存在。
- Gate effect: reviewer 已 completed，verdict 可定稿。同宿主同类 agent 的残余风险：审查方与起草方同模型，异构性不足，但 findings 全部有代码事实支撑、可独立复核，风险可控。

## 2. Design Summary

- Goal: 登录页新增「账号密码登录」通道，与验证码登录 tab 切换；账号支持手机号/邮箱；纯前端改动。
- Key contracts: PASSWORD 模式按 `err.data.code` 分流（凭证错统一文案 / 格式错透传）；后端零改动；`LoginRequest` 联合类型已支持 password 分支。
- Steps: 4 步（tab 骨架 → state 分流 → 提交与错误文案 → 联调回归）。风险热点在 step3 的错误分流判据。
- Checks: 4 steps × 4-7 checks，全部可追溯 design AC1-AC10。
- Baseline / validation: `cd zhiguang_fe && npm run lint`（= tsc --noEmit）为主；浏览器手工联调；前端 0 测试框架。

## 3. Findings

### blocking

- [x] FDR-001 `design 3.1 AC6 备注 / checklist step3 description` AC6 文案策略判据写「按 HTTP status 或 errorData.code 判断，implement 自决」，但后端 `BusinessException` 一律返 HTTP 400，三 code 同 status，按 status 无法区分。
  - Evidence: `GlobalExceptionHandler.java:27-33` `ResponseEntity.badRequest()`；`ErrorCode.java:8/15/19` 三 code；`apiClient.ts:82-101` ApiError 携带 status+data。
  - Impact: 若 implement 按 status 走，AC6 与 AC3/4/5 无法同时满足（要么格式错被统一覆盖，要么凭证错透传退化为后端 message）。
  - Expected fix scope: **已修**。design AC6 备注锁死按 `err.data.code` 分流并给判断范式；checklist step3 description 同步收紧；1.2 节约束表补「不能按 status 区分」+「ApiError 携带 status/data」两行。

### important

- [x] FDR-002 `design 1.2 约束表` 未点明 `ApiError` 携带 `status`/`data`，是 FDR-001 的前置事实。
  - Evidence: `apiClient.ts:15-24` ApiError 类定义含 status/data。
  - Impact: 实现者不知能取 `err.data.code`。
  - **已修**：1.2 节补一行 ApiError 事实。

- [x] FDR-003 `design 3.1 AC5 / 3.3 Coverage Matrix / checklist step4` AC5 与 AC3 后端同 code（`INVALID_CREDENTIALS`），无法独立验证；Matrix 标 step4 覆盖 AC5 但 step4 checks 无 AC5 条目，不一致。
  - Evidence: `AuthService.java:161` `||` 短路同抛 `INVALID_CREDENTIALS`。
  - Impact: 验收时 AC5 无法判定，Matrix 与 checks 矛盾。
  - **已修**：AC5 标「可选，与 AC3 等价」；Matrix 标 AC5 optional；step4 补 check 4.7（可选）。

### nit

- [x] FDR-004 `design R3 / 假设3` `.select` CSS 类存在性。
  - Evidence: 主 agent 读 `LoginPage.module.css:55-56` 确认 `.input, .select { ... }` 合并规则存在（reviewer 未读此文件，主 agent 补核验）。
  - **已修**：checklist step1 description 注明「已确认 .select 存在于 LoginPage.module.css:55-56；若缺独立样式则补」。

- [x] FDR-005 `design 2.1` 「切 tab 时清空 code 或 password」措辞易误读。
  - **已修**：改为「identifier 两 tab 共用且切 tab 保留；仅清对侧 code/password 及 error」。

### suggestion

- [x] FDR-006 AC6 补 EMAIL tab 填手机号对称用例。
  - **已修**：AC6 描述加「EMAIL tab 填手机号对称同此」。

### learning

- 后端 `BusinessException` 不区 HTTP status（全 400），错误分流只能靠响应体 `code` 字段——这是本项目错误处理的通用约束，未来其他需要按错误类型分流的前端逻辑都适用。建议后续走 `cs-keep` 沉淀（不在本 feature 内）。

### praise

- FDR-P1 挂载点清单按「删了 feature 是否消失」收紧到位，4 条全部满足判据，且正确把「验证码 tab 既有行为」排除在挂载点外。
- FDR-P2 「明确不做」7 条全部可 grep/diff 反向核对，3.2 节给了具体命令，可核对性强。

### residual-risk

- FDR-R1 账号枚举防护完全依赖前端 catch 覆盖。后端对「账号不存在」(`IDENTIFIER_NOT_FOUND`) 与「密码错」(`INVALID_CREDENTIALS`) 返回不同 code，前端路线 A 主动抹平。若未来改前端 catch 恢复透传，枚举漏洞复现。后端统一返回 `INVALID_CREDENTIALS` 是更稳的纵深防御，属另一个 feature。**已写入 design 第 4 节非显然依赖。**

## 4. User Review Focus

- 用户需要重点拍板：
  - 决策 3 路线 A 是否接受（密码错/账号不存在/未设密码 三者前端统一文案，不精准区分）
  - 默认 tab 停「验证码登录」（假设，可改）
  - 统一文案不带可点链接（假设，可改）
- implement 需要重点遵守：
  - 错误分流锁死按 `err.data.code`，不按 HTTP status（FDR-001）
  - 切 tab 保留 identifier、清对侧字段（FDR-005）
  - 后端零改动、service/context/types 零改动
- code review / QA / acceptance 需要重点复核：
  - AC6 格式错透传 vs AC3/4/5 凭证错统一覆盖 是否同时成立
  - 反向核对 git diff 确认后端零改动
  - FDR-R1 枚举防护是否被实现破坏

## 5. Evidence Confidence Ledger

| Check | Verdict | Evidence Class | Basis | Follow-up |
|---|---|---|---|---|
| Acceptance Coverage Matrix | pass | E | design 3.3 表，AC1-AC10 全部映射到 step；AC5 已标 optional | QA 时 AC5 跳过需在 qa.md 注明 |
| DoD Contract | pass | E | design 3.4，lint+4 手工场景+后端零改动+attention 更新提醒 | acceptance 反查 git diff |
| Steps and checks traceability | pass | E | checklist 4 steps 全部 exit_signal yes/no，checks 追溯 design AC | step3 错误分流判据已锁死 |
| Roadmap contract compliance | n/a | E | 非 roadmap 起头 | none |
| Module interface design | n/a | E | 未新增/改动 module interface，纯页面内改动 | none |
| Validation and artifacts | pass | E | design 1.3 命令+1.6 交付物+1.7 清洁度，与 attention.md 一致 | implement 前预检 lint 基线 |

Summary: E=6, C=0, H=0, H-only core checks=none。所有核心检查均有 Embedded 证据，无 H-only。

## 6. Residual Risk

- FDR-R1 账号枚举防护依赖前端抹平（见 3.residual-risk），已写入 design 第 4 节，acceptance 重点复核。
- 同宿主同类 agent 审查异构性不足——findings 虽全有代码事实支撑，但建议 acceptance 阶段另起一次人工 code review 复核错误分流实现。

## 7. Verdict

- Status: passed
- Next: 交给用户整体 review。用户确认后回 `cs-feat-design` 把 status 从 draft 改 approved，再进 `cs-feat-impl`。
