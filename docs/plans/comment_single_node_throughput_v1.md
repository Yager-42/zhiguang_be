# Plan: comment-single-node-throughput-v1 单机评论读写吞吐

| 字段 | 值 |
|------|-----|
| **plan_id** | `comment-single-node-throughput-v1` |
| **plan_version** | `0.1.1` |
| **status** | **completed - implementation and verification complete** |
| **created** | 2026-08-06 |
| **updated** | 2026-08-07 |
| **feature** | [`comment_single_node_throughput_v1.md`](../features/comment_single_node_throughput_v1.md) frozen v0.1.1 |
| **code baseline** | Git `3468b38`；`com.tongji.comment`、`com.tongji.counter`、`com.tongji.cache`、`com.tongji.storage.text`、moderation、wallet、`application.yml`、`db/schema.sql`、`docker-compose.yml`、`loadtest/` |
| **deployment boundary** | 1 Spring Boot App + 1 MySQL + 1 Redis + 1 Kafka broker + 1 Cassandra；不得用扩容获得验收结果 |
| **authority** | 本 plan 只解释和执行 frozen feature；若冲突，以 feature 为准，停止实现并更新契约/plan，禁止实现者自行扩大边界 |

## 0. Purpose and operating rule

本 plan 是 `comment-single-node-throughput-v1` 的逐步实施说明书。目标不是一次性重写评论模块，而是在保持 HTTP DTO、202/status、幂等、删除占位、MySQL/Cassandra/Redis/Kafka 事实模型不变的前提下，依次完成：

```text
读：Caffeine -> Redis index/item -> MySQL metadata + Cassandra body
    -> 单次 Counter 页面 pipeline 叠加 counts/liked

写：pending + comment_outbox
    -> 批量异步 Kafka dispatcher
    -> Cassandra-first 幂等正文写
    -> 短 MySQL finalizer
    -> COMMENT_CREATED
    -> Counter / Reward / Feedback 独立 consumer group
```

执行者必须遵守以下规则：

1. 严格按任务依赖顺序实施；每个任务通过自己的测试与收尾标准后，才能进入依赖它的任务。
2. 每个任务动手前先完成“必须理清”的代码阅读。结构问题按仓库 `AGENTS.md` 使用 CodeGraph；符号未知先 `codegraph_symbol_search`，改公共符号前用 `codegraph_get_callers`/`codegraph_analyze_impact`，编辑文件前用 `codegraph_get_edit_context`。CodeGraph 意外为空时只 reindex 一次。
3. 文本定位、配置键、SQL、日志和测试名使用 `rg` 与直接读文件；不得凭 plan 中的类名假设代码仍未变化。
4. 任务收尾时记录：改动文件、测试命令、结果、尚存风险、配置最终值。不能只以“编译通过”收尾。
5. 所有缓存、executor、Kafka in-flight 都必须有界；“始终排队”只允许 MySQL outbox/Kafka lag 持久化积压，不允许 JVM 无界排队。
6. 禁止引入第二套缓存框架、第二套点赞事实、Canal/Debezium、OFFSET 评论分页、评论专用本地 singleflight、应用层 429/503、额外 App/Redis/Kafka/Cassandra/MySQL 节点；singleflight 只复用现有 `DistributedSingleFlightService`。
7. 禁止把 `CommentItemResponse` 或任何包含当前用户 `liked` 的对象写入共享 Caffeine/Redis。
8. 禁止复制 Feed 热路径的 `leftPushAll` 顺序、逐条 Counter 查询、每请求 `log.info`、缺片段时返回残页等缺陷。

## 1. Frozen interpretation locks

以下解释用于消除 feature 示例与当前生产代码之间的歧义，不改变 frozen 边界。

| ID | 实施解释 | 不允许的漂移 |
|----|----------|--------------|
| I1 | “保持现有接口”以当前 `CommentController` 为兼容基线：status 路径保持 `/api/v1/comments/{pendingCommentId}/status`；feature §2.1 的路径文字不得被用来重命名现有接口 | 不改 URL、HTTP 状态、请求/响应字段 |
| I2 | `CommentBasePage.nextCursor` 是内部字段；实现前必须对照现有 `CommentPageResponse(nextCursorCreateTime,nextCursorCommentId)` 确定无损映射，并用测试锁定 | 不把外部 cursor 改成单字符串，不改游标排序 |
| I3 | 回复接口当前没有用户身份参数，继续按匿名用户处理：读取 counts，跳过 liked bitmap | 不为本 feature 改 Controller/API 签名 |
| I4 | 只有 `cursorCreateTime == null && cursorCommentId == null` 的第一页进入 L1/L2 index；任一 cursor 存在都走非页面缓存路径 | 不缓存所有 cursor 组合 |
| I5 | 创建、删除、审核与共享计数在缓存路径允许最多 30 秒陈旧；这是上限，不是主动 sleep 时间 | 不承诺近强一致，不允许 TTL 超过上限后仍无失效兜底 |
| I6 | 删除继续保持 Cassandra-first：正文删除失败时不 soft-delete MySQL；成功后再进入短 MySQL 删除事务 | 不顺手改成 MySQL-first 或恢复被删正文 |
| I7 | Kafka key 对所有 comment outbox 事件均为 `commentId`/`aggregateId` | 不使用 `postId` 造成热门帖子分区热点 |
| I8 | `COMMENT_CREATED` 的三个副作用使用三个独立 consumer group；不能在一个 consumer 中串行调用 Counter、Reward、Feedback | 不把副作用放回 `CommentWriteConsumer` |
| I9 | Hikari `maximumPoolSize=10` 初始保持；先消除 listener 长事务，再根据 acquire/pending 与 MySQL 余量调参 | 不先扩大连接池掩盖事务问题 |
| I10 | 性能完成标准只接受同机同数据同负载的 5 轮对比，不接受固定绝对 QPS 或单轮最好值 | 不以 10k/20k/100k QPS 宣称完成 |

### 1.1 Frozen decision traceability

每条 grill 决策必须能从 feature 追踪到任务和最终 gate；实现者不得用“任务中没写”绕过 locked 边界。

| Feature decision | Plan implementation | Final gate |
|------------------|---------------------|------------|
| G1 相同条件下相对提升，不设绝对 QPS | G0.3、E3 | V5 P1-P8，五轮 raw + median |
| G2 完整读写链路，不缩成只读 feature | A1-D5 | V1-V4 全部完成后才能进入性能 closeout |
| G3 最多 30 秒宽松最终一致 | A3、A6 | V3 invalidation/TTL gate |
| G4 backlog 下始终尝试 pending/outbox，不主动拒绝 | B2、E1 | V4 Kafka outage + V5 sustained overload |
| G5 统一 `comment_outbox` 承载四种事件 | B1-B5、D4-D5 | schema/dispatcher/clean-cutover + V4 |
| G6 Cassandra-first + 短 MySQL finalizer | C1-C3 | F3/F4 + V4 fault gate |
| G7 单 App/单节点中间件，不水平扩容 | G0.3、E2/E3 | 每轮 live/performance topology audit |
| G8 复用现有 distributed singleflight | A4 | distributed owner/follower/result TTL/takeover + V3 |
| G9 不做旧 outbox 数据迁移 | B1、D5 | 旧 dispatcher 排空后 clean cutover；非空阻塞 |

代码已裁决的 D1-D18 同样是 frozen 实施约束：

| Feature decision | Task/gate |
|------------------|-----------|
| D1 Caffeine | A1/A3 |
| D2 Caffeine -> Redis fragments -> MySQL/Cassandra | A3-A5/V3 |
| D3 只缓存无 cursor 第一页 | I4/A5/C3 |
| D4 Redis index/item 分离 | A3 |
| D5 共享 cache 不含用户 liked | A1/A3/A5/F8 |
| D6 现有 `DistributedSingleFlightService` | A4/C5/C6 |
| D7 保持 Counter bitmap/SDS | A2/D1 |
| D8 counts/liked 单页面 pipeline | A2 |
| D9 Cassandra 普通幂等 upsert，无 LWT | C1 |
| D10 Kafka key=commentId | B4 |
| D11 单 dispatcher + 有界 async send | B3 |
| D12 不使用 Canal | §0 rule 6、V1 static audit |
| D13 保持 MySQL metadata + Cassandra body | A4/C1 |
| D14 保持 HTTP DTO 与 202/status | G0.2/B2 |
| D15 不复制 Feed 逐条 Counter/info 日志 | A2/A3/A5/V1.4 |
| D16 published 小批清理 | B5 |
| D17 executor/Caffeine/Kafka in-flight 有界 | A1/A4/B3/E2 |
| D18 删除保留 Cassandra-first | I6/D4/V4 |

## 2. Current and target context

### 2.1 Current method chains

#### Read

```text
CommentController.comments/replies
-> CommentServiceImpl.pageComments/pageReplies
-> CommentMapper.listTopLevelByPost/listRepliesByRoot (MySQL, limit + 1)
-> CommentServiceImpl.page
-> TextStorageService.getCommentTexts
-> CassandraTextStorageService -> CommentTextRepository.findAllById (Cassandra)
-> CounterService.getCountsBatch (Redis pipeline: SDS GET)
-> CounterService.isLikedBatch (Redis pipeline: bitmap GETBIT)
-> CommentServiceImpl.item
-> CommentPageResponse
```

#### Submit and materialization

```text
CommentController.submit
-> @Transactional CommentServiceImpl.submit
-> PendingCommentMapper.insert
-> CommentWriteOutboxMapper.insert(comment_write_outbox)
-> 202 pending

@Scheduled CommentWriteOutboxPublisher.publishReady
-> releaseExpiredClaims -> claimReady -> findClaimed
-> for each CommentWriteProducer.publish
-> kafkaTemplate.send(comment-write, commentId, payload).get()
-> per-row markPublished/markRetry

@Transactional CommentWriteConsumer.onMessage
-> PendingCommentMapper.findByCreatorAndClientRequestId
-> CassandraTextStorageService.saveCommentText (findById + version + 1 + save)
-> CommentMapper.insert
-> CounterService.initializeCounts
-> PendingCommentMapper.updateStatus(succeeded)
-> CounterEventProducer.publish
-> CommentFeedbackProducer.publish (swallows failures)
-> ContentRewardService.rewardCommentCreation(REQUIRES_NEW, swallows grant failure)
```

#### Delete and moderation

```text
owner delete:
CommentController.delete
-> CommentServiceImpl.delete
-> CommentMapper.findById
-> TextStorageService.deleteCommentText (Cassandra-first)
-> CommentMapper.softDelete (MySQL)
-> controller best-effort CommentFeedbackProducer

moderation:
ModerationContentActionServiceImpl.applyApprovedAction @Transactional
-> CommentMapper.softDeleteForModeration
```

### 2.2 Target method chains

#### Cached head read

```text
CommentController
-> CommentServiceImpl.pageComments/pageReplies
-> CommentPageCacheService.getHead
   -> L1 commentPageCache
   -> else L2 Comment index + multiGet item fragments
   -> else DistributedSingleFlightService(comment-page-head)
      -> CommentMapper cursor query (MySQL)
      -> commentReadExecutor:
         Cassandra getCommentTexts || Counter getPageStateBatch
      -> write item fragments -> index metadata -> Caffeine
-> CounterService.getPageStateBatch (request-user overlay; anonymous omits GETBIT)
-> map shared CommentBaseItem + request state
-> existing CommentPageResponse
```

#### Unified outbox write

```text
submit @Transactional
-> pending_comments
-> comment_outbox(COMMENT_WRITE_REQUESTED, payload contains stable eventId)
-> commit -> 202

CommentOutboxDispatcher
-> batch claim ready
-> event_type -> comment-write/comment-events
-> kafkaTemplate.send asynchronously, key=aggregateId
-> wait/co-ordinate bounded batch futures
-> batch markPublished(success subset) + batch markRetry(failure subset)

CommentWriteConsumer (no JDBC transaction around listener)
-> read/validate pending
-> Cassandra blind idempotent upsert
-> CommentMaterializationService.finalizeMaterialization @Transactional
   -> idempotent comments insert
   -> pending pending->succeeded
   -> comment_outbox(COMMENT_CREATED) in same MySQL transaction
-> listener returns/record ACK

comment-events / independent groups
-> CommentCounterConsumer -> reliable stable CounterEvent -> counter-events
-> CommentRewardConsumer -> ContentRewardService (businessRef idempotent)
-> CommentFeedbackConsumer -> reliable CommentFeedbackEvent -> comment-feedback
-> Comment event cache listener/consumer -> Caffeine + Redis index invalidation
```

### 2.3 Storage and middleware ownership

| Component | Facts/keys/topics in scope | V1 action |
|-----------|----------------------------|-----------|
| MySQL | `comments`, `pending_comments`, old `comment_write_outbox`, new `comment_outbox` | 保持评论元数据/状态；统一 outbox；短事务；旧表排空后 clean cutover，不做数据迁移 |
| Cassandra | `comment_text_by_comment_id` | 保持正文事实；评论创建改固定版本普通 upsert；删除仍 Cassandra-first |
| Redis | `cnt:v1:*`, `bm:like:*`, new `comment:idx:*`, `comment:item:*`, existing `zg:singleflight:*` | Counter 事实不变；新增页面 index/item/empty；复用现有 distributed singleflight；请求用户态只读叠加 |
| Caffeine | new `commentPageCache` | 仅缓存 `CommentBasePage`；默认 max 5000、TTL 3 秒，可配置 |
| Kafka | `comment-write`, new `comment-events`, `counter-events`, `comment-feedback` | outbox 批量发布；三个独立副作用 group；至少一次 + 幂等 |
| Hikari | max pool 10 | listener 不再跨 Cassandra 持有连接；初始不扩池 |
| Executors | new `commentReadExecutor`, `commentOutboxExecutor` | 有界；CallerRunsPolicy；不用 common pool |

### 2.4 Target code map

实际文件名可为匹配现有边界做小幅调整，但必须先更新本表，不能临时复制出第二套实现。

```text
src/main/java/com/tongji/comment/
  cache/
    CommentBaseItem.java
    CommentBasePage.java
    CommentCacheKeys.java
    CommentPageCacheService.java
    CommentCacheInvalidationListener.java
  event/
    CommentOutboxEvent.java
    CommentCreatedEvent.java
    CommentDeletedEvent.java
    CommentModeratedEvent.java
    CommentOutboxDispatcher.java
    CommentOutboxCleaner.java
  consumer/
    CommentWriteConsumer.java
    CommentCounterConsumer.java
    CommentRewardConsumer.java
    CommentFeedbackConsumer.java
  service/impl/
    CommentServiceImpl.java
    CommentMaterializationService.java
    CommentMutationService.java          # 若用于保持 Cassandra-first + 短 MySQL 事务
  mapper/
    CommentOutboxMapper.java
  model/
    CommentOutbox.java

src/main/resources/mapper/
  CommentOutboxMapper.xml

existing shared modules touched:
  com.tongji.common.singleflight.DistributedSingleFlightService       # 直接复用，不另建实现
  com.tongji.common.singleflight.SingleFlightProperties               # 只增加 comment-page-head stage 配置
  com.tongji.counter.service.CounterService/impl.CounterServiceImpl
  com.tongji.counter.event.CounterEvent/CounterEventProducer/CounterAggregationConsumer
  com.tongji.storage.text.TextStorageService/CassandraTextStorageService
  com.tongji.cache.config.CacheConfig/CacheProperties
  com.tongji.config.ThreadPoolConfig
  com.tongji.comment.config.CommentKafkaConfig/CommentOutboxSchemaInitializer
  com.tongji.moderation.service.impl.ModerationContentActionServiceImpl
  application.yml, db/schema.sql, docker-compose.yml, loadtest/
```

## 3. Delivery phases

| Phase | Tasks | Depends on | Completion gate |
|-------|-------|------------|-----------------|
| G0 | G0.1-G0.3 contract/baseline/evidence | none | baseline artifacts and API/schema snapshots exist before behavior changes |
| A | A1-A6 cached read path | G0 | C1-C10 and read unit/integration gates pass |
| B | B1-B5 unified outbox/dispatcher | G0 | F1/F2/F10/F11 and schema/dispatcher gates pass；不含旧数据迁移 |
| C | C1-C3 Cassandra-first short materialization | B | F3/F4/F12 and transaction-boundary gates pass |
| D | D1-D5 side effects/mutations/cutover | A+B+C | F5-F9, cache invalidation, no old production implementation |
| E | E1-E3 observability/config/load-test closure | A-D | all required meters, scripts, audits and report templates exist |
| V | V1-V5 offline/live/performance | A-E | functional + relative performance acceptance complete |

读链路 A 与 outbox B 在 G0 后可由不同提交并行开发，但同一工作树中不得同时重写 `CommentServiceImpl`；最终串行合并并重新跑全量测试。C 必须在 B 的事件/schema 稳定后开始，D 必须在 C 的 finalizer 稳定后开始。

## 4. G0 - baseline and drift guards

### G0.1 Freeze code/context map

**任务**

在任何生产代码变化前，固化当前 Git SHA、工作树状态、Java/Maven/Docker/k6 版本、相关方法调用者和测试基线。

**动手前必须理清**

- 用 CodeGraph 获取 `CommentServiceImpl.pageComments/pageReplies/submit/delete`、`CommentWriteConsumer.onMessage`、`CounterService.getCountsBatch/isLikedBatch`、`CommentMapper.softDeleteForModeration`、`DistributedSingleFlightService.execute` 的 callers/impact。
- 确认当前分支是否有用户未提交改动；不得覆盖或回滚。
- 确认 Docker 可用性和当前是否已有同名容器/端口占用。

**实现/记录路径**

1. 在实施记录中保存 `git rev-parse HEAD`、`git status --short`、`java -version`、`mvn -version`、`docker compose version`、`k6 version`。
2. 保存 CodeGraph 结果中的文件 URI 和关键 callers；若工具不可用，记录为环境限制，不伪造结果，并用 `rg` 建立临时文本清单。
3. 运行当前全量 offline 测试并记录 passed/failed/skipped；现有依赖外部服务的 `@EnabledIf` 测试允许 skip，但必须列出。

**测试**

```bash
mvn test
```

**收尾标准**

- 基线 SHA、环境版本、全量测试结果、CodeGraph 结构证据可追溯。
- 没有生产代码改动。
- 任何红色基线测试已被单列，不能在后续被误报为本 feature 回归。

### G0.2 Freeze API, schema and event compatibility

**任务**

把 feature 要求保持的对外接口、DTO、游标 SQL、删除占位和现有下游 event consumer 字段写成可执行 contract tests。

**动手前必须理清**

- 阅读 `CommentController`、全部 comment DTO、`CommentTask2ContractTest` 和 Controller tests。
- 查找全部 `CommentWriteEvent`、`CommentFeedbackEvent`、`CounterEvent` 构造/反序列化点。
- 核对 notification/recommendation 对 `comment-feedback` 字段与 ack 行为的假设。

**实现路径**

1. 扩展或新建 contract test，锁定当前 URL、202/204、status 查询、DTO 字段和游标参数。
2. 锁定 `CommentMapper.xml` 仍为 `(create_time DESC, comment_id DESC)` 且列表包含 deleted 行。
3. 为新增 eventId 采用向后兼容 JSON 演进：旧 consumer 未使用的新增字段可接受；不得删除/重命名既有字段。

**测试**

```bash
mvn -Dtest='com.tongji.comment.*ContractTest,com.tongji.comment.api.CommentControllerTest' test
```

**收尾标准**

- I1-I4、F1、F7 有自动化 drift guard。
- plan/feature 与实际 status URL 的解释已体现在测试中，未改 API。

### G0.3 Capture comparable performance baseline

**任务**

在候选实现前采集 pure-read、80/20 comment mixed、outbox drain/materialization 三组基线；补齐当前 loadtest 中 60/40 `comment.js` 无法直接代表 frozen 80/20 的缺口。

**动手前必须理清**

- 阅读 `loadtest/scripts/common.js` 的 token 池、arrival-rate、数据 ID 范围和阈值。
- 阅读 `loadtest/run.sh`、`collect_metrics.sh` 的输出目录和 SUT 指标采集方式。
- 确认 MySQL/Cassandra 评论种子一致；当前 `verify_seed.sql` 没有评论/outbox 校验，需要补充。

**实现路径**

1. 新增或参数化专用场景：100% 无 cursor 第一页 pure-read、80% read/20% submit mixed、固定提交批次后的 outbox drain。
2. 固定：token 池、post 分布、热门/随机比例、limit=20、arrival rate、时长、预热时间、seed checksum。
3. 每场景至少 5 轮；保存 k6 raw JSON/summary、MySQL/Redis/Kafka/Cassandra 指标、outbox/pending 快照。
4. outbox drain 同时记录 `published/s`、`succeeded/s`、ready backlog 斜率和 accepted-to-succeeded 延迟。
5. 结果落到独立 baseline 目录；候选结果不得覆盖它。

**涉及链路**

```text
k6 -> HTTP -> App -> MySQL/Cassandra/Redis/Kafka
collectors -> MySQL information_schema + Redis INFO + Kafka consumer-groups + app actuator
```

**测试**

- 先用极低到达率 smoke 运行每个新场景，验证请求名、比例、token、输出文件和清理脚本。
- `verify_seed.sql` 必须对 orphan comment、missing Cassandra text、pending/outbox 状态分布给出可核对结果。

**收尾标准**

- 三场景各有 5 轮原始值和中位数。
- 基线明确只有一个 App/MySQL/Redis/Kafka broker/Cassandra。
- 未通过校验的 seed 或 dropped iterations 未被当作有效基线。

## 5. Phase A - cached read path

### A1. Add cache DTOs, keys, properties and bounded executor

**任务**

建立共享基础页类型、唯一 key builder、Caffeine Bean 与专用有界读 executor；此任务不改 `CommentServiceImpl` 行为。

**动手前必须理清**

- 对照 `CommentItemResponse`、`CommentPageResponse`、`Comment` 确认所有字段、deleted 占位和 cursor 映射。
- 对照 `CacheProperties`/`CacheConfig` 的绑定层级与 bean 命名。
- 对照 `ThreadPoolConfig`/`ThreadPoolConfigTest` 的返回类型、shutdown 和 rejection policy。
- 明确 `replyCount` 的 Counter metric 名是 `comment`，不是另造 `reply`。

**实现路径**

1. 新增 frozen DTO `CommentBaseItem`、`CommentBasePage`；字段不含 `liked` 或其他 userId。
2. 新增 `CommentCacheKeys`，只由它生成：
   - `comment:page:post:{postId}:head:{limit}` / root 等价 L1 key；
   - `comment:idx:*:ids|cursor|hasMore|empty`；
   - `comment:item:{commentId}`；
   - 若采用 scope reverse-index，键也集中在此类。
3. `CacheProperties` 增加 comment page 配置；默认 `maxSize=5000`、`ttlSeconds=3`，最终 TTL 只能在 1-3 秒调优。
4. `CacheConfig` 增加 `@Bean("commentPageCache") Cache<String, CommentBasePage>`。
5. `ThreadPoolConfig` 增加 `commentReadExecutor`：core 8、max 16、queue 200、CallerRunsPolicy；参数可配置，不使用 common pool。
6. `application.yml` 增加所有配置键与环境变量默认值。

**涉及组件**

Caffeine、JVM executor；不访问 MySQL/Redis/Kafka/Cassandra。

**测试**

- key builder 对 post/root/limit 生成精确且互不冲突的 key。
- 反射/序列化测试证明 `CommentBaseItem/Page` 不含 `liked`/`userId`。
- Cache Bean max/TTL 绑定测试。
- executor core/max/queue/rejection/shutdown 测试。

**收尾标准**

- C4、D17 有自动化 gate。
- 未出现第二套 cache properties/config 框架。
- 还没有任何请求读写新 cache，便于独立审查。

### A2. Add one-pipeline Counter page API

**任务**

新增 `getPageStateBatch(entityType, entityIds, userId, metrics)`，在同一次 Redis pipeline 中按页面读取所有 SDS counts 与当前用户 liked bitmap。

**动手前必须理清**

- 阅读 `CounterServiceImpl.getCountsBatch` 和 `isLikedBatch` 的 pipeline 返回顺序、缺 key 零值和 bitmap 分片算法。
- 使用 `BitmapShard.chunkOf/bitOf` 与 `CounterKeys.bitmapKey`；不能假设 bitmap 是未分片的 `bm:like:{id}`。
- 查所有 `CounterService` 实现/测试 mock，确保加接口后编译影响完整。
- 明确 metrics=`like,comment` 时 SDS offset 来自 `CounterSchema.NAME_TO_IDX`。

**实现路径与串联**

```text
CounterService.getPageStateBatch("comment", ids, userId, ["like","comment"])
-> build N SDS GET commands
-> if userId != null/positive, append N GETBIT commands using same pipeline
-> decode first N SDS results
-> decode optional next N bitmap results
-> ordered Map<commentId, CommentPageCounterState>
```

1. 定义内部返回类型 `CommentPageCounterState`，至少能表达 requested metrics 与 `liked`。
2. 空 IDs 直接返回空，禁止发 Redis 命令。
3. 匿名用户不追加 GETBIT；counts 仍在同一 pipeline 读取。
4. 缺失/长度错误 SDS 按当前 batch 零值语义；缺 bitmap 为 false。
5. 保留旧 `getCountsBatch/isLikedBatch` 给其他调用者，不做无关迁移。

**测试**

- 20 IDs + 登录用户只调用一次 `executePipelined`，命令顺序与解码位置一致。
- 匿名只发 20 SDS GET、不发 GETBIT。
- 缺 key、坏 SDS、unsupported metric、重复/空 IDs 的语义明确。
- 两个不同用户同一 IDs 得到不同 liked，但 counts 相同。

**收尾标准**

- D7/D8 生效；评论页面不再需要两次 Counter pipeline。
- 没有新增 liked Set/local cache，也没有改变 Counter Redis key schema。

### A3. Implement L1/L2 comment page cache store

**任务**

实现 `CommentPageCacheService` 的 L1/L2 read/write，不接 L3；完整片段命中才返回 `CommentBasePage`。

**动手前必须理清**

- 阅读 Feed fragment cache 仅理解现有 ObjectMapper/Redis API 用法，并列出不可复制项：`leftPushAll`、逐条 Counter、info 日志、异常吞掉后留下半页。
- 确认 `StringRedisTemplate.opsForList().rightPushAll` 的入参顺序和空集合行为。
- 确认 index 三个 companion key 与 empty sentinel 的 TTL/失效方式。
- 解决多 limit head key 的失效定位：允许 reverse-index 或有界 key 枚举；禁止 `KEYS`/全库 `SCAN` 进入写热路径。

**实现路径**

1. L1 `getIfPresent(baseKey)`；只返回基础页，不做用户态。
2. L2：先检查 empty sentinel；读取 ordered IDs、cursor、hasMore；`multiGet(comment:item:*)`。
3. 任意 item null、反序列化失败、ID 不一致、index companion 缺失时整页 miss；不得跳过坏 item。
4. 写缓存严格顺序：
   1. 序列化并写完所有 `comment:item`，TTL 随机 300-600 秒；
   2. 用保持顺序的写法原子替换/重建 IDs，再写 cursor/hasMore，TTL 同一随机 20-30 秒；
   3. 最后写 Caffeine。
5. 空页只写 5-10 秒 empty sentinel；不得把空 List 当成永久 L1 真值。
6. item 可带 count snapshot，但此值只能是共享基础快照，返回前必须被 A5 的 Counter 页面结果覆盖。
7. Redis 异常按 miss/fallback 处理并计指标，不能返回残页；热路径不打逐请求 info。

**涉及 Redis key**

`comment:idx:post/root:*`、`comment:item:*`；没有 MySQL/Cassandra/Kafka。

**测试**

- IDs 顺序 round-trip 与输入完全相同，专门防 `leftPushAll` 反序。
- 任一 fragment missing/corrupt -> null/miss，F9/C8。
- empty sentinel 防穿透且 TTL 在 5-10 秒。
- index/item TTL 分别在 20-30/300-600 秒；companion keys 不留无 TTL 数据。
- 写 item 失败时 index 不可见；index 写失败时不写 L1。
- 缓存 JSON 不包含 liked/userId。

**收尾标准**

- C2/C4/C8/C9/C10 的 cache-store 部分通过。
- 失败路径没有半页、永久 key、用户态污染或全库扫描。

### A4. Reuse distributed singleflight and implement concurrent L3 loader

**任务**

直接复用已有 `DistributedSingleFlightService`；同一 index key 的并发 miss 只允许一个 distributed owner 执行 MySQL/Cassandra 基础回源，MySQL 得到 IDs 后 Cassandra 与 Counter 页面读取并发执行。禁止新增评论专用本地 singleflight。

**动手前必须理清**

- 完整阅读 `DistributedSingleFlightService.execute`、`SingleFlightProperties`、`RedisSingleFlightCoordinatorRepository`、`RedisSingleFlightNotificationService`、`SingleFlightLocalReplayCache` 和已有 Redis integration tests。
- 明确 distributed mode 的 owner/follower、result serialization/replay、heartbeat/takeover、failure replay 和 Redis acquire failure 语义；评论模块不得另写 fallback。
- `DistributedSingleFlightService` 内部已有的 local replay 和异常分支属于公共组件现状，本 feature 只调用其公开 `execute` 接口，不修改公共 singleflight 语义；禁止在评论包再包一层 local fallback。
- 默认 singleflight result TTL 是 10 分钟、local replay TTL 是 30 秒，不能直接继承；评论 stage 必须把它们限制在 30 秒/3 秒内。
- 重点解决“共享基础页不能携带 owner liked”与“冷回源 Cassandra/Counter 必须并发”的组合；实现说明必须画出 owner 和 follower 各自的 Counter 调用，证明不会共享用户态。
- 明确 CallerRunsPolicy 时任务可能在 HTTP 线程执行，测试不能假设固定线程名。
- deleted rows 不应向 Cassandra 请求正文。

**实现路径**

1. `CommentPageCacheService` 注入现有 `DistributedSingleFlightService`；不得声明 `ConcurrentHashMap`、锁对象或第二个 singleflight service。
2. 调用固定接口：stage=`comment-page-head`、requestKey=Redis index base key、`TypeReference<CommentBasePage>`、supplier=L2 double-check + L3 loader；cursor 页不进入 singleflight。
3. distributed owner 在 supplier 内先重查 L2，仍 miss 才执行 MySQL `limit+1` 查询、裁剪并保留原顺序。
4. IDs 可用后，在 `commentReadExecutor` 并发提交 Cassandra body 与本请求 Counter page state；不得串行 `join` 后才提交第二项。
5. 构造/序列化到 distributed result 的 `CommentBasePage` 只含 metadata/body/shared count snapshot，不含 liked；每个 HTTP 请求在 singleflight 外用自己的 userId 覆盖 counts/liked。
6. owner L3 成功后按 A3 顺序写 L2/L1，再让 `DistributedSingleFlightService` 存 result；失败不得写 comment cache，singleflight failure/heartbeat/takeover 沿现有 service 收敛。
7. `application.yml` 至少增加以下 stage 覆盖；不要继承默认的 10 分钟 result TTL/30 秒 local replay TTL：

```yaml
singleflight:
  stages:
    comment-page-head:
      enabled: true
      mode: distributed
      result-ttl-millis: 30000
      l1-cache-ttl-millis: 3000
```

`running-ttl-millis`、`failed-result-ttl-millis`、`follower-max-wait-millis`、`takeover-detect-millis` 和 `heartbeat-interval-millis` 是否覆盖，必须根据 cold L3 实测上界与现有 service 约束一起裁定：一次正常 L3 不应被误 takeover，失败/running 状态又不得无限阻塞；最终值记录到 implementation note 和测试中。

**测试**

- 50/100 并发同 key：MySQL 和 Cassandra 各一次，所有调用完成。
- user A owner + user B follower：基础回源一次，liked 各自正确。
- Cassandra 与 Counter 用 latch 证明重叠执行，不只比较脆弱耗时。
- owner exception、executor saturated、cache write exception 后 failure state 按 TTL/重试语义收敛，下一次可重新加载。
- real Redis 覆盖 owner/follower result replay、stale owner heartbeat takeover、result/meta/stream TTL；不得永久 RUNNING。
- 两个不同 post/root key 可并行；评论模块没有本地 map/全局锁。
- 配置测试证明 stage mode 不被全局 mode 覆盖为 local，result/local replay TTL 分别不超过 30 秒/3 秒。

**收尾标准**

- C5/C6、D6、D17 通过。
- implementation note 清楚解释 distributed owner/follower 的 Counter 调用次数与 result replay；不得以用户态污染换取“少一次调用”。
- production 只调用现有 `DistributedSingleFlightService`，没有第二套评论 singleflight。

### A5. Wire cached/uncached reads and request overlay

**任务**

把 A1-A4 接入 `CommentServiceImpl.pageComments/pageReplies`，并让所有 L1/L2/L3/cursor 返回前统一使用 A2 覆盖 counts/liked。

**动手前必须理清**

- 阅读现有 `page/item` 的 int clamp、`[deleted]`、null body、next cursor 规则。
- 确认 cursor 两个参数只允许同时存在或同时不存在；若当前未校验，不在本 feature 擅自改变 API，测试保持当前语义。
- 明确 `replyCount` 最终取 Counter metric `comment` 还是 metadata fallback；feature 要求 Counter 覆盖。
- 对照 A4 implementation note，保证冷回源并发与每用户 overlay 都成立。

**实现路径与分支**

```text
no cursor:
  L1 -> else L2 -> else singleflight L3/cache fill
  -> one request-scoped page-state overlay
  -> CommentPageResponse

cursor present:
  MySQL limit+1
  -> Cassandra + page-state concurrently on commentReadExecutor
  -> no Caffeine/index write
  -> CommentPageResponse
```

1. `pageComments` key scope=post；`pageReplies` key scope=root 且 userId absent。
2. L1/L2 命中禁止调用 `CommentMapper`/`TextStorageService`。
3. 每次返回按 IDs 批量覆盖 likeCount/replyCount；登录用户叠加 liked，匿名 false。
4. deleted item 固定 `[deleted]`，保留 root/parent/replyCount；不因 Cassandra fragment 缺失而丢 deleted 行。
5. clamp 和外部 DTO/cursor 与原代码一致。
6. Counter 故障降级必须沿用当前零/false 语义并有指标，不能把带用户态对象写回缓存。

**测试**

- C1：预置 L1，verify MySQL/Cassandra zero interactions；Counter overlay 仍执行。
- C2：预置完整 L2，verify MySQL/Cassandra zero interactions。
- C3：cursor 页不读写 Caffeine/L2 index。
- F7/F8：deleted placeholder；A/B liked 隔离。
- counts/liked 单 pipeline；匿名 replies 无 GETBIT。
- next cursor/hasMore/limit 1、20、100 和空页回归。

**收尾标准**

- C1-C4、F7-F9 通过。
- 旧顺序式 `page()` 热路径被替换或只作为 cursor helper 保留，不再执行两次 Counter pipeline。

### A6. Implement after-commit invalidation

**任务**

为创建/回复/删除/审核建立统一 cache invalidation API 与 after-commit listener；点赞/取消点赞不失效 index/item。

**动手前必须理清**

- 找到创建 finalizer、owner delete、moderation 的真实事务边界；事件必须在事务成功后才触发本地失效。
- 确认一个 comment 的 postId/rootId/parentId 可从哪个事件/metadata 得到。
- 解决同 scope 不同 limit key 的定位；不得用 Redis `KEYS`。
- 区分 `comment:item` 删除与 index 删除：创建可选预热 item；删除/审核必须删 item。

**实现路径**

1. 定义本地 mutation event，携带 event type/commentId/postId/rootId/parentId。
2. 在事务内 publish Spring event，`@TransactionalEventListener(phase=AFTER_COMMIT)` 调用 invalidation service；若明确无事务，使用显式成功后调用。
3. created：删 post head 全部 limit 的 L1/L2 index；reply 再删 root head；item 可预热但不是 gate。
4. deleted/moderated：先删 `comment:item:{id}`，再删 post/root head L1/L2 index。
5. Kafka `comment-events` listener 作为同一 App 的异步补充，调用相同幂等 invalidation API。
6. Redis 失效失败只 warn+metric，依赖 index 20-30 秒 TTL；Caffeine 尽力立即失效。现有 singleflight 没有按 requestKey invalidate API，因此 `comment-page-head` result/local replay 也必须靠 A4 的 30 秒/3 秒 TTL 上限收敛，禁止直接扫描/删除 `zg:singleflight:*`。
7. like/unlike 保持 Counter bitmap/SDS 路径，不删页面 index/item。

**测试**

- 事务 rollback 不失效；commit 后失效。
- top-level create 只清 post；reply create 清 post+root。
- delete/moderate 清 item+post+root。
- Redis delete failure 不回滚已提交业务事实，且 comment index/distributed result 最大 TTL 都不超过 30 秒、singleflight local replay 不超过 3 秒。
- like/unlike 不调用 invalidation。

**收尾标准**

- C7 和 G3 有单元/配置 gate。
- 所有 head limit 可被定位失效，无全库扫描，无事务提交前误删。

## 6. Phase B - unified comment outbox and batched dispatcher

### B1. Add event contracts and `comment_outbox` schema without legacy migration

**任务**

用 frozen DDL 建立领域统一 outbox并定义四种事件 payload。此任务明确不开发、不执行 `comment_write_outbox -> comment_outbox` 数据回填迁移。

**动手前必须理清**

- 阅读 `db/schema.sql` 与 `CommentOutboxSchemaInitializer`；仓库没有 Flyway，不得同时引入另一套迁移框架。
- 阅读 `DefaultIdService`：所有 Snowflake namespace 共用 generator；新事件仍必须显式调用 `IdNamespace.OUTBOX_EVENT`。
- 理清旧 dispatcher 的 ready/publishing state 查询，供 D5 在 cutover 前确认旧表已排空；这不是数据迁移。
- 明确 MySQL JSON、unsigned BIGINT、DATETIME 精度和 MyBatis list 参数写法。

**实现路径**

1. `db/schema.sql` 和 initializer 同步创建 feature §4.1 的 `comment_outbox`，列名/state/retry/索引不得继续沿用旧模型名。
2. 定义统一 model/mapper；event type 只允许：
   `COMMENT_WRITE_REQUESTED`、`COMMENT_CREATED`、`COMMENT_DELETED`、`COMMENT_MODERATED`。
3. 每个 payload 含同一稳定 `eventId`，以及各 consumer/invalidation 必需的 comment/post/root/parent/creator/body/occurredAt 字段；不依赖查询易变业务状态补全关键路由字段。
4. initializer 只负责新表 schema readiness；不得读取、转换、回填或兼容消费旧表数据。
5. 不增加 dual-write、old-table fallback、migration runner、migration mapper 或迁移状态列。
6. 旧表排空与删除只按 D5 clean cutover runbook 执行。

**数据库链路**

```text
new submit/finalizer/mutation transaction
-> comment_outbox
-> uk_comment_event(event_type, aggregate_id)

old comment_write_outbox
-> old dispatcher drains before cutover
-> no row is copied into comment_outbox
```

**测试**

- DDL contract 精确断言主键、unique、ready index、JSON、claimed_until、published_at。
- event JSON round-trip 与旧 `CommentWriteEvent` 兼容字段。
- real MySQL greenfield/startup schema test；重复启动 initializer 幂等。
- static contract 证明 production 不存在 old-to-new backfill SQL、migration runner 或 dual-write。
- D5 preflight test：旧 ready/publishing count 非 0 时 cutover 判失败，等于 0 才允许继续。

**收尾标准**

- G5/G9/D10 生效；schema.sql 与 initializer 无 drift。
- 没有旧数据迁移代码；旧表尚由旧 dispatcher 使用，最终排空/删除由 D5 完成。

### B2. Cut submit over to pending + unified outbox transaction

**任务**

让 `submit` 在一个短 MySQL 事务中插入 `pending_comments` 与 `COMMENT_WRITE_REQUESTED`，继续返回 202。

**动手前必须理清**

- 阅读 `submit` 的 parent/root 校验、重复 clientRequestId fast path 和 `DuplicateKeyException` race recovery。
- 明确 payload 序列化失败应在事务提交前失败；不得留下只有 pending 的行。
- 查 `CommentSubmitResponse`/Controller tests，保持 pendingCommentId 当前就是 commentId 的行为。

**实现路径**

1. 继续先检查 `(creatorId,clientRequestId)`，重复请求返回已有 pending 状态。
2. 生成 commentId 与独立 outbox eventId。
3. 在同一个 `@Transactional` 方法中 `pendingCommentMapper.insert` 后 `commentOutboxMapper.insert(WRITE_REQUESTED)`。
4. outbox payload 含 eventId/commentId/post/root/parent/creator/clientRequestId/body/occurredAt；Kafka key 后续为 commentId。
5. outbox insert/serialization/DB 失败必须回滚 pending；禁止同步调用 Kafka/Cassandra/Redis/Counter/Wallet。
6. 不根据 backlog 增加 429/503 或预检查。

**测试**

- F1/F2：202 DTO 与重复提交不产生第二 pending/comment/outbox。
- verify submit 事务内只调用 MySQL mapper/IdService/ObjectMapper。
- outbox insert failure -> transaction integration test 证明 pending 不存在。
- backlog 很大时仍尝试 insert，不走拒绝分支。

**收尾标准**

- G4/D14；submit 路径没有下游网络调用。
- production submit 不再写旧 `comment_write_outbox`。

### B3. Implement batch mapper operations and async dispatcher

**任务**

把逐条 `send().get()`/逐条 UPDATE 改成批量 claim、异步 send、成功/失败子集批量更新。

**动手前必须理清**

- 查当前 Spring Kafka 版本 `KafkaTemplate.send` 的 future 类型和 callback API。
- 查 Spring scheduler 默认线程模型；即使默认单线程，也保留显式 non-overlap gate。
- 明确 MyBatis `<foreach>` 对空 list 的行为；所有 batch update 空集合必须 no-op，不能生成 `IN ()`。
- 明确 claim lease 超时与最大 Kafka future 等待时间，避免成功 ACK 后 lease 被另一轮重领。

**实现路径与状态机**

```text
READY(0), next_attempt_at <= now
-> batch claim token + claimed_until -> CLAIMED(1)
-> findClaimed ordered by event_id
-> route + kafkaTemplate.send for every row without per-row get
-> bounded allOf/coordinator
   success IDs -> one/few batch mark PUBLISHED(2)
   failed IDs  -> batch mark READY + retry_count + next_attempt_at + last_error
```

1. mapper 提供 release expired、claim ready(default 500)、find claimed、batch mark published、batch mark retry、count/oldest queries。
2. dispatcher 用 `AtomicBoolean` 或单线程 scheduler 保证一轮调度不重入。
3. `commentOutboxExecutor` 只协调 futures；有界 queue + CallerRunsPolicy；不得承载事实队列。
4. 最大同时 in-flight batch 配置 2-4（默认 2）；到上限不继续 claim。
5. 同批先启动全部 send，再等待聚合结果；禁止循环内 `.get()`。
6. 仅 future 成功的 eventId 进入 published 子集；部分成功不整批回滚。
7. 失败指数退避最大 30 秒；错误文本截断到列长 500。
8. dispatcher `finally` 必须释放 running/in-flight 计数；进程崩溃由 claimed_until 回收。

**测试**

- 500 rows 在任何 future 完成前已调用 500 次 send，证明不是串行 ACK。
- future 未完成时没有 markPublished，F10。
- 3 success/2 failure -> 两个精确 batch 子集，F11。
- callback 异常、serialization error、timeout、interrupted、executor rejection 均释放 gate并可重试。
- 两次 scheduler 并发调用只有一轮 claim；in-flight 不超过配置。
- mapper SQL 空 list、500 IDs、claim token 条件、expired claim real MySQL 测试。

**收尾标准**

- D11、F10/F11；production 无逐事件 `.get()` 和逐事件 UPDATE。
- 发送成功前绝不 published，失败事件仍保留持久事实。

### B4. Configure topic routing and Kafka producer batching

**任务**

让统一 dispatcher 按 event type 路由两个 topic，并配置 Kafka producer batching/idempotence。

**动手前必须理清**

- 阅读 `CommentKafkaConfig` 的 topic bean、partitions、listener container ack mode。
- 查 application 中全局 producer 配置对其他 producer 的影响；若全局设置会改变不相关链路，采用最小兼容配置并补回归。
- 明确 `COMMENT_DELETED/MODERATED` 都发 `comment-events`，key 仍 commentId。

**实现路径**

| event_type | topic | key |
|------------|-------|-----|
| `COMMENT_WRITE_REQUESTED` | `${comment.kafka.write-topic:comment-write}` | aggregateId/commentId |
| `COMMENT_CREATED` | `${comment.kafka.event-topic:comment-events}` | aggregateId/commentId |
| `COMMENT_DELETED` | `comment-events` | aggregateId/commentId |
| `COMMENT_MODERATED` | `comment-events` | aggregateId/commentId |

1. 为 `comment-events` 建单 broker replicas=1 的 topic bean；partitions 可配置。
2. producer 初始：`acks=all`、`enable.idempotence=true`、`linger.ms=5`、`batch.size=32768`、`compression.type=lz4`。
3. 未知 event type fail closed：不发默认 topic，不 published，进入 retry/告警。
4. Kafka payload 的 `eventId` 必须等于 outbox row event_id；不得发送时重新生成。

**测试**

- 四类型 route/topic/key/payload contract。
- unknown/null type 失败且 row 可重试。
- application context topic/config binding 测试。
- 检查 key 绝不为 postId。

**收尾标准**

- G5/D10；producer config 可由运行环境覆盖但默认值符合 feature。

### B5. Add outbox retention and operational metrics

**任务**

published 事件保留 24 小时后每批最多清理 1000 行，cleaner 默认每 24 小时运行一次，并暴露 outbox 核心指标。

**动手前必须理清**

- 确认 cleaner 与 dispatcher scheduler 不共享会阻塞发送的单线程 executor。
- EXPLAIN 已证明 ready index 无法支持 published_at 清理；使用 `(state, published_at, event_id)` 联合索引避免大表扫描和 filesort。
- 评估 metric 查询频率，禁止每次 Prometheus scrape 发多个大表 COUNT。

**实现路径**

1. mapper `deletePublishedBefore(cutoff, limit=1000)`，SQL 有确定顺序/limit，小事务循环每轮最多一次或有界次数。
2. default retention=24h，clean interval 可配置；不删 ready/claimed。
3. 暴露 ready/claimed/published/retry 数、oldest ready age、dispatch batch duration、batch size、Kafka send failures、published rate。
4. gauge 用低频采样快照或已有批次计数更新，避免高频 COUNT 热表。

**测试**

- cutoff 边界、1001+ rows 只删 1000、ready/claimed 不删。
- cleaner 异常不停止 dispatcher。
- metric 名/tag 无 eventId/postId 等高基数。

**收尾标准**

- D16；published 表不会无限增长，清理不形成大事务。

## 7. Phase C - Cassandra-first short materialization

### C1. Replace comment read-before-write with deterministic Cassandra upsert

**任务**

评论创建正文改为普通幂等 blind upsert；帖子正文保存逻辑不变。

**动手前必须理清**

- 阅读 `CassandraTextStorageService.saveCommentText` 与 `savePostText`，只改 comment 路径。
- 确认 Spring Data Cassandra `save` 对固定 primary key 是普通 upsert，不触发 LWT。
- 明确 event 的 occurredAt 如何映射 `updated_at`，确保重复消息不不断改变版本/时间。

**实现路径**

```text
saveCommentTextIdempotent(commentId, body, occurredAt)
-> CommentText(commentId, body, fixed version/deterministic version, deterministic updatedAt)
-> CommentTextRepository.save
```

1. 删除 comment `findById -> version+1`；固定/确定性 version。
2. 不用 `IF NOT EXISTS`、`@Version`、compare-and-set 或 LWT。
3. 同 commentId 重放相同 payload，body/version/updatedAt 结果一致。
4. Cassandra 异常继续包装/抛出 `TextWriteException`。

**测试**

- unit verify comment save 不调用 `findById`；post save 仍按原行为。
- Cassandra live round-trip：同 event 写两次，只有同一主键且字段稳定。
- Cassandra failure 抛出，不吞异常。

**收尾标准**

- D9；评论创建没有 Cassandra 读前写或 LWT。

### C2. Add proxied short MySQL finalizer

**任务**

新增独立 Spring Bean `CommentMaterializationService`，用一个短 MySQL 事务原子完成 metadata、pending succeeded、`COMMENT_CREATED` outbox。

**动手前必须理清**

- 用 CodeGraph 查 `CommentMapper.insert`、pending update 的所有调用者。
- 阅读 `comments` 的两个 unique key；区分 comment_id 重放与 `(creator,clientRequestId)` 冲突。
- 选择不会因捕获 `DuplicateKeyException` 留下 rollback-only 的幂等 SQL，例如 insert-ignore/no-op upsert 后回读校验；不能只 catch 后假定数据相同。
- 确认 finalizer 从另一个 Bean 调用，Spring 代理真实生效，禁止 self-invocation。

**实现路径与事务顺序**

```text
@Transactional finalizeMaterialization(event)
-> idempotent insert comments; duplicate -> read and verify canonical fields
-> conditional pending pending->succeeded; already succeeded allowed only if same comment
-> insert COMMENT_CREATED outbox with new stable OUTBOX eventId
   unique(event_type, aggregate_id) makes replay no-op
-> publish local mutation event inside tx
-> COMMIT
-> AFTER_COMMIT cache invalidation
```

1. Cassandra 不得注入/调用 finalizer。
2. `COMMENT_CREATED` payload 包含后续三个副作用和 cache invalidation 所需字段。
3. duplicate metadata 恢复路径仍补 pending succeeded 与缺失 created outbox；已存在 created event 不产生第二条。
4. pending 不存在、commentId 不匹配或 canonical metadata 冲突必须失败重试/告警，不可覆盖。
5. accepted-to-succeeded latency 以 pending.create_time 到提交时记录。

**测试**

- 反射 + Spring context 证明 public method 有事务代理，consumer 本身无事务。
- mapper failure 在三步任一点 -> real MySQL 事务全回滚。
- duplicate comment + pending 仍 pending -> 收敛 succeeded 且恰好一个 created outbox，F4/F5。
- concurrent duplicate finalizer -> 一条 comment、一条 created outbox、succeeded。
- local invalidation listener 只 after commit。

**收尾标准**

- G6、F4/F5；事务内只有 MySQL/ObjectMapper/local event publication，无 Cassandra/Redis/Kafka/Wallet。

### C3. Refactor write consumer, retry and DLT boundaries

**任务**

`CommentWriteConsumer` 只负责状态检查、Cassandra-first 写和调用 finalizer；移除 listener 整方法事务与同步副作用。

**动手前必须理清**

- 阅读 `@RetryableTopic` 默认 attempts/backoff/DLT 命名和当前测试；把关键值配置化/测试化，不能靠未记录默认值。
- 明确 RECORD ack 下方法正常返回/抛异常的提交语义。
- 检查 pending lookup 应以 commentId 还是 creator/clientRequestId 为主，并验证两者一致。

**实现路径**

```text
onMessage(raw)
-> deserialize/validate eventId + commentId
-> pending lookup
-> if canonical succeeded: return
-> if missing/mismatch/failed: defined fail/ignore according to existing semantics
-> C1 blind Cassandra upsert
-> C2.finalizeMaterialization
-> return -> Kafka ACK

onDlt
-> pending updateStatusIfCurrent(pending -> failed)
-> if update/logging fails: propagate or emit explicit error metric/log; never silent
```

1. 删除 `onMessage @Transactional`。
2. 删除 consumer 对 CounterService/Producer、FeedbackProducer、ContentRewardService 的依赖。
3. Cassandra/finalizer 任一异常必须抛到 retry topic；不得 ACK。
4. concurrency 初始 2-4（默认 4 或压测前 2），C 完成后 V5 阶梯测试 4/6/8。
5. DLT 只把仍 pending 的 canonical row 置 failed；succeeded 不倒退。

**测试**

- F3：Cassandra failure -> finalizer never、pending not succeeded、exception propagates。
- MySQL finalizer failure -> Cassandra called，exception propagates，retry 再次 upsert安全。
- succeeded replay -> no Cassandra/finalizer。
- DLT pending->failed；succeeded/mismatch 不错误改终态；DLT mapper failure 有 meter/log。
- reflection 断言 listener 不含 `@Transactional`，finalizer 含。

**收尾标准**

- F3/F4/F12；write listener 不持 JDBC transaction 访问 Cassandra，也不执行任何副作用。

## 8. Phase D - asynchronous effects, mutations and clean cutover

### D1. Add idempotent CommentCounterConsumer

**任务**

独立 group 消费 `COMMENT_CREATED`，初始化评论计数并为 post/root 发布稳定 CounterEvent；重复 created 不得重复计数。

**动手前必须理清**

- 阅读 `CounterEvent.of` 当前随机 UUID、`CounterEventProducer` fire-and-forget、`CounterAggregationConsumer` 当前未使用 eventId 去重的事实。
- 阅读 comment metric idx=3 和 top-level/reply 路由。
- 设计稳定 CounterEvent ID 派生规则并锁定，例如由 `COMMENT_CREATED.eventId + effect kind` 得到；不能每次重放生成 UUID。
- 必须证明 downstream aggregation 对同 eventId 幂等；仅“ID 相同”但仍 HINCRBY 两次不算完成。

**实现路径**

1. 新 group `comment-counter-consumer` 只接受 `COMMENT_CREATED`，其他 type 明确 ACK/skip。
2. `CounterService.initializeCounts("comment", commentId)` 保持 setNX 幂等。
3. top-level -> `knowpost/postId metric=comment +1`；reply -> `comment/rootId metric=comment +1`。
4. 构造稳定 `CounterEvent.eventId`；扩展 producer 为可观察 Kafka future，send/serialization 失败使 comment consumer 重试。
5. 在 Counter aggregation Redis Lua/等价原子边界中用 eventId 去重并写 aggregate bucket，防止重复 Kafka record 双加；dedupe TTL 必须覆盖 broker replay/retention 窗口且可配置。
6. 不改变 like/unlike/fav 的事实/事件路径。

**测试**

- top-level/reply route/idx/delta/userId。
- 同 created 消费两次 -> 相同 CounterEvent ID。
- real Redis 聚合两条同 eventId -> delta 只加一次；不同 eventId 正常累加。
- Kafka send future failure -> 不 ACK/抛出重试。
- 非 CREATED 不产生 Counter。

**收尾标准**

- F5 的 Counter 部分有端到端幂等证据；不能只 mock producer 次数。

### D2. Add CommentRewardConsumer

**任务**

独立 group 在物化事务外调用现有 `ContentRewardService.rewardCommentCreation`。

**动手前必须理清**

- 阅读 reward service 的 `REQUIRES_NEW`、内部 catch 和 `businessRef=content-reward:comment:{id}`。
- 阅读 wallet unique keys 和现有 reward tests；明确返回 0 可能代表 disabled 或失败，保持现有语义。
- 确认 consumer 不与 Counter/Feedback 共用 group。

**实现路径**

1. 新 group `comment-reward-consumer`，只处理 CREATED。
2. 调用 `rewardCommentCreation(creatorId,commentId)`；不再由 write consumer 调用。
3. 记录 success/disabled-or-zero 低基数 metric；不改变 reward service 当前失败隔离边界，除非另立契约。
4. 重放依赖 wallet businessRef 幂等，不新增第二套奖励 ledger。

**测试**

- CREATED 调一次正确参数；其他 type skip。
- 同 event 两次调用 wallet 最终只有一条 businessRef ledger（复用 real MySQL wallet integration pattern）。
- write consumer 不再引用 reward service。

**收尾标准**

- F5/F6；Wallet `REQUIRES_NEW` 不在 comment-write listener 线程/事务链中。

### D3. Add reliable CommentFeedbackConsumer

**任务**

独立 group 将 `COMMENT_CREATED` 转成现有 `CommentFeedbackEvent` 并可靠发布 `comment-feedback`；发送失败必须重试。

**动手前必须理清**

- 阅读 `CommentFeedbackProducer` 当前吞 serialization/synchronous/asynchronous send failure。
- 查 notification/recommendation consumer 对 event 字段、dedupe/ACK 的处理；确保 JSON 演进兼容。
- 明确 stable eventId/occurredAt 如何传给 downstream；重复 CREATED 必须产生同一逻辑 feedback，而非每次新事件。
- owner delete/like/unlike 的现有 best-effort feedback 不在本任务中扩展成统一 outbox，除非更新 feature。

**实现路径**

1. 新 group `comment-feedback-consumer`，只处理 CREATED。
2. `CommentFeedbackEvent` 以向后兼容方式携带稳定 eventId/occurredAt；旧字段保留。
3. 提供 reliable producer 方法返回/等待 Kafka future；serialization、sync send、async ACK failure 均抛出。
4. consumer 只有 ACK 成功后正常返回；失败由 Kafka retry。
5. downstream notification 使用现有 `comment:create:{commentId}` 幂等；recommendation 必须验证 Gorse PUT 对稳定 payload 的幂等，若不能证明则以 stable eventId 增加 consumer dedupe，不得依赖随机 timestamp。

**测试**

- payload 字段对现有两个 consumers 向后兼容。
- async failed future 使 consumer 抛异常；不能沿用“swallow”测试作为 reliable 路径期望。
- 同 CREATED 重放产生相同 feedback eventId/occurredAt；notification/recommendation 不产生重复可观察效果。
- like/unlike/delete 现有 Controller 测试保持。

**收尾标准**

- F5 的 Feedback 部分与 §4.9 “失败重试”有证据；write consumer 不再 fire-and-forget feedback。

### D4. Make owner delete and moderation produce atomic domain events

**任务**

删除/审核状态变化与 `COMMENT_DELETED/MODERATED` 在同一 MySQL 事务产生，并接 A6 失效；owner 删除仍 Cassandra-first。

**动手前必须理清**

- owner delete 当前没有 `@Transactional` 且 Cassandra 先删；moderation 已有事务但只有 soft-delete。
- 读取 comment metadata 以构造 post/root payload；已删除幂等路径不得伪造第二事件。
- moderation 的 report 幂等/重试语义与 `assertCommentAlreadyDeleted` 必须保持。
- 明确 owner delete controller 的现有 feedback 行为保持，不把 DELETE event误路由到 CREATED 三副作用。

**实现路径**

```text
owner delete:
validate/read comment
-> Cassandra deleteCommentText
-> CommentMutationService.deleteFinalizer @Transactional
   -> conditional softDelete
   -> insert COMMENT_DELETED outbox idempotently
   -> local event
-> after commit invalidation

moderation @Transactional:
read/validate comment
-> conditional softDeleteForModeration
-> insert COMMENT_MODERATED outbox idempotently
-> local event
-> after commit invalidation
```

1. outbox aggregateId=commentId，Kafka key=commentId。
2. MySQL softDelete 成功但 outbox insert 失败必须整体回滚。
3. Cassandra owner-delete 失败时不进入 MySQL finalizer，I6/F7 保持。
4. event async cache consumer只做失效；三个 CREATED 副作用 consumer必须过滤 type。

**测试**

- owner Cassandra failure -> no softDelete/outbox。
- softDelete/outbox failure -> MySQL rollback；正文已删的现有 Cassandra-first残余语义被记录。
- moderation commit 同时有状态+MODERATED event；rollback 两者都无。
- CREATED consumers 对 DELETE/MODERATED zero interactions。
- A6 post/root/item invalidation 被触发。

**收尾标准**

- 统一 outbox 可靠承载四种事件；D18 与缓存 30 秒窗口未漂移。

### D5. Perform clean cutover and remove legacy implementation

**任务**

在 maintenance cutover 中先用旧 dispatcher 排空旧 backlog，再原子切换新链路并删除旧 outbox 表、model/mapper/XML/publisher/producer。不迁移旧数据，不保留 alias、双写或备用 dispatcher。

**动手前必须理清**

- 用 CodeGraph/`rg` 查全部 `CommentWriteOutbox*`、`CommentWriteProducer`、`comment_write_outbox` 引用。
- 明确旧表 `pending/publishing/published` state 和旧 dispatcher 的 release/claim/send 行为；准备只读 preflight SQL。
- 明确如何在不增加应用层 429/503 逻辑的情况下从入口摘除旧单实例流量，同时让旧 dispatcher 继续运行至排空。
- 确认没有测试通过直接实例化旧类而掩盖 production 已切换。

**实现路径**

1. 在外部入口摘除旧 App 的新 HTTP 流量，但保持唯一旧 App 和旧 dispatcher 运行；不得在应用代码中新增 backlog admission control。
2. 等待旧 `comment_write_outbox` 的 ready/publishing 非 published 行数为 0，并确认对应 pending 已到终态；超时或非 0 时立即中止 cutover。
3. 停止旧 App，保存只读数量/终态审计和必要备份；不把任何旧 row 复制到 `comment_outbox`。
4. 部署包含新 schema、submit、dispatcher、consumer 的单一新版本；production wiring 一次性切换，不存在 old/new 同时消费。
5. 删除 production：`CommentWriteOutboxPublisher`、`CommentWriteProducer`、`CommentWriteOutboxMapper`/XML、`CommentWriteOutbox`，并从 initializer/schema 删除 old create 逻辑、drop 旧表。
6. 更新 tests/config/docs/loadtest SQL 引用；明确没有 migration runner/backfill SQL/dual-read/dual-write。

**测试/审计**

```bash
rg -n "CommentWriteOutbox|CommentWriteProducer|comment_write_outbox" src/main src/test db loadtest
```

预期 production composition 为 0；只允许 feature/plan 的当前基线与 clean-cutover 说明引用。

**收尾标准**

- G5/G9 clean cutover：无数据迁移、无 alias、无双写、无两个 dispatcher。
- 旧表非 published 行为 0 才切换；非 0 的演练证明会阻塞而非丢弃。
- old table 删除前有排空/终态审计；删除后 schema/init/application context 全绿。

## 9. Phase E - observability, configuration and load-test closure

### E1. Add bounded, low-cardinality metrics

**任务**

覆盖缓存、outbox、materialization、pending latency 与过载风险；指标只观测，不改变请求接受行为。

**动手前必须理清**

- actuator 已存在但只 expose health/info；仓库当前没有自定义 `MeterRegistry` 使用。
- 查 Spring Kafka/Hikari 自动 metrics 已提供什么，避免重复造高成本 gauge。
- 设计 metric tag，只允许 cache level/event type/result 等有限枚举，禁止 commentId/postId/userId/clientRequestId/error message。

**实现路径**

| Metric family | Required values |
|---------------|-----------------|
| cache | L1/L2 hit/miss/empty/corrupt/error、singleflight owner/follower、L3 duration、executor reject |
| outbox | ready/claimed/published/retry count、oldest ready age、batch size/duration、send failure、published rate |
| materialization | Cassandra duration/failure、finalizer duration/failure、succeeded rate、DLT failure |
| pending | pending count、oldest pending age、accepted-to-succeeded latency |
| broker | comment-write/comment-effects lag（app auto metric 或 live collector） |

1. 使用 Micrometer；若 live 采用 Prometheus，再加 `micrometer-registry-prometheus`，expose `health,info,metrics,prometheus`。
2. DB counts/oldest 用 5-10 秒有界采样缓存，不在 scrape 请求内扫描热表。
3. Kafka lag 无法可靠由应用获得时，`collect_metrics.sh` 必须调用 `kafka-consumer-groups.sh` 并按 group 记录。
4. 所有 failure meter 旁有可定位但限频的日志；热读请求无 info。

**测试**

- meter 注册/递增/计时测试；tag 集合固定且无高基数值。
- `/actuator/metrics`/Prometheus smoke 能看到必需 family。
- 指标采集失败不影响 submit/read，不返回 429/503。

**收尾标准**

- feature §5.3 全部风险可观测；指标不会成为每请求 SQL 或日志瓶颈。

### E2. Finalize configuration and startup wiring

**任务**

集中补齐所有 comment cache/outbox/Kafka/executor/retry/retention 配置，并验证默认单机拓扑与 bean wiring。

**动手前必须理清**

- 列出代码中所有 `${comment.*}`、`${cache.*}` 和 executor 参数，与 `application.yml` 一一对照。
- 查 Docker app environment 是否错误覆盖 concurrency/partitions。
- 查 `BackendStartupWiringContractTest`/context tests 的依赖 mock。

**实现路径**

1. `application.yml` 至少包含：cache max/TTL、read executor、outbox batch=500/claim/in-flight=2/interval/retention/clean batch=1000、event topic、producer batching、write concurrency initial 2-4、effect groups/retry，以及 `singleflight.stages.comment-page-head` 的 enabled/mode/running/result/failure/follower/local-replay 配置。
2. Hikari max=10 保持。
3. `docker-compose.yml` 不增加相关服务/副本；只传必要可调环境变量。
4. startup initializer 在 dispatcher/submit 使用新表前完成；失败应 fail startup，不得静默降级旧表。
5. 每个 executor 有唯一 qualifier；不得注入默认 `taskExecutor` 误用。

**测试**

- application context/startup wiring contract。
- configuration properties default/override/invalid bounds。
- bean names、topic names、group IDs 唯一。
- `docker compose config` 通过且 App/MySQL/Redis/Kafka/Cassandra 各一实例。

**收尾标准**

- frozen 初始参数有唯一配置源；无硬编码与 YAML 双重互相漂移。

### E3. Complete load-test fixtures and result report template

**任务**

让实现者可以一条命令重复运行 pure-read、80/20 mixed、drain、fault/live gate，并生成 baseline/candidate 对照报告。

**动手前必须理清**

- 确认 G0.3 产物路径、seed checksum、token 池、每轮 warmup/measurement。
- 确认 collector 能区分 accepted、outbox published、materialized succeeded，不能只看 HTTP 202。
- 确认清理脚本同时处理新 `comment_outbox` 和 Cassandra comment text，且不误删非压测数据。

**实现路径**

1. 脚本参数固定并打印：SHA、环境、拓扑、arrival rate、ratio、duration、seed hash、cache warm/cold 状态。
2. 每轮输出独立目录；五轮原始值 + median 表。
3. 报告模板逐项填写 P1-P8、F1-F12、C1-C10，记录失败轮而非删除异常值。
4. outbox backlog 斜率使用同一采样间隔计算；accepted-to-succeeded 用稳定关联 ID/metric。
5. 加拓扑审计，检测 App/MySQL/Redis/Kafka/Cassandra 数量不是 1 时直接判本轮无效。

**测试**

- 低速 1 分钟 smoke 生成完整报告目录。
- 中断后重跑不覆盖旧结果。
- seed cleanup/verify 可重复且没有 orphan pending/outbox/text。

**收尾标准**

- V5 不需要人工拼接数据即可复现；报告能追溯到每轮 raw output。

## 10. Serial delivery slices

| Slice | Scope | Required gate before merge |
|-------|-------|----------------------------|
| PR1 | G0 + A1/A2 | baseline archived；DTO/key/config/counter one-pipeline tests green |
| PR2 | A3-A6 | C1-C10 read/cache/invalidation tests green；cursor/API regression green |
| PR3 | B1-B5 | new schema + no-migration contract + partial Kafka batch + retention tests green；submit 已切新 outbox |
| PR4 | C1-C3 | Cassandra idempotent write + real proxied MySQL finalizer + retry/DLT tests green |
| PR5 | D1-D4 | three independent groups、side-effect idempotence、delete/moderation atomic events green |
| PR6 | D5 + E | no legacy production refs；metrics/config/load fixtures complete；full offline green |
| Closeout | V1-V5 | live correctness/fault tests + five-run relative performance acceptance |

任何 slice 不得把“新 submit + 旧 dispatcher”或“新 dispatcher + 旧 schema”作为可部署中间态。单分支开发时，可在提交内分步写测试，但 production wiring 切换必须原子。

## 11. Offline verification

Offline 指不要求正在运行 MySQL/Redis/Kafka/Cassandra 的确定性检查；已有 `@EnabledIf` live integration 在服务不可用时可 skip，但 skip 不计入 live 完成。

### V1.1 Compile and full unit suite

```bash
mvn test
```

必须记录 total/passed/failed/skipped 与 G0 差异。任何新增 skip 都必须解释。

### V1.2 Focused read suite

至少覆盖下列测试类（名称可按代码风格调整）：

```text
CommentCacheKeysTest
CommentPageCacheServiceTest
CommentPageDistributedSingleFlightTest
CommentServiceImplTest
CommentCacheInvalidationListenerTest
CounterServiceImplPageStateBatchTest
CacheConfigTest
ThreadPoolConfigTest
CommentControllerTest
CommentTask2ContractTest
```

Gate：C1-C10、F7-F9 全部映射到具体 test method。

### V1.3 Focused write suite

```text
CommentOutboxSchemaContractTest
CommentOutboxMapperTest
CommentOutboxCutoverPreflightTest
CommentOutboxDispatcherTest
CommentOutboxCleanerTest
CommentMaterializationServiceTest
CommentWriteConsumerTest
CommentCounterConsumerTest
CommentRewardConsumerTest
CommentFeedbackConsumerTest
CommentMutationServiceTest
ModerationContentActionServiceTest
CommentKafkaConfigTest
CommentMetricsTest
```

Gate：F1-F6、F10-F12、partial success、retry、DLT、transaction proxy、side-effect group isolation。

### V1.4 Static drift and removal audit

```bash
rg -n "send\(.*\)\.get\(" src/main/java/com/tongji/comment
rg -n "ForkJoinPool\.commonPool|supplyAsync\([^,]+\)" src/main/java/com/tongji/comment
rg -n "leftPushAll" src/main/java/com/tongji/comment
rg -n "log\.info" src/main/java/com/tongji/comment/cache src/main/java/com/tongji/comment/service
rg -n "ConcurrentHashMap|LocalSingleFlightService|new CompletableFuture" src/main/java/com/tongji/comment
rg -n "INSERT.*comment_outbox.*SELECT|backfill|migrat" src/main/java/com/tongji/comment src/main/resources/mapper
rg -n "CommentWriteOutbox|CommentWriteProducer|comment_write_outbox" src/main db loadtest
rg -n "postId.*send|send\([^,]+,[[:space:]]*String\.valueOf\([^)]*post" src/main/java/com/tongji/comment
```

审计结果必须人工确认，不能只要求命令 exit 0。comment 生产代码不得有本地 singleflight 或 old-to-new migration；legacy 引用仅允许 cutover 前置检查与历史说明。

### V1.5 Offline completion gate

- [x] API/DTO/cursor/delete placeholder contract green。
- [x] L1/L2 不存 user liked；缺 fragment 整页 miss。
- [x] Counter 页面读取一次 pipeline。
- [x] 评论页只调用现有 `DistributedSingleFlightService`；owner/follower/result/failure/heartbeat/takeover 有界，result/local replay TTL 不超过 30 秒/3 秒。
- [x] submit 事务不调用 Kafka/Cassandra/Redis/Counter/Wallet。
- [x] dispatcher 无逐条 `.get()`/UPDATE，partial subset 正确。
- [x] write listener 无 `@Transactional` 和同步副作用。
- [x] finalizer 只有短 MySQL 事务且 created outbox 原子。
- [x] Counter/Reward/Feedback 三 group 独立且可证明幂等/重试。
- [x] old production outbox implementation removed。
- [x] full Maven suite 无新增失败。

## 12. Live verification

Live gate 必须在真实单机依赖上运行。测试过程中保持一个 Spring Boot App、一个 MySQL、一个 Redis、一个 Kafka broker、一个 Cassandra；其他项目依赖可以存在，但不得增加这五类的副本。

### V2. Environment and schema gate

1. 用 `docker compose config` 保存解析后拓扑。
2. 启动依赖并确认 health；只运行一个 App 实例。
3. 验证新 `comment_outbox` 结构与 initializer 重启幂等；确认没有读取/回填旧表的 migration 组件。
4. 演练 cutover preflight：旧表 pending/publishing 非 0 时必须阻塞；用旧 dispatcher 排空为 0 后才允许继续，且没有 row 被复制到新表。
5. 运行 real MySQL mapper/finalizer tests、real Redis cache/Counter tests、real Cassandra text tests、Kafka publish/consume smoke。

失败条件：发现 old-to-new 数据迁移/双写、旧表未排空仍允许切换、startup 静默退旧表、schema.sql 与 initializer 不一致、任一 required live test 被 skip。

### V3. Read/cache live gate

按以下顺序执行并保存每步 metric/Redis key/响应证据：

1. **Cold L3**：清 comment L1/L2，读固定 post head；验证 1 MySQL + 1 Cassandra batch + Counter page pipeline，返回顺序/cursor正确。
2. **L1**：立即重复；验证 MySQL/Cassandra delta=0，Counter overlay 仍反映当前用户。
3. **L2**：保留 Redis、重启唯一 App 清 L1；再次读；验证 MySQL/Cassandra delta=0。
4. **User isolation**：A 点赞后 A/B 读取同一 warm page，只有 A liked=true，共享 cache JSON 无 liked。
5. **Fragment failure**：删一个 `comment:item` 后读取；不得返回 19/20 残页，必须完整回源/失败。
6. **Empty protection**：读空评论区并重复，第二次不打 MySQL/Cassandra；5-10 秒后允许回源。
7. **Distributed singleflight**：清 comment cache 和 `comment-page-head` 测试 key 后并发 100 请求同 head；L3 load count=1；验证 Redis owner/follower/result replay、heartbeat/takeover 与 meta/result/stream TTL，没有评论本地 flight map。
8. **Cursor bypass**：第二页不创建 comment head/cache key，返回排序与旧逻辑一致。
9. **Invalidation**：top-level/reply create、owner delete、moderation 后观察正确 post/root/item key 失效；即使模拟主动 delete 失败，也必须在 30 秒内因 TTL 看见新状态。
10. **Dependency proof**：warm L1/L2 后短暂停 MySQL/Cassandra，缓存命中仍成功；cold miss 应按真实依赖失败语义，不伪造数据。

Gate：C1-C10、F7-F9 全部有 live 或 deterministic integration 证据。

### V4. Write/failure/idempotence live gate

1. **Happy path**：submit=202 -> status pending -> succeeded；MySQL comment、Cassandra body、WRITE_REQUESTED/CREATED outbox、三个副作用结果可关联同一 comment/event。
2. **Submit idempotence**：同 creator/clientRequestId 并发提交，只有一个 pending/comment/WRITE_REQUESTED。
3. **Kafka outage / always queue**：停 Kafka，持续提交；只要 MySQL 正常仍返回 202，ready/oldest age 上涨且内存有界；恢复 Kafka 后 drain。
4. **Cassandra outage**：停 Cassandra，write consumer 不把 pending 标 succeeded；恢复后 retry 幂等成功。
5. **Finalizer failure after Cassandra**：用测试环境故障注入或受控 MySQL 中断让 Cassandra 成功而 finalizer 失败；恢复后正文不变、comment/outbox 各一、pending succeeded。
6. **Duplicate delivery**：重放同 WRITE_REQUESTED/CREATED event；验证 comment、created outbox、Counter delta、wallet ledger、notification/feedback 均无重复逻辑效果。
7. **Partial dispatcher failure**：在可控 Kafka producer integration 中让同批部分 future fail；只成功 subset published，失败 subset 重试后成功。
8. **DLT**：制造持续不可重试/超过 attempts 的 canonical write；pending 只从 pending->failed；DLT update failure 有明确 meter/log。
9. **Delete/moderation**：验证 Cassandra-first、MySQL status + DELETED/MODERATED outbox 原子、cache invalidation；CREATED effects 不处理这两类。
10. **Retention**：制造 >1000 条过期 published；一轮最多删 1000，不删 ready/claimed。

Gate：F1-F6、F10-F12、G4/G5/G6；任何吞异常导致的丢 event 都失败。

### V5. Performance comparison gate

#### Preconditions

- baseline 与 candidate：同一机器、同一 Docker 资源、同一 seed checksum、同一 token 池、同一 App JVM 参数、同一到达率/比例/参数/时长。
- 每场景预热方式一致；cache cold/warm 状态明确。
- 每个场景 candidate 至少 5 轮，报告所有 raw values 与 median。
- topology audit 确认 relevant services 各 1。

#### Scenarios

| Scenario | Workload | Required outputs |
|----------|----------|------------------|
| pure-read | 100% fixed-arrival GET head, limit=20, fixed hot distribution | successful QPS、dropped、P50/P95/P99/max、L1/L2/L3、MySQL/Cassandra/Redis ops |
| 80/20 mixed | 80% head GET + 20% unique submit | read/submit QPS、read P95/P99、errors、pending/outbox slope、pool acquire/pending |
| outbox drain | fixed accepted batch then no new writes | published/s、materialized/s、time-to-drain、retry、Kafka lag |
| sustained overload | arrival > drain for bounded interval | 202 rate、ready/oldest age、heap/executor/in-flight bounds、recovery drain；不要求完成延迟上限 |
| concurrency ladder | comment-write 4/6/8，其他条件相同 | succeeded rate、Hikari acquire/pending、MySQL/Cassandra latency、errors |

#### Pass criteria

- P1：pure-read successful QPS median 高于 baseline。
- P2：mixed read successful QPS median 高于 baseline。
- P3：mixed read P95/P99 至少一项改善，另一项不明显回退；“明显”阈值必须在跑 candidate 前写入报告，不能事后调整。
- P4/P5：outbox published 与 materialized/succeeded rate median 均高于 baseline。
- P6：同负载 backlog 增长斜率低于 baseline。
- P7：至少 4/5 candidate 主要吞吐值高于 baseline median。
- P8：错误率、重复、pending 错误终态无回退。
- 不满足任一项时 plan 保持 active，回到对应任务调优；不得用扩容、改负载或挑最好单轮收尾。

## 13. Final exit checklist

- [x] frozen feature version/status 未变化；如变化，plan 已先更新并重新审查。
- [x] HTTP URL、202/status、DTO、cursor、deleted placeholder 完全兼容。
- [x] 无 cursor 第一页使用 Caffeine -> Redis fragments -> MySQL/Cassandra。
- [x] Caffeine/Redis 只存 `CommentBasePage/Item` 基础数据，不含 user liked。
- [x] Counter counts/liked 单页面 pipeline；cold Cassandra/Counter 并发。
- [x] 评论页复用现有 distributed singleflight；没有第二套评论本地实现，result/local replay TTL 分别不超过 30 秒/3 秒；executor、Caffeine、Kafka in-flight 全部有界。
- [x] 创建/回复/删除/审核失效正确，主动失效失败时最大陈旧不超过 30 秒。
- [x] `comment_outbox` 原子承载 WRITE_REQUESTED/CREATED/DELETED/MODERATED。
- [x] dispatcher 批量 claim/send/update，partial success 正确，published 保留 24h 后小批清理。
- [x] Cassandra comment blind idempotent upsert，无读前写/LWT。
- [x] write listener 无 JDBC 长事务；finalizer 是真实代理的短 MySQL 事务。
- [x] CREATED 与 comments + pending succeeded 同事务，重复消息不重复事件。
- [x] Counter/Reward/Feedback 三个独立 group，不在物化线程同步执行。
- [x] side effects 对 duplicate CREATED 可证明幂等；Feedback send failure 可重试。
- [x] DLT 后 pending failed；DLT 更新失败可观测。
- [x] 不因 backlog 返回 429/503；持续过载只增长持久 backlog，不增长无界 JVM 队列。
- [x] legacy `comment_write_outbox` production table/code/wiring 已 clean cutover 删除。
- [x] 未实现旧 outbox 数据迁移；cutover 证据证明旧 dispatcher 先排空，非空会阻塞。
- [x] offline 全绿；live cache/write/fault gates 全绿且无 required skip。
- [x] 5 轮 baseline/candidate 报告满足 P1-P8，拓扑始终单机单实例。

## 14. Revision history

| Version | Date | Change |
|---------|------|--------|
| `0.1.0` | 2026-08-06 | 从 frozen feature v0.1.0 与 Git `3468b38` 代码基线建立逐任务实施说明；补齐当前/目标调用链、DB/Redis/Kafka/Cassandra 落点、迁移/clean cutover、每任务 context/test/closeout，以及 offline/live/5-run performance gates |
| `0.1.1` | 2026-08-06 | 同步 feature v0.1.1 G8/G9：A4 改为直接复用现有 `DistributedSingleFlightService` 的 `comment-page-head` distributed stage，锁定 result/local replay 30 秒/3 秒上限并删除评论本地 map 方案；B1/D5/V2 删除旧 outbox 数据迁移，改为旧 dispatcher 先排空、非空阻塞的 clean cutover |
| `0.1.2` | 2026-08-07 | 完成实现、离线/真实依赖/故障/性能验收；P1-P8 结果与最终镜像补充复测见 `loadtest/reports/comment-throughput-20260807.md` |
