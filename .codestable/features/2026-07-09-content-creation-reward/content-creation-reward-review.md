---
doc_type: code-review
feature: 2026-07-09-content-creation-reward
status: passed
reviewed: 2026-07-09
round: 1
reviewer: native-agent
---

# content-creation-reward 代码审查报告

## 1. Scope And Inputs

- Design: `.codestable/features/2026-07-09-content-creation-reward/content-creation-reward-design.md`（approved）
- Checklist: 同目录 `content-creation-reward-checklist.yaml`
- 本轮 diff：后端 9 改 + 5 新，前端 4 改 + 2 新
- Code facts checked: ContentRewardService / PublishAttemptService / CommentWriteConsumer / WalletService.grant+apply / SecurityConfig / 4 个测试文件 / 前端 6 文件

### Independent Review

- Status: completed
- Detection: native-agent（general-purpose subagent，同类模型降级）
- Raw output: 1 blocking + 2 important + 2 nit + 2 suggestion + 7 praise + 2 residual-risk
- Merge policy: 逐条本地核验——B1 误判驳回（reviewer 看错子模块分支），I1 文档级 nit 接受，I2/N1/N2 真问题已修
- Gate effect: blocking 清零（B1 驳回 + I2 修复后），可放行

## 2. Findings

### blocking

none（B1 驳回，见下）

> **B1 驳回**：reviewer 称"前端 6 文件在子模块不存在"——本地核验：前端文件在 `zhiguang_fe` dev 分支工作区（未 commit），`ls` + `grep contentReward` 确认 5 文件命中（contentRewardService.ts / contentReward.ts / CreatePage.tsx / CommentSection.tsx + 2 css）。reviewer 看了后端仓库 `plan` 分支的子模块指针（d2a5b833 旧 commit），没看 dev 工作区未提交改动。前端代码确实存在，npm lint 绿 + 评论奖励端到端实测通过（wallet 69→71 + ledger CONTENT_CREATION_REWARD 记录）已证明。

### important

- [x] **CR-I1** `{design §2.2 Top3 风险1}` **design 措辞误导：发帖侧 completePublish 自身是 @Transactional，非"调用方 runPublish 无外层事务"**
  - Evidence: `PublishAttemptService.java:149` completePublish 标 `@Transactional`，:180 调 reward。design 说"调用方 runPublish 无外层事务，REQUIRES_NEW 天然隔离"——实际 reward 的直接 caller 事务是 completePublish 自身的事务，与评论侧拓扑相同。
  - Impact: 实现正确（catch 在 service 内兜底，两侧行为一致），但 design 风险论证基于错误前提，误导未来维护者。
  - 处理: 文档级 nit，实现无错。design 已 approved 不再改措辞，记入此处供未来参考。两侞性能/风险一致，都靠 catch-in-service 隔离。

- [x] **CR-I2** `{PublishManagerAttemptServiceTest / CommentWriteConsumerTest}` **挂载点 hook 单测缺失——completePublish/CommentWriteConsumer 成功路径未 verify reward 被调**
  - Evidence: completePublish 成功测试只 verify markSucceeded，没 verify rewardPostCreation；CommentWriteConsumer 成功路径 InOrder 不含 contentRewardService。若未来误删 reward 调用，测试仍绿。
  - Impact: 挂载点 hook 无回归网。AC4/AC5a/AC5b 幂等路径无单测守护。
  - 处理: **已修**——补 3 处 verify：(1) completePublish 成功后 `verify(contentRewardService).rewardPostCreation(7L, 9L)`；(2) completePublish 失败（2 个测试）`verify(never()).rewardPostCreation(anyLong(), anyLong())`（AC9 反向核对）；(3) CommentWriteConsumer 成功路径 InOrder 加 `verify(contentRewardService).rewardCommentCreation(7L, 101L)`；(4) DuplicateKey 短路路径 `verifyNoInteractions(contentRewardService)`。175 测试全过。

### nit

- [x] **CR-N1** `{CommentWriteConsumerTest duplicateMetadataInsert...}` DuplicateKey 短路路径未 verify 不调 reward。**已修**（CR-I2 处理 4）。
- [x] **CR-N2** `{ContentRewardServiceTest rewardIdempotentOnRetryOnlyCallsGrantOnce}` 测试名说"only calls grant once"但断言 `times(2)`，名实不符。**已修**：改名 `rewardDelegatesIdempotencyToGrantOnRetry`。

### suggestion

- CR-S1 ContentRewardProperties 用 @Component + @ConfigurationProperties——与 WalletProperties 一致（同为 @Component 模式），无分叉。无需改。
- CR-S2 ContentRewardController CORS——SecurityConfig 已 `/**` 全开，无需 @CrossOrigin。

### praise

- **P1** catch 位置严格符合 design 契约——`ContentRewardService.grantSafely` private 方法在 REQUIRES_NEW 方法体内 try-catch 吞所有 Exception 返回 0，异常不冒泡 caller。规避 UnexpectedRollbackException 陷阱。
- **P2** businessRef 幂等格式正确复用 grant 内置 matchSingleSidedGroup——`content-reward:post:{id}` / `content-reward:comment:{id}`，同帖/同评论重试 LedgerIdentity 一致幂等返回。
- **P3** SecurityConfig 未加 permitAll——config 接口走默认 authenticated，需登录态，符合 A2-b。
- **P4** DTO 零改动——PublishStatusResponse/CommentSubmitResponse 不加 rewardAmount，金额走 config 接口。
- **P5** 挂载点位置正确——发帖 markSucceeded 后 / 评论 updateStatus(succeeded) 后；failPublish/stuck/DuplicateKey 路径不发（AC9）。
- **P6** 构造器改动不破坏 Spring 注入——PublishAttemptService 保留 @Autowired public 构造器委托 8 参 package 构造器，4 测试文件全适配。
- **P7** 清洁度达标——后端无 console.log/TODO/死 import/注释代码，reward 失败用 slf4j warn 带 businessRef。

### residual-risk

- **R1** AC8 REQUIRES_NEW 隔离在 MockMvc 单测层不可证伪（design 已承认）——依赖手工集成验证。本次评论奖励端到端实测通过（curl 发评论 + wallet 69→71 + ledger 记录），但 AC8 的"mock wallet 抛 RuntimeException 验证评论仍 succeeded"未手工执行。建议 QA 环节补：临时改 ContentRewardProperties.enabled=false 或 mock wallet 抛异常，发评论确认评论成功 + ledger 无记录。
- **R2** config 接口 enabled=false 仍返回真实金额（design A3 接受）——风险全在前端 enabled 判断。前端 CreatePage/CommentSection 已实现 `rewardConfig?.enabled && amount > 0` 双判断，端到端 enabled=true 已测，enabled=false 未手工测。建议 QA 切配置验证前端不显示。

## 3. Verdict

- Status: **passed**
- blocking 清零（B1 误判驳回），I2/N1/N2 已修复，175 测试全过。
- 下一步：进 cs-feat-qa（含浏览器实测 enabled=false 路径 + AC8 手工集成）→ cs-feat-accept → commit + push。
