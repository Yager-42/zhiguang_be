---
doc_type: feature-code-review
feature: 2026-07-02-frontend-password-login
status: passed
reviewed: 2026-07-03
round: 2
reviewer: subagent
source: cs-feat-impl-qa-fix
next: cs-feat-qa
---

# frontend-password-login 代码审查报告（round 2 复审）

> 本轮为 qa-fix 后的复审。round 1 报告的 important finding I1（500 错误路径被吞成密码错）经 qa-fix 修复，本轮确认修复有效 + 无新问题。round 1 的 nit/suggestion/learning/praise 未变化部分不再重复，仅记录增量。

## 1. Scope And Inputs

- 本轮 qa-fix 改动：`LoginPage.tsx` 的 `resolveLoginError` 新增 5xx 透传分支
  ```typescript
  if (err instanceof ApiError && err.status >= 500) {
    return err.message;
  }
  ```
  位置：`loginMode !== "PASSWORD"` 早返之后、`err.data.code` 白名单之前。注释同步更新。
- 上一轮 review：`frontend-password-login-review.md` round 1，passed，I1=important
- Code facts checked: `apiClient.ts:15-24,90-101`（ApiError.status 来源 = response.status）、`GlobalExceptionHandler.java:75-82`（500 路径）

### Independent Review

- 环节 A（独立 Task agent）：completed。原生 Agent（agentId ae68beb76ab1e6aa4）。同模型降级，残余风险 REV2-R3。
- 环节 B（OCR）：not-available（未安装，同 round 1）。
- Merge policy：reviewer findings 全部经主 agent 本地读代码核验。REV2-N1 经核验 `apiClient.ts:90-101` message 回退分支确认成立；REV2-L2/L3/L4 经核验 5xx 分支位置 + 400 路径 status 确认。

## 2. qa-fix 修复确认

| Round 1 finding | 本轮状态 | 证据 |
|---|---|---|
| I1（important）500 被吞成密码错 | **已修** | `LoginPage.tsx:26-28` 5xx 透传 `err.message`；500 路径不再落 PASSWORD_FALLBACK。qa-fix 实测：畸形 JSON→后端 500 INTERNAL_ERROR→前端现透传「服务异常，请稍后重试」；400 凭证错回归仍走统一文案 |

## 3. Findings（增量）

### blocking
none。

### important
none。I1 已闭环。

### nit

- [ ] REV2-N1 `LoginPage.tsx:26-28` + `apiClient.ts:90-101` 网关/代理层 5xx（nginx 502/504、vite proxy 上游宕机）返回 HTML 时，`err.message` 回退为整段 HTML 文本，PASSWORD 模式会把它塞进 error div。
  - Evidence: `apiClient.ts:90-101` `JSON.parse` 失败时 `message = rawText`；`LoginPage.tsx:27` `return err.message`。
  - Impact: 轻微 UX 退化（密码 tab 看到一段 HTML）。非安全（React 文本节点不 XSS）。后端自身 500 走 JSON 不受影响。CODE 模式同病（也透传同一 message），非本次回归。
  - 建议修复边界: 可选，5xx 分支改为 `err.message?.length > 200 ? "服务异常，请稍后重试" : err.message` 或统一固定文案。非阻塞，acceptance 阶段决定。
  - 来源: native-agent

### suggestion

- [ ] REV2-S1 `LoginPage.tsx:26` 用 `err.status >= 500` 而非 `=== 500`，正确覆盖 502/503/504（网关层）。比 I1 review 建议的「显式排除 INTERNAL_ERROR」更稳。无需改。来源: native-agent

### learning

- REV2-L1 qa-fix 加 5xx 透传不算偷偷扩 design——是 design 3.1「非凭证错不落统一文案」防御性思路的自然延伸。**建议 acceptance 在 design 3.1 补一行 5xx 透传契约**（非本次阻塞）。
- REV2-L2 5xx 分支位置正确（白名单之前），否则 500 的 INTERNAL_ERROR code 会落 PASSWORD_FALLBACK 复现 I1。
- REV2-L3/L4 400 凭证错（INVALID_CREDENTIALS/IDENTIFIER_NOT_FOUND，status 400）与 400 格式错（BAD_REQUEST，status 400）均不满足 `>= 500`，跳过 5xx 分支，行为不变。AC3/AC4/AC6 不回归。

### praise

- REV2-P1 I1 实质性修复，方向正确，后端 500 时密码 tab 显示「服务异常」而非误导性「密码错误」。
- REV2-P2 注释同步更新，与代码一致。
- REV2-P3 非 ApiError 的 5xx（断网 fetch TypeError）落 PASSWORD_FALLBACK，合理退化（断网无法区分凭证错与服务端故障，统一文案引导换通道）。
- REV2-P4 修复边界收敛：3 行改动，未动 CODE 分支/白名单/switchMode/isDisabled，回归面极窄。

### residual-risk

- REV2-R1 N1 网关 HTML 透传：仅代理层 5xx，UX 轻微，CODE 同病。acceptance 决定是否补脱敏。
- REV2-R2 design 3.1 未同步补 5xx 契约行：qa-fix 行为正确但 design 文档未更新，未来对照 design 审代码会以为 5xx 透传是「实现自决」。建议 acceptance 补 design（属 spec 回写，走 `cs-feat-accept` / `trellis-update-spec`）。
- REV2-R3 同模型复审异构性：5xx 代码事实已逐行核验（E 类），N1 网关 HTML 行为是推断（C 类），建议 QA/acceptance 实测确认。

## 4. Test And QA Focus（QA 复测）

1. **I1 实测确认已修**：造后端 500（停 MySQL/造 NPE），密码 tab 提交 → 应显示「服务异常，请稍后重试」（透传），不再显示「密码错误或不存在」
2. **400 凭证错回归**：AC3 密码错误 → 仍显示统一文案
3. **400 格式错回归**：AC6 → 仍透传格式错误
4. **N1 网关 5xx 实测**（可选）：停后端进程保留 vite proxy，密码 tab 提交 → 观察 error div 是否渲染 HTML 文本
5. **断网回归**：密码 tab 显示 PASSWORD_FALLBACK（合理退化）
6. **CODE 模式 5xx 回归**：CODE tab 后端 500 → 透传「服务异常」（早返路径未受 qa-fix 影响）

反向核对：qa-fix 未改后端/service/context/types/CODE 分支/isDisabled/switchMode，未引新依赖 ✓

## 5. Evidence Confidence Ledger

| Check | Verdict | Evidence Class | Basis | Follow-up |
|---|---|---|---|---|
| I1 修复有效（5xx 透传） | pass | E | LoginPage.tsx:26-28 + qa-fix 实测 500 透传 | QA 复测确认 |
| 5xx 分支位置正确 | pass | E | LoginPage.tsx:26 在白名单前 | none |
| 400 路径未误伤 | pass | E | status 400 不满足 >=500，curl 实测 AC3/AC6 回归 | none |
| 无新 blocking/important | pass | E | reviewer + 主 agent 核验 | none |
| 网关 HTML 透传（N1） | warn | C | apiClient.ts message 回退分支推断 | QA/acceptance 实测 |

Summary: E=4, C=1, H=0, H-only core checks=none。

## 6. Residual Risk

- REV2-R1 N1 网关 HTML 透传（nit，acceptance 决定）
- REV2-R2 design 3.1 补 5xx 契约行（acceptance spec 回写）
- REV2-R3 同模型复审，N1 推断需实测

## 7. Verdict

- Status: **passed**
- Reviewer: subagent（环节 A completed，环节 B OCR not-available）
- I1 闭环：已修，无新 blocking/important
- Next: 重跑 `cs-feat-qa`（qa-fix 改了 diff，QA 需复测 I1 修复 + 回归），QA passed 后进 `cs-feat-accept`
