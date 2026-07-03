---
doc_type: feature-qa
feature: 2026-07-02-frontend-password-login
status: passed
tested: 2026-07-03
round: 2
---

# frontend-password-login QA 报告（round 2 复测）

> 本轮为 qa-fix 后的复测。round 1 已 passed，本轮聚焦 I1 修复运行时验证 + 400 路径回归。AC1/AC2/AC8 等 round 1 已验证且 qa-fix 未触碰（仅改 resolveLoginError 的 5xx 分支），不重跑，引用 round 1 证据。

## 1. Scope And Inputs

- Design: `frontend-password-login-design.md`（approved）
- Checklist: `frontend-password-login-checklist.yaml`（4 steps 全 done）
- Review: `frontend-password-login-review.md`（round 2 passed，I1 已修，无 blocking）
- Diff basis: qa-fix 改动 = `LoginPage.tsx` resolveLoginError 新增 5xx 透传分支（3 行 + 注释）
- Baseline dirty files: comment 相关 4 文件属上 feature 遗留，已隔离
- Feature type: **functional**
- Core evidence gate: I1 修复运行时验证（500 透传）+ AC3/AC4/AC6 回归（400 路径未误伤）

## 2. Verification Matrix

| ID | 来源 | 核心性 | 场景/风险 | 证据类型 | 命令或动作 | 期望 | 结果 |
|---|---|---|---|---|---|---|---|
| QA2-001 | review I1 修复 | core-functional | 500 错误路径现透传 message | API | curl 畸形 JSON 触发 500 | status 500 + INTERNAL_ERROR → 前端透传「服务异常」 | pass |
| QA2-002 | review AC3 回归 | core-functional | 400 密码错误未误伤 | API | curl 错误密码 | status 400 + INVALID_CREDENTIALS → 统一文案 | pass |
| QA2-003 | review AC6 回归 | core-functional | 400 格式错未误伤 | API | curl 手机号 tab 填邮箱 | status 400 + BAD_REQUEST → 透传格式错误 | pass |
| QA2-004 | design AC10 | supporting | 类型检查 | command | npm run lint | 零错误 | pass |
| QA2-005 | round 1 | core-functional | AC1/AC2/AC8（未触碰，引用 round 1） | API | round 1 证据 | 登录成功/验证码回归 | pass（引用） |

## 3. Command Results

- `cd zhiguang_fe && npm run lint` → exit 0：tsc --noEmit 零错误
- 后端 8080 + 前端 5173 均在运行

## 4. Scenario Results

- [x] QA2-001 I1 修复（500 透传）：pass
  - Evidence: `curl 畸形 JSON` → 后端 `status: 500` + `{"code":"INTERNAL_ERROR","message":"服务异常，请稍后重试"}`；前端 `resolveLoginError`：`err instanceof ApiError && err.status >= 500` → `return err.message` → 显示「服务异常，请稍后重试」。**不再落 PASSWORD_FALLBACK**。
  - 对比 round 1：round 1 实测同场景会被吞成「密码错误或不存在」，本轮已修。
- [x] QA2-002 AC3 回归（400 凭证错）：pass
  - Evidence: 错误密码 → `status: 400` + `INVALID_CREDENTIALS`；400 不满足 `>=500`，跳过 5xx 分支，走白名单 → PASSWORD_FALLBACK「密码错误或不存在，可使用验证码登录」。未误伤。
- [x] QA2-003 AC6 回归（400 格式错）：pass
  - Evidence: 手机号 tab 填邮箱 → `status: 400` + `BAD_REQUEST`「手机号格式错误」；跳过 5xx 分支，走白名单 code===BAD_REQUEST → 透传。未误伤。
- [x] QA2-004 类型检查：pass
- [x] QA2-005 AC1/AC2/AC8：pass（引用 round 1，qa-fix 未触碰这些路径）

## 5. Findings

### failed
none。

### blocked
none。

### residual-risk

- **REV2-N1 网关 HTML 透传**（nit，非阻塞）：网关/代理层 5xx（nginx 502 HTML）时 `err.message` 可能是整段 HTML 文本。后端自身 500 走 JSON 不受影响（QA2-001 已验证）。CODE 模式同病。acceptance 阶段决定是否补脱敏。
- **REV2-R2 design 3.1 补 5xx 契约行**：qa-fix 行为正确但 design 文档未更新 5xx 透传契约。acceptance 阶段回写 spec。
- **round 1 QA-012 UI 肉眼验证**：用户已在 round 1 后浏览器确认 UI 无问题（tab 切换/字段/错误文案/跳转）。本轮 qa-fix 仅改 5xx 分支，UI 行为不变，无需复测。
- **REV2-R3 同模型审查异构性**：I1 修复已由 QA 实测确认（E 类证据），不再依赖推断。

## 6. Cleanliness

- Debug output: pass
- Temporary TODO/FIXME/XXX: pass
- Commented-out code: pass
- Unused imports / dead code: pass
- Out-of-scope files: pass（qa-fix 仅改 LoginPage.tsx 的 3 行 + 注释）

## 7. Verdict

- Status: **passed**
- I1 修复运行时确认有效：500 路径现透传「服务异常」，不再误导为密码错。
- 400 路径回归无破坏：AC3/AC6 行为不变。
- Next: **`cs-feat-accept`**（验收闭环）
- acceptance 待办：① design 3.1 补 5xx 透传契约行（spec 回写）；② 决定是否处理 REV2-N1 网关 HTML 透传；③ `cs-note` 更新 attention.md 那条「LoginPage 只做手机号+验证码登录，无密码登录入口」（已过时）。
