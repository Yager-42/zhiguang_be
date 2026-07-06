---
doc_type: feature-qa
feature: 2026-07-06-distributed-singleflight
status: passed
tested: 2026-07-06
round: 2
---

# distributed-singleflight QA 报告

## 1. Scope And Inputs

- Design: `.codestable/features/2026-07-06-distributed-singleflight/distributed-singleflight-design.md`
- Checklist: `.codestable/features/2026-07-06-distributed-singleflight/distributed-singleflight-checklist.yaml`
- Review: `.codestable/features/2026-07-06-distributed-singleflight/distributed-singleflight-review.md`（round 5，status=passed，reviewer=subagent）
- Evidence pack: none
- Gate results: none
- DoD results: none
- Diff basis: `git status --short --untracked-files=all` 显示本 feature 修改 common singleflight、counter、feed、moderation、relation、application.yml、相关 mapper/test 和 feature 文档；`git diff --cached` 为空；`git diff --name-only` 已复核已跟踪文件改动摘要。
- Baseline dirty files: none identified outside this feature scope.
- Feature type: functional
- Core evidence gate: 公共分布式单飞锁 owner/follower/replay/failure/takeover/stage 开关/hybrid fallback/Redis Stream 通知；四个业务场景为计数重建、关系用户计数重建、关注流 author head cache miss、审核 LLM 结果生成。

## 2. Verification Matrix

| ID | 来源 | 核心性 | 场景 / 风险 | 证据类型 | 命令或动作 | 期望 | 结果 |
|---|---|---|---|---|---|---|---|
| QA-001 | design S1 / review focus | core-functional | 公共分布式单飞锁 owner/follower、结果回放、失败传播、owner 超时接管、stage 开关、hybrid fallback | unit | targeted Maven | `DistributedSingleFlightServiceTest` 全绿 | pass |
| QA-002 | design S1 / review residual | core-functional | Redis Stream 通知发布、TTL、从捕获 offset 等待，避免 follower 错过通知 | unit | targeted Maven | `RedisSingleFlightNotificationServiceTest` 全绿 | pass |
| QA-003 | design S1 | supporting | single-flight 结果编解码保持成功/失败语义 | unit | targeted Maven | `SingleFlightResultCodecTest` 全绿 | pass |
| QA-004 | design S2 / review focus | core-functional | Counter SDS miss 先进入分布式 single-flight；overload/backoff 不写入成功回放 | unit | targeted Maven | `CounterServiceImplSingleFlightTest` 全绿 | pass |
| QA-005 | design S2 | core-functional | 用户计数重建 adapter 可被 RelationController 复用 | unit | targeted Maven | `UserCounterRebuildAdapterTest` 全绿 | pass |
| QA-006 | design S3 | core-functional | Follow feed author head cache miss 走 single-flight，cursor path 不被错误合并 | unit | targeted Maven | `FollowFeedServiceTest` 全绿 | pass |
| QA-007 | design S4 / review focus | core-functional | Moderation LLM 结果 single-flight，retryCount CAS，通知/content side effects 受保护 | unit | targeted Maven | `ModerationReviewExecutorTest` 全绿 | pass |
| QA-008 | design S2 | core-functional | RelationController 接入用户计数重建 single-flight 且原有 manager wiring/API 测试不回退 | unit | targeted Maven | `RelationControllerSingleFlightTest`、`RelationManagerControllerTest`、`RelationManagerControllerWiringTest` 全绿 | pass |
| QA-009 | checklist | supporting | checklist YAML 结构有效 | static | `python .codestable/tools/validate-yaml.py --file ... --yaml-only` | exit 0 | pass |
| QA-010 | project DoD | supporting | WSL Docker 依赖环境下全量测试是否完整跑通 | test run | full Maven | 全量 `mvn test` exit 0 | pass |
| QA-011 | cleanliness | supporting | debug 输出、临时 TODO、异常打印、空白错误 | static | `git diff --check`; targeted `Select-String` | 无 feature 代码清洁问题 | pass |
| QA-012 | user request | supporting | OCR 已从当前机器移除，QA 不依赖 OCR | manual CLI | `where.exe ocr` | command not found | pass |
| QA-013 | design S4 / BDD chain | core-functional | 审核 LLM 外部 endpoint 真实可用，返回 OpenAI-compatible chat completions 响应 | integration | `-Dtest=ModerationLlmOpenAiCompatibleIntegrationTest -Dllm.integration=true` | exit 0 | pass |
| QA-014 | review residual | supporting | 真实 Redis 下验证 distributed single-flight notification/coordinator：XADD+PEXPIRE TTL、空 stream offset 首事件、owner/follower replay、stale heartbeat takeover | integration | `-Dtest=RedisDistributedSingleFlightIntegrationTest` | exit 0 | pass |
| QA-015 | review round 5 focus | supporting | Redis integration test 在当前环境没有被 skip，避免“绿灯但没测” | surefire | `RedisDistributedSingleFlightIntegrationTest.txt` | Skipped 0 | pass |
| QA-016 | review round 5 residual | supporting | owner 超过 running TTL 后旧 owner 恢复、Redis 高延迟 heartbeat 堆积、retryable failure 热点 | risk assessment | review + existing tests | 非核心 chaos/灰度风险，核心路径已有运行证据 | residual |

## 3. Command Results

- `python .codestable/tools/validate-yaml.py --file .codestable/features/2026-07-06-distributed-singleflight/distributed-singleflight-checklist.yaml --yaml-only` -> exit 0；1 个 YAML 文件验证通过。
- WSL Docker 依赖环境：MySQL、Redis、Cassandra、Kafka 容器 healthy；Elasticsearch 8.12.2 `/_cluster/health` 为 green。
- 环境准备：清理 MySQL promotion 集成测试固定 ID 段 `7700000000..7700999999` 后确认剩余为 0，避免上轮测试残留造成重复主键误判。
- `$env:JAVA_HOME='C:\Users\Administrator\.jdks\ms-21.0.11'; & 'C:\Users\Administrator\.m2\wrapper\dists\apache-maven-3.9.11-bin\6mqf5t809d9geo83kj4ttckcbc\apache-maven-3.9.11\bin\mvn.cmd' -q '-Dtest=RedisDistributedSingleFlightIntegrationTest' test` -> exit 0；真实 Redis 专测通过，`Tests run: 4, Failures: 0, Errors: 0, Skipped: 0`。
- `$env:JAVA_HOME='C:\Users\Administrator\.jdks\ms-21.0.11'; & 'C:\Users\Administrator\.m2\wrapper\dists\apache-maven-3.9.11-bin\6mqf5t809d9geo83kj4ttckcbc\apache-maven-3.9.11\bin\mvn.cmd' -q '-Dtest=*SingleFlight*,RedisSingleFlightNotificationServiceTest,FollowFeedServiceTest,ModerationReviewExecutorTest,RelationManagerControllerTest,RelationManagerControllerWiringTest' test` -> exit 0；feature 定向测试通过。
- `$env:JAVA_HOME='C:\Users\Administrator\.jdks\ms-21.0.11'; & 'C:\Users\Administrator\.m2\wrapper\dists\apache-maven-3.9.11-bin\6mqf5t809d9geo83kj4ttckcbc\apache-maven-3.9.11\bin\mvn.cmd' -q test` -> exit 0；默认全量测试通过，MySQL/Redis/ES/Cassandra 相关集成测试均连通。
- `$env:JAVA_HOME='C:\Users\Administrator\.jdks\ms-21.0.11'; & 'C:\Users\Administrator\.m2\wrapper\dists\apache-maven-3.9.11-bin\6mqf5t809d9geo83kj4ttckcbc\apache-maven-3.9.11\bin\mvn.cmd' -q '-Dtest=ModerationLlmOpenAiCompatibleIntegrationTest' '-Dllm.integration=true' test` -> exit 0；真实 LLM endpoint 返回 OpenAI-compatible response。
- `git diff --check` -> exit 0；仅输出 Windows LF/CRLF warning，无 whitespace error。
- targeted `Select-String` for `TODO|FIXME|XXX|System\.out|printStackTrace` -> no matches。
- `where.exe ocr` -> exit 1；当前 PATH 未发现 OCR CLI。

## 4. Scenario Results

- [x] QA-001 公共 single-flight 行为：pass
  - Evidence: `DistributedSingleFlightServiceTest`，Tests run: 14, Failures: 0, Errors: 0。
- [x] QA-002 Redis Stream 通知行为：pass
  - Evidence: `RedisSingleFlightNotificationServiceTest`，Tests run: 4, Failures: 0, Errors: 0。
- [x] QA-003 编解码行为：pass
  - Evidence: `SingleFlightResultCodecTest`，Tests run: 2, Failures: 0, Errors: 0。
- [x] QA-004 Counter SDS miss / overload：pass
  - Evidence: `CounterServiceImplSingleFlightTest`，Tests run: 2, Failures: 0, Errors: 0。
- [x] QA-005 用户计数重建 adapter：pass
  - Evidence: `UserCounterRebuildAdapterTest`，Tests run: 1, Failures: 0, Errors: 0。
- [x] QA-006 Follow feed author head cache miss：pass
  - Evidence: `FollowFeedServiceTest`，Tests run: 7, Failures: 0, Errors: 0。
- [x] QA-007 Moderation LLM single-flight：pass
  - Evidence: `ModerationReviewExecutorTest`，Tests run: 9, Failures: 0, Errors: 0。
- [x] QA-008 RelationController 用户计数重建：pass
  - Evidence: `RelationControllerSingleFlightTest`，Tests run: 1, Failures: 0, Errors: 0；`RelationManagerControllerTest`，Tests run: 4, Failures: 0, Errors: 0；`RelationManagerControllerWiringTest`，Tests run: 1, Failures: 0, Errors: 0。
- [x] QA-010 全量业务链路测试：pass
  - Evidence: WSL Docker 中 MySQL/Redis/Cassandra/Kafka/Elasticsearch 可用后，默认全量 `mvn test` exit 0；覆盖评论、认证、计数、发布、审核、通知、推广、推荐、关系、搜索、存储、钱包等现有自动化链路。
- [x] QA-013 审核 LLM 外部 endpoint：pass
  - Evidence: `ModerationLlmOpenAiCompatibleIntegrationTest` with `-Dllm.integration=true`，Tests run: 1, Failures: 0, Errors: 0, Skipped: 0。
- [x] QA-014 真实 Redis distributed single-flight 通知与接管：pass
  - Evidence: `RedisDistributedSingleFlightIntegrationTest`，Tests run: 4, Failures: 0, Errors: 0, Skipped: 0；覆盖 XADD+PEXPIRE TTL、从 `0-0` offset 等待首个 terminal event、follower 通过真实 Redis Stream/result store 回放 owner 结果、stale owner heartbeat takeover。
- [x] QA-015 Redis integration test 未被 skip：pass
  - Evidence: `RedisDistributedSingleFlightIntegrationTest` surefire 摘要为 `Skipped: 0`；本轮真实 Redis 服务处于 healthy 状态。
- [x] QA-016 review round 5 residual：residual
  - Evidence: 现有测试覆盖 stale heartbeat takeover、hybrid acquire failure fallback、retryable failure 分类和 moderation CAS；未新增 chaos/压测测试来证明 Redis 高延迟 heartbeat 堆积、旧 owner 恢复调用方体验或 retryable failure 热点不会出现线上抖动。该项不承载本轮 design 核心路径缺口。

## 5. Findings

### failed

none

### blocked

none

### residual-risk

- owner 超过 running TTL 后如果 takeover owner 已成功写入结果，旧 owner 恢复后 `storeResult/finishSuccess` 会因 token 不匹配失败并向旧调用方抛错；不会破坏 Redis 最终结果，但调用方体验建议灰度观察。
- `SingleFlightHeartbeatManager` 使用单线程调度；Redis 客户端高延迟时可能导致 heartbeat 延迟并触发误接管。当前功能测试覆盖 stale takeover 语义，但没有做 Redis 高延迟 chaos/压测。
- retryable failure 在 Redis 协调层允许立即 takeover；热点失败场景仍建议上线后观察重试量和 single-flight key 热度。
- `RedisDistributedSingleFlightIntegrationTest` 当前普通 Maven 运行下 Redis 不可达会 skip；本轮已验证 Skipped 0，后续 CI 是否在显式 integration profile 下 fail-fast 需要另行约定。

## 6. Cleanliness

- Debug output: pass
- Temporary TODO/FIXME/XXX: pass
- Commented-out code: pass by diff/review scope; targeted search did not find temporary markers or debug prints.
- Unused imports / dead code from this feature: pass by targeted Maven compile/test.
- Out-of-scope files: pass; dirty files align with distributed single-flight feature scope and feature docs.

## 7. Verdict

- Status: passed
- Next: `cs-feat-accept`
