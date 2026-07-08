---
doc_type: feature-review
feature: 2026-07-06-distributed-singleflight
status: passed
reviewer: subagent
reviewed: 2026-07-06
round: 5
---

# distributed-singleflight 代码审查报告

## 1. Scope And Inputs

- Design: `.codestable/features/2026-07-06-distributed-singleflight/distributed-singleflight-design.md`
- Checklist: `.codestable/features/2026-07-06-distributed-singleflight/distributed-singleflight-checklist.yaml`
- Evidence pack: none
- Gate results: none
- DoD results: none
- Implementation evidence: 对话中的实现汇报、QA/acceptance 文档、surefire 摘要和当前 worktree 文件。
- Diff basis: `git status --short --untracked-files=all` + `git diff` + `git diff --cached`；staged diff 为空。
- Baseline dirty files: none identified outside this feature scope.

### Independent Review

- Detection: 当前宿主可用原生 subagent；OCR 已按用户要求从本机删除，`where.exe ocr` 返回不可用；当前 worktree 无 `.codegraph`，未使用主检出 stale CodeGraph 索引作为结论依据。
- 环节 A 独立隔离 Task agent: native-agent + completed。
- 环节 B OCR CLI: not-available。
- OCR severity mapping: 未运行；OCR 不可用不阻塞本轮，因为 subagent gate 已完成。
- Merge policy: subagent 结论已逐条本地核验后合并；本地同时覆盖 design fit、行级 diff、测试证据和对抗路径。
- Gate effect: `reviewer: subagent`，满足 review gate 放行条件。

## 2. Diff Summary

- 新增：
  - `src/main/java/com/tongji/common/singleflight/**`
  - `src/main/java/com/tongji/counter/service/UserCounterRebuildAdapter.java`
  - `src/test/java/com/tongji/common/singleflight/**`
  - `src/test/java/com/tongji/counter/**`
  - `src/test/java/com/tongji/relation/api/RelationControllerSingleFlightTest.java`
  - `.codestable/features/2026-07-06-distributed-singleflight/**`
- 修改：
  - `CounterServiceImpl`、`FollowFeedServiceImpl`、`ModerationReviewExecutorImpl`、`RelationController`
  - `ModerationReportMapper` / `ModerationReportMapper.xml`
  - `application.yml`
  - moderation/feed/relation 相关测试
- 删除：none
- 未跟踪 / staged：未跟踪文件均属于本 feature 代码、测试或 CodeStable 文档；staged diff 为空。
- 风险热点：跨模块公共并发基础设施、Redis Lua/Stream、owner/follower 时序、审核副作用 CAS、真实 Redis 集成测试。

## 3. Adversarial Pass

- 假设的生产 bug：owner/follower 的 terminal event、TTL、takeover 或业务副作用边界在高并发下失真。
- 主动攻击过的反例：
  - owner 成功后 stream publish 失败是否把成功误写为失败：`DistributedSingleFlightServiceTest.terminalPublishFailureDoesNotMaskOwnerSuccess` 覆盖，未成立。
  - counter 限流/退避是否在 single-flight 外短路并把 0 写成成功 replay：当前限流/退避在 owner supplier 内抛 `RejectedExecutionException`，未成立。
  - feed cursor 分页是否被错误合并到 head key：`readAuthorHead` 对 `cursor != null` 直接 `loadAuthorFeed`，测试覆盖。
  - moderation 是否把通知/内容处置副作用也 replay：当前只包 `llmClient.review(report)`，DB CAS 后才执行副作用，未成立。
  - 真实 Redis stream 首事件是否因 `$` 语义漏掉：新增 `RedisDistributedSingleFlightIntegrationTest` 覆盖 `0-0` offset 首事件等待，未成立。
- 结果：无 blocking / important；保留 takeover 旧 owner 返回语义、单线程 heartbeat 调度、高压 retryable failure 和 Redis integration test skip 策略为 residual-risk / QA focus。

## 4. Findings

### blocking

none

### important

none

### nit

none

### suggestion

- [ ] REV-001 `src/test/java/com/tongji/common/singleflight/RedisDistributedSingleFlightIntegrationTest.java:46` 真实 Redis integration test 使用 `@EnabledIf("redisReachable")`，Redis 不可达时会 skip。
  - Evidence: 当前 QA 已记录该测试 `Tests run: 4, Failures: 0, Errors: 0, Skipped: 0`，本轮证据可信；但未来 CI/本地显式跑 `-Dtest=RedisDistributedSingleFlightIntegrationTest` 时，如果 Redis 没起，可能出现“绿灯但未测”。
  - Impact: 不影响本轮 review 放行；影响后续验证信号清晰度。
  - Expected fix scope: 后续可按项目测试策略调整 gating，例如显式 integration profile 下 Redis 不可达则 fail，普通 `mvn test` 是否允许 skip 另行约定。

### learning

- `DistributedSingleFlightService` owner 成功路径是 supplier -> serialize/store result -> finish success meta -> publish stream -> L1 cache；失败路径写 FAILED meta 后发布失败事件。stream 是唤醒优化，最终语义仍由 Redis meta/result 决定。
- `RedisSingleFlightCoordinatorRepository` 对 retryable failure 不返回 `REPLAY_FAILURE`，而是允许后续 `OWNER_TAKEOVER`，符合 “retryable failure 可接管/重试” 的设计。
- 当前 worktree 没有 `.codegraph`；CodeGraph 会退回主检出索引的情况下，不应把其结果用于本 worktree 审查结论。

### praise

- moderation 只把 `llmClient.review(report)` 放进 single-flight，DB 终态迁移、retry 调度和通知仍由 CAS 控制，副作用边界正确。
- Redis Stream 使用 Lua 一次性 `XADD + PEXPIRE`，并在 follower wait 前捕获 offset，修复了空 stream 用 `$` 可能错过首个 terminal event 的典型时序问题。
- 新增 `RedisDistributedSingleFlightIntegrationTest` 覆盖了真实 Redis 下 stream TTL、空 stream 首事件、owner/follower replay 和 stale heartbeat takeover，消除了上一轮 review/QA 的主要 residual。

## 5. Test And QA Focus

- QA 必须重点复核：
  - Redis 不可达/超时下 `distributed` 与 `hybrid` 模式的四个 stage 失败语义。
  - owner 超过 running TTL 后恢复完成：takeover 结果唯一，旧 owner 异常是否符合调用方预期。
  - retryable failure 热点：同一 key 多 follower 是否产生过多 takeover/retry。
  - moderation 并发：同一 `reportId + retryCount` 下 LLM 一次，`markReviewed/markIgnored/scheduleRetry` 仅一个 CAS 成功，CAS loser 无通知和内容副作用。
  - Redis integration test 在 Redis 不可达时不要被误判为“通过”。
- Evidence pack residual risks / gate warnings: none。
- 建议新增或加强的测试：
  - 可选增强：显式 integration profile 下 Redis 不可达应 fail 的测试策略。
  - 可选增强：owner 被 takeover 后旧 owner 恢复并尝试 finish 的真实 Redis 时序测试。
- 不能靠 review 完全确认的点：
  - Redis 客户端高延迟导致 heartbeat 单线程调度堆积时的误接管概率。
  - 上线流量下 retryable failure 热点是否形成重试风暴。

## 6. Residual Risk

- `src/main/java/com/tongji/common/singleflight/DistributedSingleFlightService.java:132` owner 运行超过 heartbeat 接管窗口后，如果 takeover owner 已成功写入结果，旧 owner 恢复后 `storeResult/finishSuccess` 会因 token 不匹配失败并向旧调用方抛错。该路径不会破坏 Redis 最终结果，但调用方体验需要 QA/灰度确认是否符合预期。
- `src/main/java/com/tongji/common/singleflight/SingleFlightHeartbeatManager.java:17` heartbeat 使用单线程调度；若 Redis heartbeat 调用被客户端超时拖住，多个 owner heartbeat 可能一起延迟，进而触发误接管。当前测试覆盖 stale heartbeat takeover，但无法证明高延迟 Redis 下不会误判。
- `CounterServiceImpl.bitCountShardsPipelined` 仍使用 `redis.keys(pattern)`，这是既有性能风险；本 feature 没扩大该风险，后续可单独走性能优化。

## 7. Verdict

- Status: passed
- Reviewer: subagent；OCR 不可用且未运行。
- Next: feature 来源通过后进入 `cs-feat-qa`；由于本轮 review 覆盖了新增真实 Redis integration test 后的 diff，QA 应重点复核本报告第 5 节的非阻塞风险。
