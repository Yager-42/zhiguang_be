---
doc_type: feature-acceptance
feature: 2026-07-06-distributed-singleflight
status: passed
accepted: 2026-07-06
round: 1
---

# distributed-singleflight 验收报告

> 阶段：阶段 3（验收闭环）
> 验收日期：2026-07-06
> 关联方案 doc：`.codestable/features/2026-07-06-distributed-singleflight/distributed-singleflight-design.md`

## 1. 接口契约核对

对照方案第 2.1 节名词层逐一核查：

**接口示例逐项核对**：

- [x] `DistributedSingleFlightService.execute(stage, requestKey, TypeReference<T>, Supplier<T>)`：代码要求 `stage`、`requestKey` 非空，调用方显式传 `TypeReference<T>`，返回 owner supplier 结果或 follower replay。实际落点：`src/main/java/com/tongji/common/singleflight/DistributedSingleFlightService.java`。
- [x] typed result replay：`SingleFlightResultCodec` 使用 Jackson JSON bytes、Base64 payload、checksum、可选 gzip 和 `TypeReference<T>` 反序列化；覆盖 `Map<String, Long>`、`List<TimelineItem>`、`ModerationLlmResult` 的业务调用。
- [x] Redis key 前缀：meta/result/stream 使用 `zg:singleflight:*`，未沿用 AI-Meeting 的 `ai:flight:*`；`owner-seq` 是长期序列 key，已在 review residual 中作为运维说明候选记录。

**名词层“现状 -> 变化”逐项核对**：

- [x] 公共服务：新增 `com.tongji.common.singleflight` 包，包含 service、coordinator、notification、heartbeat、codec、local replay cache、policy model。
- [x] `SingleFlightPolicy` / stage 配置：`SingleFlightProperties` 支持全局 enabled/mode/defaults 和 stage 级 enabled/mode 覆盖。
- [x] `SingleFlightCoordinatorRepository`：Redis Lua 协调 owner/follower/replay/failure/takeover，并用 TTL 管理 meta/result。
- [x] `SingleFlightNotificationService`：Redis Stream 发布 terminal event，follower 捕获当前 offset 后 wait，并保留 poll fallback。
- [x] `SingleFlightLocalReplayCache`：本地短 TTL replay cache 已接入 `tryReadSuccessReplay` 和 owner success。

**流程图核对**：

- [x] A/B/C/D/E/F/G/H/I/J 节点均有代码落点：业务生成 stage/requestKey；`DistributedSingleFlightService` 查 L1、`acquireOrJoin`、owner `markRunning + heartbeat`、supplier、store/finish/publish、follower wait/poll、replay success/failure。

结论：接口契约与 design 一致，没有需要先修复的偏差。

## 2. 行为与决策核对

**需求摘要逐项验证**：

- [x] 引入可复用分布式 single-flight：公共包和四个业务 stage 已落盘。
- [x] 四个业务场景纳入：`counter-sds`、`user-counter`、`feed-author-head`、`moderation-llm` 均有生产代码挂载和测试覆盖。
- [x] owner 成功时 followers 复用结果：公共测试和业务测试均覆盖 owner/follower replay。
- [x] owner 失败时按失败类型处理：retryable failure 写短失败 TTL 并允许后续重试/takeover；non-retryable failure replay 为失败语义。

**明确不做逐项核对**：

- [x] 未复制 AI-Meeting 的 interview/AI 命名、`ai:flight:*` key 或 String-only 编码。
- [x] 未改变四个业务场景的对外 API 响应结构；controller/feed/moderation 方法签名保持业务原入口。
- [x] 未替换 `KnowPostServiceImpl.getDetail` 本地 single-flight；精确扫描还发现 `KnowPostFeedServiceImpl` 有既有本地 single-flight，同样未纳入本轮。
- [x] 未新增数据库 schema；仅收紧 `scheduleRetry` mapper 入参与 SQL 条件。

**关键决策落地**：

- [x] 公共层归属：代码位于 `com.tongji.common.singleflight`。
- [x] typed replay：业务调用显式传 `TypeReference`，没有字段候选式 fallback。
- [x] 不叠加两套 owner 决策：计数、用户计数、Feed、审核挂载点使用 distributed single-flight；审核旧 Redisson lock 入口已删除。
- [x] 审核副作用 CAS：`markReviewed` / `markIgnored` 仍由 `status='pending'` 控制，`scheduleRetry` 增加 `sourceRetryCount`，通知和内容动作只在终态更新成功后执行。

**编排层“现状 -> 变化”逐项核对**：

- [x] `counter-sds`：SDS 缺失先进入 single-flight，限流/退避在 owner supplier 内转为 retryable overload，不写入伪成功 replay。
- [x] `user-counter`：缺失和采样不一致统一进入 `rebuildUserCounters`，由 `UserCounterRebuildAdapter.rebuildAndRead` 作为唯一结果来源。
- [x] `feed-author-head`：仅 `cursor == null` 的 head cache miss 进入 single-flight；cursor 分页直接查询。
- [x] `moderation-llm`：key 为 `report:{reportId}:retry:{retryCount}`，retryCount 来源于 pending report 当前值，LLM 调用被 single-flight 包裹，DB 副作用不回放。

**流程级约束核对**：

- [x] `stage` / `requestKey` 为空显式抛 `IllegalArgumentException`。
- [x] terminal stream publish best-effort，不改变 owner 成功/失败最终语义。
- [x] follower wait 捕获 offset 后再读 result/meta，避免 poll-to-wait 之间错过首条 stream event。
- [x] stage disabled/global disabled 直接执行 supplier；local mode 使用 JVM 内 local single-flight；hybrid 只在 acquire 协调失败时本地 fallback。

**挂载点反向核对（可卸载性）**：

- [x] `counter-sds`：实际落点 `CounterServiceImpl.getCounts` 缺失分支。删除该调用会回到旧的直接重建/失败返回 0 边界。
- [x] `user-counter`：实际落点 `RelationController.rebuildUserCounters`。删除该调用会回到多请求同时触发 `rebuildAllCounters` 的旧边界。
- [x] `feed-author-head`：实际落点 `FollowFeedServiceImpl.readAuthorHead` head miss 分支。删除该调用会回到并发 Cassandra 回源。
- [x] `moderation-llm`：实际落点 `ModerationReviewExecutorImpl.doReview` 中的 LLM 调用。删除该调用会回到重复请求模型。
- [x] 反向核查：精确扫描 `DistributedSingleFlightService` / stage 字符串，生产引用只落在公共包、四个业务挂载点和配置中；测试引用均为对应覆盖。
- [x] 拔除沙盘推演：移除四个业务调用和 `singleflight.*` 配置后，公共包可保留但不生效；Redis `zg:singleflight:*` request key 带 TTL。

## 3. 验收场景核对

- [x] 实体计数 SDS 并发缺失：同一实体/metrics 只执行一次事实重建，followers 返回同一 map。
  - 证据来源：`CounterServiceImplSingleFlightTest` + 公共 single-flight 测试。
- [x] 用户计数 SDS 并发缺失：同一 userId 只执行一次 adapter 重建，followers 返回 5 个指标。
  - 证据来源：`RelationControllerSingleFlightTest`、`UserCounterRebuildAdapterTest`。
- [x] 用户计数采样不一致：采样不一致分支进入同一 `rebuildUserCounters` single-flight 路径。
  - 证据来源：代码核对 + relation controller 测试回归。
- [x] Feed author head 并发 miss：head miss 进入 `feed-author-head`，Cassandra 查询由 owner 执行。
  - 证据来源：`FollowFeedServiceTest`。
- [x] Feed cursor 分页：`cursor != null` 直接 `loadAuthorFeed`，不使用 single-flight key。
  - 证据来源：`FollowFeedServiceTest`。
- [x] 审核 LLM 并发终态 review：同 report/retryCount 只调用一次 LLM，终态 CAS 成功者执行后续副作用。
  - 证据来源：`ModerationReviewExecutorTest`。
- [x] 审核 LLM 并发 retryable 结果：`scheduleRetry` 带 `sourceRetryCount` CAS，只有一个 retry 调度成功。
  - 证据来源：`ModerationReviewExecutorTest` + mapper XML 核对。
- [x] owner retryable failure：timeout/overload 被分类为 retryable failure，不写伪成功。
  - 证据来源：`DistributedSingleFlightServiceTest`。
- [x] owner non-retryable failure：validation 类失败 replay 为失败语义，不重复 supplier。
  - 证据来源：`DistributedSingleFlightServiceTest`。
- [x] owner heartbeat 过期：后续请求可 takeover 并完成结果。
  - 证据来源：`DistributedSingleFlightServiceTest`。
- [x] 回滚开关：global/stage disabled、local、hybrid 行为有公共测试覆盖；配置示例已落盘。
  - 证据来源：`DistributedSingleFlightServiceTest`、`application.yml`。

**review 报告重点复核**：

- [x] Redis key 命名、typed codec、owner/follower 错误语义、审核 `reportId + retryCount` key、pending CAS 与 retryCount CAS 均已覆盖。
- [x] residual risk 已复核：WSL Docker 依赖环境补齐后默认全量测试通过；真实 LLM endpoint 显式集成测试通过；剩余项只是不影响本轮通过的 Redis notification 专测增强建议。

**QA 报告重点复核**：

- [x] 验证证据来源：`.codestable/features/2026-07-06-distributed-singleflight/distributed-singleflight-qa.md`。
- [x] QA status 为 `passed`，failed / blocked 为 none。
- [x] QA matrix 覆盖 design 关键场景和 review focus。
- [x] residual risk 没有把核心功能未运行写成非阻塞；WSL Docker 环境下全量 Maven 已补跑通过，LLM 外部 endpoint 已显式验证通过。
- [x] Evidence pack、DoD Results、Gate Results：本 feature 非 goal/gate 包装模式，无对应产物。

## 4. 术语一致性

- `DistributedSingleFlightService`：生产代码命中公共服务和四个业务调用，命名一致。
- `SingleFlightPolicy` / `SingleFlightProperties`：配置与 policy model 命名一致。
- `SingleFlightCoordinatorRepository` / `RedisSingleFlightCoordinatorRepository`：公共协调仓储命名一致。
- `SingleFlightNotificationService` / `RedisSingleFlightNotificationService`：通知服务命名一致。
- `SingleFlightHeartbeatManager`：owner heartbeat 命名一致。
- `SingleFlightResultCodec`：typed result codec 命名一致。
- `SingleFlightLocalReplayCache`：L1 replay cache 命名一致。
- stage 名称：`counter-sds`、`user-counter`、`feed-author-head`、`moderation-llm` 在配置、代码、测试中一致。
- 防冲突：未发现 `ai:flight` 或 AI-Meeting interview 命名进入生产代码。

## 5. 领域影响盘点（提示而非代写）

- [x] 候选：分布式 single-flight 作为跨业务公共并发基础设施。
  - 建议：走 `cs-domain` 写 ADR。理由：它是难回退、跨模块、不显然且有真实权衡的结构性选择。
- [x] 候选：`stage + requestKey + TypeReference<T>` 作为公共结果回放契约。
  - 建议：走 `cs-domain` 写 CONTEXT 术语或 ADR 约束。理由：后续新挂载点必须遵守显式类型和 requestKey 稳定性。
- [x] 候选：审核场景“只复用 LLM 结果，DB 副作用由 CAS 单执行”。
  - 建议：走 `cs-domain` 写流程级约束。理由：这是稳定的并发副作用边界。
- [x] 候选：`KnowPostServiceImpl.getDetail` 本地 single-flight 暂不替换。
  - 建议：作为后续 feature / ADR 背景记录。理由：涉及详情缓存和权限语义，不应被误认为可直接替换。

accept 阶段不直接写 `requirements/CONTEXT.md` 或 ADR，本节只登记和提示。

## 6. requirement delta / clarification 回写

- Frontmatter：`requirement: null`。
- 判定：本 feature 是内部并发基础设施迁移和既有能力稳定性增强；不新增对外 API、用户故事或产品 pitch，不在 accept 阶段自由 backfill requirement。
- 结论：无 requirement 文档需要机械回写；不生成 approval-report。

## 7. roadmap 回写

- Design frontmatter 无 `roadmap` / `roadmap_item` 字段。
- `.codestable/roadmap` 当前只有 `.gitkeep`，无可回写 items.yaml。
- 结论：非 roadmap 起头，跳过 roadmap 回写。

## 8. attention.md 候选盘点

- 候选 1：当前 Maven 固定路径为 `C:\Users\Administrator\.m2\wrapper\dists\apache-maven-3.9.11-bin\6mqf5t809d9geo83kj4ttckcbc\apache-maven-3.9.11\bin\mvn.cmd`，JDK 使用 `C:\Users\Administrator\.jdks\ms-21.0.11`。
  - 建议：可用 `cs-note` 加到 `.codestable/attention.md` 的测试/编译分节。
- 候选 2：全量业务链路测试需要 WSL Docker 中的 MySQL、Redis、Cassandra、Kafka 和 Elasticsearch；当前可用路径是先确认 `docker ps` 中 `zhiguang-*` 服务 healthy/green，再用 Windows Maven 跑 `mvn test`。
  - 建议：可用 `cs-note` 加到测试/运行与本地起服务分节。
- 候选 3：执行 worktree 没有 `.codegraph` 时，CodeGraph 会退回主检出索引并提示“different git worktree”；这种结果不能用于当前 worktree 验收结论。
  - 建议：可用 `cs-note` 加到命令与脚本陷阱分节。
- 候选 4：本机 OCR CLI 已按用户要求删除，后续 review/QA 不应再调用 OCR。
  - 建议：可用 `cs-note` 加到命令与脚本陷阱或工具分节。

## 9. 遗留

- 后续优化点：
  - 单独评审 `KnowPostServiceImpl.getDetail` 本地 single-flight 替换，先澄清详情缓存权限语义。
  - 单独优化 `CounterServiceImpl.bitCountShardsPipelined` 的 `redis.keys(pattern)` 性能风险。
- 已知限制：
  - 默认全量测试已依赖 WSL Docker 中的 MySQL/Redis/Cassandra/Kafka/Elasticsearch；后续复验前需确保这些服务处于 healthy/green。
  - stage disabled 当前是直接执行 supplier，满足回滚开关，但不完全等价旧 Redisson lock 的所有细节。
- 实现阶段顺手发现：
  - `zg:singleflight:owner-seq` 是长期序列 key，不同于每个 request 的 TTL key，运维文档后续可明确说明。

## 10. 最终审计

- 验证证据来源：`.codestable/features/2026-07-06-distributed-singleflight/distributed-singleflight-qa.md`
- Evidence sources：无独立 evidence-pack / dod-results / gate-results；本轮 acceptance 现场复验命令见下。
- Inline Verification Matrix：不需要；已有 QA 报告且 status=passed。
- 本轮终审复验：2026-07-06 15:10-15:12，在隔离 worktree `E:\idk\zhiguang_be\.worktree\quarantine-distributed-singleflight` 现场重跑。
- 聚合命令：
  - `python .codestable/tools/validate-yaml.py --file .codestable/features/2026-07-06-distributed-singleflight/distributed-singleflight-checklist.yaml --yaml-only` -> exit 0。
  - `$env:JAVA_HOME='C:\Users\Administrator\.jdks\ms-21.0.11'; & 'C:\Users\Administrator\.m2\wrapper\dists\apache-maven-3.9.11-bin\6mqf5t809d9geo83kj4ttckcbc\apache-maven-3.9.11\bin\mvn.cmd' -q '-Dtest=*SingleFlight*,RedisSingleFlightNotificationServiceTest,FollowFeedServiceTest,ModerationReviewExecutorTest,RelationManagerControllerTest,RelationManagerControllerWiringTest' test` -> exit 0。
  - `$env:JAVA_HOME='C:\Users\Administrator\.jdks\ms-21.0.11'; & 'C:\Users\Administrator\.m2\wrapper\dists\apache-maven-3.9.11-bin\6mqf5t809d9geo83kj4ttckcbc\apache-maven-3.9.11\bin\mvn.cmd' -q '-Dtest=RedisDistributedSingleFlightIntegrationTest' test` -> exit 0。
  - WSL Docker 环境：MySQL、Redis、Cassandra、Kafka healthy；Elasticsearch 8.12.2 `/_cluster/health` green。
  - `$env:JAVA_HOME='C:\Users\Administrator\.jdks\ms-21.0.11'; & 'C:\Users\Administrator\.m2\wrapper\dists\apache-maven-3.9.11-bin\6mqf5t809d9geo83kj4ttckcbc\apache-maven-3.9.11\bin\mvn.cmd' -q test` -> exit 0。
  - `$env:JAVA_HOME='C:\Users\Administrator\.jdks\ms-21.0.11'; & 'C:\Users\Administrator\.m2\wrapper\dists\apache-maven-3.9.11-bin\6mqf5t809d9geo83kj4ttckcbc\apache-maven-3.9.11\bin\mvn.cmd' -q '-Dtest=ModerationLlmOpenAiCompatibleIntegrationTest' '-Dllm.integration=true' test` -> exit 0。
  - `git diff --check` -> exit 0，仅 Windows LF/CRLF warning，无 whitespace error。
  - targeted `Select-String` for `TODO|FIXME|XXX|System\.out|printStackTrace` -> no matches。
- 场景复核：re-verified 14 / trust-prior-verify 0。
- 交付物复核：
  - 代码：公共 single-flight 包、四个业务挂载点、mapper CAS 改动均存在。
  - 配置：`singleflight.*` 全局和四个 stage 示例已存在。
  - schema：无 DB schema 变更。
  - 路由：未新增/修改对外 API route 结构。
  - 文档：design、design-review、review、QA、acceptance 均落盘。
  - architecture/requirement/roadmap：accept 阶段只登记候选；无 requirement/roadmap 机械回写目标。
- 完整工作区复核：
  - `git status --short` 中 tracked/untracked 文件均属于本 feature 代码、测试或 CodeStable 文档范围。
  - staged diff 为空。
  - CodeGraph 当前 worktree 无 `.codegraph`；一次 CodeGraph 调用返回主检出索引 warning，未用于最终代码结论。最终验收以当前 worktree 文件读取、精确字符串扫描和测试命令为准。
- diff 清洁度：通过；无临时 TODO/FIXME/XXX、debug 输出、异常打印或注释掉旧锁分支。
- 知识沉淀出口：
  - `cs-domain`：建议补 ADR / CONTEXT，覆盖 distributed single-flight 结构决策和审核 CAS 副作用约束。
  - `cs-keep`：建议沉淀 WSL Docker 全量测试环境、worktree CodeGraph stale-index 陷阱。
  - `cs-note`：建议把 Maven/JDK 路径、WSL Docker 依赖服务、CodeGraph worktree 陷阱、OCR 已删除写入 attention.md。
  - `cs-doc-api`：公共 `DistributedSingleFlightService` 是可复用接口，后续可补 API 参考。
- 用户终审：2026-07-06 用户已确认验收结论。
- 结论：通过。原始契约满足，核心功能路径均有运行证据，交付物已落盘；剩余项为上线/环境级非阻塞风险和后续文档沉淀候选。
