---
doc_type: feature-design-review
feature: 2026-07-09-content-creation-reward
status: passed
reviewed: 2026-07-09
round: 2
reviewer: native-agent
---

# content-creation-reward feature design 审查报告

## 1. Scope And Inputs

- Design: `.codestable/features/2026-07-09-content-creation-reward/content-creation-reward-design.md`
- Checklist: 同目录 `content-creation-reward-checklist.yaml`（4 step / 16 check，ruby yaml 校验通过）
- Intent / brainstorm: none
- Roadmap: none
- Related docs: requirements 目录空；compound 仅 `2026-07-08-jackson-hash-roundtrip-map-record.md`（无关）
- Code facts checked: `WalletService.java` / `WalletRegistrationGrantService.java` / `WalletLedgerReason.java` / `WalletBusinessType.java` / `PublishAttemptService.java` / `PublishManagerImpl.java` / `KnowPostController.java` / `PublishAcceptedResponse.java` / `PublishStatusResponse.java` / `CommentWriteConsumer.java` / `CommentServiceImpl.java` / `CommentSubmitResponse.java` / `SecurityConfig.java` / 前端 `CreatePage.tsx` / `CommentSection.tsx`

### Independent Review

- Status: completed
- Detection: native-agent（无 `mcp__paseo__create_agent`，用宿主原生 general-purpose Agent）
- Provider / agent: general-purpose subagent（同类模型，降级——残余风险见 §6）
- Raw output: 3 blocking + 4 important + 2 nit + 3 suggestion + 2 learning + 4 praise + 4 residual-risk，主 agent 已逐条本地事实核验
- Merge policy: 逐条核验通过，全部合并；B1/B2/B3/I1/I2/I3/I4 均有代码事实支撑
- Gate effect: blocking 未清零，design 不可进 implementation，回 `cs-feat-design` 修订

## 2. Design Summary

- Goal: 发帖/发评论成功后向创作者 wallet 发放可配置积分（发帖 10 / 评论 2），复用 `WalletService.grant` 链路，独立事务不阻塞主流程
- Key contracts: `ContentRewardService`（rewardPostCreation/rewardCommentCreation，REQUIRES_NEW + try-catch）；businessRef=`content-reward:post:{id}` / `content-reward:comment:{id}` 复用 grant 内置幂等；config 接口供前端拉金额
- Steps: 4 step（服务骨架 → 发帖挂载 → 评论挂载 + config 接口 → 前端）
- Checks: 16 check，来源基本可追溯到 design
- Baseline / validation: mvn test wallet/comment/knowpost + npm lint + 浏览器实测

## 3. Findings

### blocking

- [ ] **FDR-001** `{design §2.1/§2.2 + checklist step2}` **发帖 rewardAmount 回传链路是虚构的——completePublish 在异步线程执行且返回 void**
  - Evidence: `KnowPostController.publish:106-111` 返回 `PublishAcceptedResponse(只有 publishAttemptId)`，HTTP 202；`PublishManagerImpl.runPublish:131-134` 在 `publishExecutor` 异步线程（:128）调 `completePublish`，`completePublish` 返回 `void`；前端靠轮询 `GET /{id}/publish/status` → `getPublishStatus` → `PublishStatusResponse`。design §2.2 序列图画的"`completePublish` → rewardAmount → `PublishStatusResponse` → 前端"在代码里没有发生点。
  - Impact: step2 给 `PublishStatusResponse` 加 rewardAmount 的 deliverable 无法实现（completePublish 不构造它）；AC3 发帖前端显示 "+10 积分" 拿不到金额；step4 exit_signal 必失败。整个发帖奖励前端提示功能不可实现。
  - Expected fix scope: 发帖奖励金额也走 config 接口（与 A2-b 一致），前端 `CreatePage` 发布成功后按 config.postAmount 显示，**不从 PublishStatusResponse 取**。design §2.1 删"PublishStatusResponse 加 rewardAmount"、§2.2 重画发帖序列图（异步 completePublish→void + 轮询 getPublishStatus 分开）、checklist step2 deliverables 去掉 `PublishStatusResponse.java`、step2 check 2.1 改为"reward 挂在 completePublish markSucceeded 后"。

- [ ] **FDR-002** `{design §2.3 + checklist step3.4}` **GET /api/v1/content-reward/config 会被 SecurityConfig 默认拦截成 401——design 说"公开无鉴权"是错的**
  - Evidence: `SecurityConfig.java:30-41` permitAll 白名单只列 actuator/feed/detail/auth/*，`:42 .anyRequest().authenticated()`。新接口不在白名单，未带 token 直接 401。
  - Impact: 若实现者照 design"公开无鉴权"不改 SecurityConfig，未登录请求 401；step3 exit_signal "curl config 接口返回 {...}" 不带 token curl 必失败；design 契约与实现不符。
  - Expected fix scope: 二选一明确——(a) 真公开：SecurityConfig permitAll 加 `/api/v1/content-reward/config`，step3 deliverables 加 `SecurityConfig.java`；(b) 需登录（推荐，金额配置轻敏感，前端都登录态）：design 删"公开无鉴权"，step3.4 改"需登录态访问"，curl 验证带 token。倾向 (b)。

- [ ] **FDR-003** `{design §2.2 错误语义 + checklist step1 check 1.2}` **评论侧 REQUIRES_NEW 的 catch 位置未明确——子事务异常即使被外层 catch，外层事务仍可能被标记 rollback-only**
  - Evidence: `CommentWriteConsumer.onMessage:55-58` @Transactional，`handle:60-83` 同事务内。design §2.2 说"grant 抛其他异常 → catch + warn 返回 0"，但**没规定 catch 写在 ContentRewardService 内还是 caller 内**。若 catch 写在 caller（handle 内），REQUIRES_NEW 子事务回滚抛异常被外层 @Transactional catch——Spring 经典陷阱：子事务回滚会通过 TransactionSynchronizationManager 影响外层，外层可能 `UnexpectedRollbackException` 或 rollback-only 无法提交。发帖侧因 completePublish 调用方 runPublish 无外层事务，REQUIRES_NEW 能隔离（design Top1 风险在发帖侧被夸大）；评论侧才是真正风险点。
  - Impact: AC8 评论侧（主事务不回滚）在 reward 抛非 BusinessException 时可能失败：评论 insert + updateStatus 被外层回滚，评论消失。design 没单测覆盖此场景。
  - Expected fix scope: design §2.2 错误语义明确"catch 必须在 ContentRewardService 方法体内（rewardPostCreation/rewardCommentCreation 内 try-catch 吞所有 Exception 返回 0，异常不冒泡到 caller）"；checklist step1 check 1.2 补"catch 在 service 方法体内，caller 不感知异常"。

### important

- [ ] **FDR-004** `{design AC7/AC8}` **AC8"主事务不回滚"在 MockMvc standalone 单测下不可证伪**
  - Evidence: attention.md:26 明确后端测试是 MockMvc standalone（mock 依赖、不起真实容器），`@Transactional`/`REQUIRES_NEW` 注解在 mock 环境不生效。单测只能验证"service catch 异常返回 0"，无法验证"外层事务没被 rollback-only"。
  - Impact: AC8 核心承诺在单测层不可达，"测试绿但生产才暴露"风险。
  - Expected fix scope: AC8 证据改两层——单测层验证 service catch 返回 0（已有）；集成层降级为手工 curl + DB 查询（mock wallet 抛异常后发评论，评论成功 + ledger 无记录），step3 exit_signal 补手工验证。或显式声明项目无集成测试基建，AC8 集成验证降级手工。

- [ ] **FDR-005** `{checklist step3.2}` **评论幂等两条路径未区分**
  - Evidence: `@RetryableTopic` 重试时，"reward 成功后外层异常"靠 businessRef 幂等兜底；"reward 前异常"靠"第一次没发"。两条路径验证用例 design 没区分。
  - Impact: 实现者可能只测前者，漏后者。
  - Expected fix scope: step3.2 拆 3.2a（reward 成功后重试 → businessRef 幂等只发一次）+ 3.2b（reward 前异常重试 → 第一次未发，重试后正常发一次），均断言 ledger count=1。

- [ ] **FDR-006** `{design §0 A1}` **BOUNTY 是 dead enum（全仓库无使用），A1 决策缺这条证据**
  - Evidence: `grep -rn BOUNTY src/main/java` 只在 `WalletBusinessType.java:9` 枚举定义处 1 行命中，无任何使用。
  - Impact: 实现者可能误以为 BOUNTY 有现成语义可复用。design 倾向新增 CONTENT 是对的，但论证不完整。
  - Expected fix scope: design §0 A1 补"BOUNTY 全仓库无使用（dead enum），不复用；新增 CONTENT 独立承载内容创作语义"。

- [ ] **FDR-007** `{design §2.1 + §2.2 A2-b}` **CommentSubmitResponse 加 rewardAmount 与 A2-b（config 接口）矛盾**
  - Evidence: design §2.1 说"CommentSubmitResponse 新增 Long rewardAmount"，但 A2 已确认选 b（config 接口），评论提交时奖励还没发（异步），加 rewardAmount 会误导（填 config 值还是 0？）。checklist step3 deliverables 也没列 CommentSubmitResponse，说明倾向不加。
  - Impact: design §2.1 与 step3 deliverables 不符，实现者看到 §2.1 会加字段。
  - Expected fix scope: design §2.1 删"CommentSubmitResponse 新增 rewardAmount"或标注"仅在选 A2-a 时适用（已弃）"。

### nit

- [ ] **FDR-008** `{checklist step1 check 1.1}` "tsc 无关"放在后端 step 里是噪声，删掉。
- [ ] **FDR-009** `{design §2.3}` 挂载点补一句"stuck 恢复路径（failPublish）不发奖励（正确，发布未成功）"作反向核对，避免实现者误挂。

### suggestion

- [ ] **FDR-010** `ContentRewardService` 签名建议加 `long amount` 参数透传（caller 从 Properties 读后传入），service 只做"grant + 幂等 + 异常隔离"，amount 决策上移。Deep module 原则。v1 不强求，owner 认同可记 compound。
- [ ] **FDR-011** `recoverStuckPublishingAttempts`（PublishAttemptService:185+）走 failPublish 不发奖励，与 design 一致，design §2.3 补反向核对即可（同 FDR-009）。
- [ ] **FDR-012** 评论 reward creatorId 来自 event.creatorId（submit 时登录用户），链路正确无越权风险。

### learning

- **L1** Spring `@Transactional(REQUIRES_NEW)` rollback-only 陷阱：子事务回滚抛异常即使被外层 catch，外层仍可能无法提交（UnexpectedRollbackException）。正确做法：catch 写在子事务方法体内，异常不进入外层事务边界。**建议沉淀 compound**（`spring-requires-new-rollback-only-trap.md`），未来"主事务内调独立子事务 + 容错"场景参考。
- **L2** BOUNTY 是 dead enum，全仓库无使用，勿复用。

### praise

- **P1** 挂载点"删了它 feature 是否消失"判据清晰可操作（design §2.3）。
- **P2** businessRef 复用 grant 内置幂等、不外层查重的决策正确（避免查+插竞态）。
- **P3** Acceptance Coverage Matrix 把 AC 映射到 step + 证据 + 命令，可追溯性强（除 AC8 证据不可达，见 FDR-004）。
- **P4** step 按 paradigm 切片（骨架→发帖→评论→前端）原子性合理。

## 4. User Review Focus

- **用户需重点拍板**：
  - FDR-001 修复方案确认：发帖金额走 config 接口（不从 PublishStatusResponse 取）——与已拍板的 A2-b 一致，需确认。
  - FDR-002 config 接口公开还是需登录——倾向需登录（b）。
  - FDR-010 amount 透传签名是否本次采用（倾向 v1 不采用，service 内读）。
- **implement 需重点遵守**：
  - FDR-003 catch 必须在 ContentRewardService 方法体内，不冒泡 caller。
  - FDR-001 发帖 reward 挂 completePublish markSucceeded 后，金额走 config 接口。
  - FDR-005 评论幂等测两条路径。
- **code review / QA / acceptance 重点复核**：
  - FDR-003/FDR-004 REQUIRES_NEW 隔离在非 BusinessException 下的真实行为（手工集成验证）。
  - FDR-001 发帖前端提示金额来源正确性。

## 5. Evidence Confidence Ledger

| Check | Verdict | Evidence Class | Basis | Follow-up |
|---|---|---|---|---|
| Acceptance Coverage Matrix | warn | E+C | 10 AC + Matrix 齐全，但 AC8 证据不可达（FDR-004）、AC3 发帖金额链路虚构（FDR-001） | 修 FDR-001/004 后重评 |
| DoD Contract | pass | E | Design/Impl/Review/QA/Acceptance DoD + 命令 + 产物齐全 | none |
| Steps and checks traceability | warn | E | 4 step/16 check 可追溯，但 step2 deliverables 含虚构的 PublishStatusResponse（FDR-001）、step3.2 未拆路径（FDR-005） | 修后重评 |
| Roadmap contract compliance | n/a | E | 非 roadmap 起头 | none |
| Module interface design | pass | C | ContentRewardService deep module（caller 只传 id），但 FDR-010 建议 amount 透传 | 可选改进 |
| Validation and artifacts | warn | E+C | 命令明确，但 AC8 单测不可达（FDR-004）、既有 flaky 已说明 | 修 FDR-004 |

Summary: E=4, C=2, H=0。H-only core checks=none。核心检查无 H-only，但 3 个 warn 需修后重评。

## 6. Residual Risk

- **R1 reviewer 降级**：本轮用宿主原生 general-purpose Agent（同类模型），非 Paseo 异构审查。残余风险：同类模型可能有相同盲区。但本轮 reviewer 发现了 3 个 blocking（B1/B2/B3）且全部本地核验通过，审查质量高，降级影响可控。
- **R2 REQUIRES_NEW 真实行为**：FDR-003 指出的 rollback-only 陷阱在单测层不可验证（FDR-004），生产环境第一次 reward 抛非 BusinessException 时才暴露。implement 后必须手工集成验证（mock wallet 抛 RuntimeException 发评论，确认评论成功 + ledger 无记录）。
- **R3 无每日上限**：design §1 显式承担（v1 简单发放）。上线后若刷量，靠 enabled=false 紧急开关。运维文档应记录。
- **R4 compound 沉淀遗漏**：design §4 说无 compound 需沉淀，但 L1（REQUIRES_NEW 陷阱）+ L2（BOUNTY dead enum）值得沉淀。建议 implement 后补。

## 7. Verdict

- Status: **changes-requested**
- Next: 回 `cs-feat-design` 修订 design + checklist（重点修 FDR-001/002/003 三个 blocking + FDR-004/005/006/007 四个 important），重跑 `cs-feat-design-review`。修订涉及发帖回传链路重画、config 接口鉴权明确、catch 位置明确、AC8 证据降级、评论幂等路径拆分——这些是结构性修订，不是文案调整。修订后必须重跑独立 review。

---

# Round 2 复审（修订后）

## 复审结论

原 3 个 blocking（FDR-001/002/003）**全部清零**，修订质量高：
- FDR-001（发帖 rewardAmount 链路虚构）：§2.2 序列图重画，正确反映 completePublish 异步 void + 前端轮询 + 金额走 config；checklist step2 去掉 PublishStatusResponse。✓
- FDR-002（config 接口 401）：明确"需登录态"，不加 SecurityConfig permitAll。✓
- FDR-003（catch 位置）：§2.1/§2.2/checklist 1.2 三处一致明确"catch 在 service 方法体内不冒泡 caller"。✓
- FDR-004（AC8 证据降级）：AC8 改两层（单测 + 手工集成）。✓
- FDR-005（评论幂等拆路径）：AC5a/AC5b + checklist 3.2 拆。✓
- FDR-006（BOUNTY dead enum）：§0 A1 补证据。✓
- FDR-007（CommentSubmitResponse 矛盾）：§2.1 删。✓

## Round 2 新发现（已处理）

- **NEW-BLOCKING（§1 交付物清单矛盾）**：round 1 修订未贯穿全文，§1 交付物清单仍写"PublishStatusResponse/CommentSubmitResponse 携带 rewardAmount"。**已修**：§1 改为"不改任何 DTO"+ 补 ContentRewardController/ConfigResponse。✓
- **I-1（AC5b 语义不自洽）**：reward 挂 updateStatus 后 + catch 吞异常 → reward 失败时评论 succeeded、重试短路不补发，积分丢失。**owner 已决策选 A（接受丢失）**：v1 设计取舍，reward 失败不阻塞评论，极少数 grant 失败时丢一次积分。design AC5b + checklist 3.2b 已改为明确断言"接受丢失"。✓

## Round 2 Verdict

- Status: **passed**
- 原 blocking 全清零，新 blocking 已修，I-1 owner 已决策。
- Next: 交给用户整体 review。用户确认后 design status 改 approved，进 cs-feat-impl。

## Residual Risk（保留）

- R2 REQUIRES_NEW 真实行为：implement 后必须手工集成验证（mock wallet 抛 RuntimeException 发评论，确认评论成功 + ledger 无记录）。
- R3 无每日上限：靠 enabled=false 紧急开关。
- R4 compound 沉淀：implement 后补 L1（REQUIRES_NEW 陷阱）+ L2（BOUNTY dead enum）。
