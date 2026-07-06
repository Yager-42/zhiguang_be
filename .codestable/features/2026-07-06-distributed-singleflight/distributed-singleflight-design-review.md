---
doc_type: feature-design-review
feature: 2026-07-06-distributed-singleflight
status: passed
reviewed: 2026-07-06
round: 3
---

# distributed-singleflight feature design 审查报告

## 1. Scope And Inputs

- Design: `.codestable/features/2026-07-06-distributed-singleflight/distributed-singleflight-design.md`
- Checklist: `.codestable/features/2026-07-06-distributed-singleflight/distributed-singleflight-checklist.yaml`
- Intent / brainstorm: none
- Roadmap: none
- Related docs: `.codestable/attention.md`, `.codestable/reference/shared-conventions.md`, `.agents/skills/cs-feat-design/references/codebase-design.md`
- Code facts checked:
  - `CounterServiceImpl.getCounts`
  - `RelationController.counter`
  - `UserCounterServiceImpl.rebuildAllCounters`
  - `UserCounterService`
  - `FollowFeedServiceImpl.readAuthorHead`
  - `ModerationReviewExecutorImpl.review`
  - `ModerationReportMapper.xml`
  - `KnowPostServiceImpl.getDetail`
  - AI-Meeting `DistributedInterviewAiSingleFlightService`

### Independent Review

- Status: completed
- Detection: native-agent
- Provider / agent: Noether / Erdos / Huygens
- Raw output: 第一轮发现 2 个 blocking、1 个 important；第二轮剩余 1 个 important；第三轮 verdict 为 `passed`。
- Merge policy: 独立 finding 已逐条本地核验并合并进 design/checklist。
- Gate effect: none

## 2. Design Summary

- Goal: 将 AI-Meeting 的分布式 single-flight 模式迁移为 zhiguang 公共能力，并接入计数、用户计数、Feed head、审核 LLM 四个场景。
- Key contracts:
  - `stage` / `requestKey` 不能为空，不沿用 AI-Meeting 的默认 key 兜底。
  - 公共接口通过显式 `TypeReference` 或 codec 做 typed result replay。
  - `user-counter` 通过新增 `UserCounterRebuildAdapter.rebuildAndRead(userId)` 产生 replay map，不假设现有 `rebuildAllCounters` 返回结果。
  - `moderation-llm` key 固定为 `report:{reportId}:retry:{retryCount}`，`retryCount` 来自 `findById(reportId)` 读取到的 pending report。
  - 审核终态副作用通过 pending CAS 单执行，retry 调度通过 `sourceRetryCount` CAS 单执行。
- Steps: 6 步，先公共骨架，再四个业务挂载点，最后配置与验证。
- Checks: 9 项，覆盖并发成功、失败语义、takeover、回滚、审核 retryable CAS，以及本地 single-flight 非本轮范围。
- Baseline / validation: checklist YAML 与 design frontmatter 已通过 `validate-yaml.py`。

## 3. Findings

### blocking

- none

### important

- none

### nit

- none

### suggestion

- 实现完成后，checklist 中通配符测试命令应替换为真实 test class 或在 QA 证据中记录实际命中的测试。

### learning

- AI-Meeting 可迁移的是协调生命周期：L1 replay、`acquireOrJoin`、owner heartbeat、result store、stream terminal event、failure classification。不能照搬的是 `String Supplier`、`ai:flight:*` key、空 key 默认兜底和 interview/AI 外壳。
- `KnowPostServiceImpl.getDetail` 排除在本轮是正确边界：当前 `pageKey` 只含内容 ID 和 layout version，但方法内还有当前用户权限判断与最终 enrich。

### praise

- design 已把普通 Redisson 互斥锁、本地 JVM single-flight 和真正的分布式 follower result replay 区分开。
- 审核场景最终收敛到 `reportId + retryCount` key、pending CAS、retryCount CAS，避免了候选式实现路径。

## 4. User Review Focus

- 用户需要重点拍板：本 feature 只覆盖四个场景，暂不替换 `KnowPostServiceImpl.getDetail`。
- implement 需要重点遵守：typed codec 必须显式，不得写字段候选 fallback；审核 retryableFailure 也必须通过 `sourceRetryCount` CAS 单执行。
- code review / QA / acceptance 需要重点复核：并发测试是否真的证明 owner 执行次数为 1，审核终态和 retry 调度是否都最多成功一次。

## 5. Evidence Confidence Ledger

| Check | Verdict | Evidence Class | Basis | Follow-up |
|---|---|---|---|---|
| Acceptance Coverage Matrix | pass | E+C | design 第 3 节列出核心场景，checklist checks 可追踪到 steps；代码事实来自 CodeGraph 和文件核验。 | none |
| DoD Contract | pass | E | design 第 3 节和 checklist dod.commands / artifacts 已覆盖。 | none |
| Steps and checks traceability | pass | E | checklist 每个 check 都有 covered_by。 | none |
| Roadmap contract compliance | n/a | E | 本 feature 不是 roadmap 起头。 | none |
| Module interface design | pass | E+C | design 第 2.1 节给出接口示例、typed codec 约束、user-counter adapter、审核 CAS 边界。 | implementation 时固定签名 |
| Validation and artifacts | pass | E | checklist 与 design frontmatter 已通过 `validate-yaml.py`。 | none |

Summary: E=6, C=2, H=0, H-only core checks=none。

## 6. Residual Risk

- 定向测试类名仍是实现阶段产物，当前 checklist 使用通配符命令作为计划占位。
- `scheduleRetry` 的 mapper 签名需要实现阶段同步 Java interface、XML SQL 和单测，避免只改一侧。

## 7. Verdict

- Status: passed
- Next: 交给用户整体 review；用户确认后可将 design 状态从 `draft` 改为 `approved`，再进入 `cs-feat-impl`。
