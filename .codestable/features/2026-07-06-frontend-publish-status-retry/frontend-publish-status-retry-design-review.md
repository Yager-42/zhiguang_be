---
doc_type: feature-design-review
feature: 2026-07-06-frontend-publish-status-retry
status: passed
reviewed: 2026-07-06
round: 2
---

# frontend-publish-status-retry feature design 审查报告（round 2）

## 1. Scope And Inputs

- Design: .codestable/features/2026-07-06-frontend-publish-status-retry/frontend-publish-status-retry-design.md
- Checklist: 同目录 frontend-publish-status-retry-checklist.yaml（4 step / 18 check）
- Intent / brainstorm: none
- Roadmap: none
- Related docs: compound/comment-async-submit.md、compound/counter-likecount-read-sds-not-mysql.md、round 1 design-review.md
- Code facts checked: PublishAttemptService.java、PublishAttemptMapper.xml、PublishManagerImpl.java、KnowPostController.java、PublishAcceptedResponse/PublishStatusResponse.java、knowpostService.ts、apiClient.ts

### Independent Review

- Status: completed
- Detection: native-agent（general-purpose subagent，无 Paseo，同类降级）
- Provider / agent: general-purpose（agentId a428222f8b8c2ee9c）
- Raw output: 见本报告第 3 节（已逐条本地事实核验）
- Merge policy: round 1 三 blocking 逐条本地代码核验已消化；round 2 新 finding 5 条已核验，I5/N4/Sg4 已补 design，N5/Sg5 不补（可接受）
- Gate effect: 无 blocking，可交用户整体 review

## 2. Design Summary

- Goal: 前端补发布状态轮询 + 失败 UI + 重试，让异步发布闭环；修 publish 双 bug
- Key contracts: 3 service（publish 修双 bug / publishStatus / retryPublish）+ usePublishStatus hook（phase 四态+cleanup+attemptId 闭包持有）+ CreatePage 四态 UI
- Steps: 4（service/types → hook → CreatePage → harden+S3 代码审查）
- Checks: 18，覆盖 9 验收场景 + 反向核对
- Baseline: npm run lint + curl 带 token + 浏览器四态

## 3. Findings

### round 1 blocking 消化核验

- **B1 retry 复用同一 attemptId** ✅ 摘要/mermaid/hook 签名/1.3/1.4/S2/checklist 2.4+3.3 全改对；1.4 补 `restartFailedAttempt UPDATE` 事实经 mapper xml:82-96 核验属实
- **B2 publish 双 bug（token+body）** ✅ R1 拆双层，step1 curl 带 Bearer token，编排层补 tokens.accessToken，1.2 三检齐全；knowpostService.ts:32 漏 token + setTop:37 等全传 token 对比属实
- **B3 timeout 断层** ✅ 补 timeout 后重试/我的知文出口，明写"60s≠后端失败判定"，failedStep 枚举补 stuck_publishing(:232)/critical_publish(PublishManagerImpl:138)，轮询副作用（@Transactional+recover）记录；STUCK_TIMEOUT:29=5min 核验属实

### round 1 important/nit/suggestion 处理

- I1 编排层补 token ✅ | I2 S3 降级 diff review（后端 isRetryable:242-244 成对设 failed+publish_failed 恒 true，不可达）✅ | I3 step3 拆场景 ✅ | I4 S7 补"不重复触发+单 post 去重"（acceptPublish:67 命中 existing schedulePublishWork=false）✅
- N1 phase 改名 ✅ | N2 failedStep 枚举补全 ✅ | N3 schema 无 verification 布尔（历史一致，非本 feature 问题，保留）| Sg1 cleanup 新轮询前清旧 ✅ | Sg2 submitting 映射 ✅ | Sg3 /post/{postId} ✅

### blocking
none。

### important
none（I5 hook 闭包持有 attemptId 未显式声明——已本轮补入 design 2.1，消化）。

### nit

- N4 design 1.4 行号 `restart:84` 偏差 → 已改为 `restartFailedAttempt:82-96`（本轮补）
- N5 mermaid timeout 出口"去我的知文"未画独立节点——文字已补，mermaid 简化可接受，不补

### suggestion

- Sg4 retry 无 body 显式声明 → 已补 checklist 1.3"retry 无 body，path variable + token only"（本轮补）
- Sg5 timeout/failed 时 submitting=false——逻辑可推导（phase≠publishing），不补

### learning

- L1 后端发布状态机是"单 attempt 原地重启"模型——B1 修订核心，1.4 已回写
- L2 getPublishStatus @Transactional 触发 recoverStuckPublishingIfNeeded 副作用——design 2.2 已记录

### praise

- P1 B1 修订彻底（7 处全改），1.4 补 UPDATE 事实经 mapper 核验属实，非假设改假设
- P2 B2 拆双层精准（token 层 + body 层分开），curl 显式带 Bearer
- P3 B3 把不可消除的断层变成显式产品取舍（"60s≠后端失败判定"），failedStep 两值+行号+文案映射+轮询副作用四点全补
- P4 1.4 四枚举全标代码行号，回应 round 1 RR2 后端契约权威来源标注
- P5 I2 S3 降级处理干净，不为凑场景硬造 mock

### residual-risk

- RR1 前端 0 测试 + 后端 MockMvc 不验真实 HTTP，S1-S9 全手工无兜底——design 不能凭空造测试基础设施，acceptance 阶段补手工回归 checklist，design:220 已埋 attention 候选
- RR2 `recoverStuckPublishing` @Scheduled(fixedDelay=60000) 兜底扫描 design 未提——与"后端 5min 转 failed"语义一致，非缺陷；实现者若验证"5min 转 failed 谁触发"会少一条路径（轮询时 recover + 定时 recover 双触发），可在 implement 时留意，非阻塞
- RR3 attemptId hook 持有方式已补 design 2.1，但实现细节（ useRef vs useState）归 implement 自决

## 4. User Review Focus

- 用户需重点拍板：B3 timeout 取舍（design 已给方案——60s 停轮询 + 重试/我的知文出口，后端 5min 转 failed 用户可在我的知文看到结果）；owner 已定 60s/停留/不刷新恢复三决策不变
- implement 需重点遵守：B1 retry 复用同一 attemptId（hook 闭包持有）；B2 publish 传 token+body 双参；cleanup 含"新轮询前清旧"；getPublishStatus 有副作用 2s 是频率上限
- code review/QA/acceptance 重点复核：B1 状态模型、B2 token 传递、B3 timeout 出口 UI、S3 代码审查（retryable===false 分支）、R2 cleanup

## 5. Evidence Confidence Ledger

| Check | Verdict | Evidence Class | Basis | Follow-up |
|---|---|---|---|---|
| Acceptance Coverage Matrix | pass | E | 9 场景+矩阵全，S3 降级 diff review 明写 | none |
| DoD Contract | pass | E | Design/Impl/Review/QA/Acceptance + 命令 + 产物齐全 | none |
| Steps and checks traceability | pass | E | 4 step/18 check 可追溯，exit_signal 拆场景 | none |
| Roadmap contract compliance | n/a | E | 非 roadmap 起头 | none |
| Module interface design | pass | C | service 签名含 token/body/返回类型；hook phase 与 attemptStatus 区分；attemptId 闭包持有已声明 | none |
| Validation and artifacts | pass | E | 命令带 token+产物齐 | none |

Summary: E=5, C=1, H=0, H-only core checks=none。

## 6. Residual Risk

- RR1 0 测试回归无兜底 → acceptance 补手工回归 checklist
- RR2 recoverStuckPublishing @Scheduled 兜底未提 → 非缺陷，implement 留意双触发
- RR3 attemptId 持有实现细节 → implement 自决

## 7. Verdict

- Status: **passed**
- round 1 三 blocking 全部 ✅已消化（本地代码核验属实），round 2 无新 blocking；I5/N4/Sg4 已本轮补入 design/checklist。
- reviewer: native-agent（环节 A 完成，OCR 不可用跳过环节 B）
- Next: 交给用户整体 review。用户确认后回 cs-feat-design 标 approved → cs-feat-impl。
