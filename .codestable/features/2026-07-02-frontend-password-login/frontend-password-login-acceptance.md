---
doc_type: feature-acceptance
feature: 2026-07-02-frontend-password-login
status: passed
accepted: 2026-07-03
round: 1
---

# 前端登录页账号密码登录 验收报告

> 阶段：阶段 3（验收闭环）
> 验收日期：2026-07-03
> 关联方案 doc：`.codestable/features/2026-07-02-frontend-password-login/frontend-password-login-design.md`

## 1. 接口契约核对

对照 design 第 2.1 节名词层逐一核查：

**名词层"现状 → 变化"逐项核对**：
- [x] `loginMode: "CODE" | "PASSWORD"` state（默认 CODE）：代码 `LoginPage.tsx:45` `useState<LoginMode>("CODE")` ✓
- [x] `identifierType: "PHONE" | "EMAIL"` state（默认 PHONE，仅密码 tab 用）：`LoginPage.tsx:46` ✓
- [x] `password` state：`LoginPage.tsx:48` ✓
- [x] `identifier` 两 tab 共用、切 tab 保留：`switchMode` 只清对侧字段，不清 identifier ✓
- [x] 提交 payload 按 loginMode 分流：`LoginPage.tsx:75-78` CODE→`{identifierType:"PHONE",identifier,code}`，PASSWORD→`{identifierType,identifier,password}` ✓
- [x] 错误文案 PASSWORD 模式统一覆盖、CODE 透传：`resolveLoginError` ✓

**流程图核对**（design 2.2 mermaid）：
- [x] tab 分流 / PASSWORD 提交 / CODE 提交 / 失败文案分流 节点均有代码落点（loginMode/switchMode/handleSubmit/resolveLoginError）

无偏差。

## 2. 行为与决策核对

**需求摘要逐项验证**：
- [x] 验证码登录 / 密码登录 tab 切换，默认验证码：`loginMode` 默认 CODE + tabs UI ✓
- [x] 密码 tab = 类型切换 + 账号 + 密码 + 登录：select(手机号/邮箱) + identifier + password input ✓
- [x] 账号类型取值决定 identifierType：select onChange → setIdentifierType ✓

**明确不做逐项核对**（design 0 节反向核对）：
- [x] 未动后端：`git diff src/main/java` 零改动 ✓
- [x] 未动 authService/AuthContext/types/auth.ts：grep 反向核对零改动 ✓
- [x] 未接 password/reset：authService 仅 6 方法，无 resetPassword ✓
- [x] 无「忘记密码」链接：LoginPage.tsx 无相关 UI ✓
- [x] 未用 USERNAME：select options 仅 PHONE/EMAIL ✓
- [x] 未用 zgId 登录：无 zgId 相关代码 ✓
- [x] 未引入新依赖：package.json 零 diff ✓

**关键决策落地**：
- [x] D1 账号框 phone+email 都接受 + 类型切换控件：select 实现 ✓
- [x] D2 tab 切换：tabs UI + switchMode ✓
- [x] D3 路线 A 一刀切文案：resolveLoginError 凭证类覆盖、格式类透传、5xx 透传（qa-fix）✓

**编排层"现状 → 变化"逐项核对**：
- [x] handleSubmit 按 loginMode 分流：`LoginPage.tsx:74-82` ✓
- [x] catch 分流（PASSWORD 统一/5xx 透传，CODE 透传）：`resolveLoginError` ✓
- [x] tab 切换清对侧字段 + 清 error：`switchMode:110-119` ✓
- [x] isDisabled 按 loginMode 分流：`LoginPage.tsx:122` ✓
- [x] handleSendCode 仅 CODE 模式可见：CODE 分支 JSX 内 ✓

**流程级约束核对**：
- [x] 错误语义：5xx 透传 / 凭证类统一 / 格式类透传 / 防御性回退，四档清晰 ✓
- [x] 账号枚举防护：IDENTIFIER_NOT_FOUND 与 INVALID_CREDENTIALS 前端统一覆盖 ✓

**挂载点反向核对**（design 2.3，4 条）：
- [x] M1 tab 切换 UI：`LoginPage.tsx:135-153` tabs/ta/tabActive ✓
- [x] M2 密码 tab 表单：`LoginPage.tsx:155-205` select+identifier+password ✓
- [x] M3 PASSWORD 分支 payload：`LoginPage.tsx:76-77` ✓
- [x] M4 PASSWORD catch 统一文案：`resolveLoginError:27-32` ✓
- [x] **反向 grep 核查**：loginMode/switchMode/resolveLoginError/PASSWORD_FALLBACK 所有引用均落在清单范围内，无清单外漏记 ✓
- [x] **拔除沙盘推演**：删 tabs UI + PASSWORD 分支 + resolveLoginError + password state → 退回原验证码-only 登录页，无残留 ✓

## 3. 验收场景核对

对照 design 第 3 节 + QA round 2 证据：

- [x] **AC1** 密码登录成功(手机号)：curl 13800000001/Test1234 → user.id=12 + token ✓（QA2 + 最终审计复验）
- [x] **AC2** 密码登录成功(邮箱)：curl test1@zhiguang.cn/Test1234 → user.id=13 + token ✓（QA2）
- [x] **AC3** 密码错误：INVALID_CREDENTIALS → 统一文案「密码错误或不存在，可使用验证码登录」✓（QA2 + 最终审计）
- [x] **AC4** 账号不存在：IDENTIFIER_NOT_FOUND → 统一文案 ✓（QA2）
- [x] **AC5** 账号未设密码：与 AC3 等价，跳过（注册必填，无无密码账号）✓
- [x] **AC6** 格式不匹配(双向)：BAD_REQUEST → 透传「手机号格式错误」/「邮箱格式错误」✓（QA2 + 最终审计）
- [x] **AC7** tab 切换清空：switchMode 代码确认 + 用户浏览器肉眼确认 ✓
- [x] **AC8** 验证码 tab 回归：发码→抠码→登录成功 user.id=12 ✓（QA round 1）
- [x] **AC9** 默认 tab=CODE：useState 初始值 + 用户确认 ✓
- [x] **AC10** 类型检查：npm run lint 零错误 ✓（最终审计复验）

**功能性前端浏览器肉眼验证**：
- [x] 用户在 QA round 1 后确认 UI 无问题（tab 切换/字段可见/错误文案/跳转），qa-fix 仅改 5xx 分支不影响 UI ✓

**review/QA 重点复核**：
- [x] review round 2 Test And QA Focus 已覆盖（I1 修复 + 400 回归）
- [x] review residual risk 逐条处理：I1 已修；N1 网关 HTML 透传留 residual（见第 9 节）；R2 design 补 5xx 契约已做
- [x] QA round 2 passed，无 failed/blocked，residual-risk 非核心路径

## 4. 术语一致性

- `loginMode`/`identifierType`/`password`/`resolveLoginError`/`PASSWORD_FALLBACK`/`switchMode`：代码命名与 design 2.1 一致 ✓
- 禁用词 `USERNAME`：select options 无命中 ✓
- `zgId` 未作登录用 ✓

无不一致。

## 5. 领域影响盘点

- **新名词**：无新领域术语。`loginMode`/`identifierType` 是 UI 局部状态，非领域实体，不需进 CONTEXT.md。
- **结构性选择**：无。纯前端页面内改动，未新增模块/跨模块接口/新依赖。
- **流程级约束**：错误分流判据（5xx 透传 / 凭证类统一 / 格式类透传）是登录页局部逻辑，非全局约束，不需 ADR。

结论：无领域维度变更，不需要 `cs-domain`。

## 6. requirement delta / clarification 回写

- design frontmatter 原 `requirement: frontend-password-login`，但 `.codestable/requirements/` 下**不存在该 req 文件**——design 阶段误填。
- 本 feature 是补「后端已有能力（密码登录接口）的前端入口」，规模小，全程未走 cs-req 流程，无 owner-approved req delta。
- 处置：**已将 design frontmatter `requirement` 字段改空**（不强行 backfill req）。acceptance 不自由重写 requirement（L3 Global Route Governance）。
- 结论：无 requirement 影响。若未来要把「密码登录」作为正式能力愿景落档，走 `cs-req` 起草。

## 7. roadmap 回写

非 roadmap 起头（design frontmatter 无 roadmap/roadmap_item 字段）→ 跳过。

## 8. attention.md 候选盘点

- [x] **有候选**：attention.md 第 21 行「登录走验证码（前端 LoginPage 只做手机号+验证码登录，无密码登录入口）」**已过时**——本 feature 上线后 LoginPage 支持验证码 + 密码登录。
  - 候选 1：更新该条为「登录支持验证码 + 密码登录（验证码 tab 默认，密码 tab 新增；密码 tab 支持手机号/邮箱）」
  - **不擅自写入**——退出后由用户确认走 `cs-note`

其他知识出口分流：
- 后端 BusinessException 全返 400、错误分流只能靠 `err.data.code`（review L1 learning）→ 复用价值高，退出后提示 `cs-keep` 沉淀到 compound
- 无用户操作指南变化（登录交互自解释）→ 不需 cs-doc-tutorial
- 无公开 API/组件表面变化 → 不需 cs-doc-api

## 9. 遗留

- **REV2-N1 网关 HTML 透传**（nit，非阻塞）：网关/代理层 5xx（nginx 502 HTML）时 `err.message` 可能是整段 HTML。后端自身 500 走 JSON 不受影响。CODE 模式同病。建议后续统一 5xx 显示固定友好文案（可走 `cs-issue` 或下个 feature）。
- **顺手发现 1**：`types/auth.ts:1` 的 `IdentifierType` 含 `USERNAME` 死代码，后端不认——建议后续 `cs-refactor` 清理。
- **顺手发现 2**：验证码 tab 只支持 PHONE、密码 tab 支持 PHONE+EMAIL，两边能力不对称——若产品要统一是另一个 feature。
- **顺手发现 3**：前端无重置密码入口（后端 `/auth/password/reset` 已实现）——「忘记密码」流程是另一个 feature。
- **架构债**：账号枚举防护单点依赖前端 catch（review L1），后端统一返 INVALID_CREDENTIALS 是更稳的纵深防御，属另一个 feature。

## 10. 最终审计

**聚合命令复验**（最终工作区状态）：
- `cd zhiguang_fe && npm run lint` → exit 0 ✓（re-verified）
- 后端 8080 + 前端 5173 在运行 ✓

**场景抽样复核**（re-verified，非 trust-prior）：
- 500 路径：curl 畸形 JSON → status 500 + INTERNAL_ERROR → 前端 5xx 透传 ✓
- 400 凭证错：INVALID_CREDENTIALS → 统一文案 ✓
- 400 格式错：BAD_REQUEST → 透传 ✓
- AC1 成功：user.id=12 + token ✓

**交付物落盘复核**：
- 修改：`zhiguang_fe/src/pages/LoginPage.tsx`（+162/-44）、`LoginPage.module.css`（+42）✓
- spec 回写：design 3.1 已补 5xx 透传契约行（REV2-R2 闭环）；design frontmatter requirement 改空 ✓
- 验收报告：本文件 ✓
- 无新增文件、无后端改动、无依赖变更 ✓

**diff 清洁度复核**：无 console.log/TODO/FIXME/注释掉代码/无用 import ✓

**知识沉淀出口分流**：
- attention.md 候选 1 条（登录方式描述过时）→ 退出后 `cs-note`
- 后端错误码分流 learning → 退出后 `cs-keep`
- design 2.5 无「建议沉淀的 convention」段（本 feature 未做微重构）

**覆盖率诚实标记**：
- re-verified：AC1/AC3/AC6/AC10 + 500 路径 + 清洁度 + lint（最终审计实跑）
- trust-prior-verify：AC2/AC4/AC8（QA round 1/2 已验证，qa-fix 未触碰这些路径，最终审计引用）

**缺口处理**：无未处理缺口。REV2-R2（design 补 5xx 契约）已在本验收完成 spec 回写。

## Verdict

- Status: **passed**
- 原始契约满足 ✓、验证证据充分且最终状态成立 ✓、交付物真实落盘 ✓、知识沉淀已分流 ✓
- 待用户终审确认后，cs-feat 工作流走完。后续 BUG 走 issue 流程。
