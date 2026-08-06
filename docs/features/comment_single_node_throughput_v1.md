# Feature 边界契约：comment-single-node-throughput-v1

| 字段 | 值 |
|------|-----|
| **feature_contract_version** | `0.1.0` |
| **status** | **frozen**（grill 收敛于 2026-08-06，决策记录见 §9） |
| **updated** | 2026-08-06 |
| **feature_id** | `comment-single-node-throughput-v1` |
| **范围** | 单机、单 Spring Boot 实例下的评论读写链路吞吐优化 |
| **代码基线** | `com.tongji.comment`、`com.tongji.counter`、`com.tongji.cache`、`KnowPostFeedServiceImpl`、`application.yml`、`db/schema.sql`、`docker-compose.yml` |
| **参考源** | [100,000 QPS 评论中台设计](https://jishuzhan.net/article/1928346157223366657)；[B站评论系统架构总结](https://www.cnblogs.com/crazymakercircle/p/17197091.html#autoid-h3-10-0-0)；仅吸收可映射到当前代码的设计思想，不采用其容量结论或未验证示例 |

---

## 0. 效力与状态

1. 本文是评论链路单机吞吐改造的 **frozen feature 边界**。标为 **locked** 的决定不得由实现者自行改写；重大变更须重新 grill 并提升 `feature_contract_version`。
2. 本文只以当前生产代码、运行配置、数据库结构和已观察压测结果为基线；项目中的 OpenSpec、README、CONTEXT、历史架构文档不构成本 feature 的规范源。
3. 本 feature 覆盖完整读写链路：三级缓存、冷回源并发、Counter 批量读取、评论 outbox、Kafka 批量发布、物化短事务、异步副作用、缓存失效、指标与基准验证。
4. 本 feature 不承诺固定绝对 QPS。完成标准是相同单机、相同数据、相同负载下，读链路和写链路均取得可重复的正向提升，且功能正确性不回退。
5. 单机持续过载时不做应用层 admission control：评论提交继续尝试写入 MySQL pending/outbox；完成延迟允许随积压增长。依赖真实失败时仍按现有错误语义返回失败。

---

## 1. 动机与目标（locked grill）

### 1.1 当前问题

#### 读链路

当前 `CommentServiceImpl.page(...)` 的完整路径：

```text
MySQL 游标查询评论元数据
→ Cassandra findAllById 批量读取正文
→ Redis pipeline 读取每条评论的 SDS 计数
→ Redis pipeline 读取每条评论的 GETBIT liked
→ JVM 拼装 CommentItemResponse
```

满 20 条页面至少包含：

```text
1 次 MySQL 查询
1 次 Cassandra 批量点查
20 个 Redis SDS GET
20 个 Redis GETBIT
```

上述调用目前按顺序执行；评论列表没有 Caffeine 或 Redis 页面/片段缓存。不同用户反复读取同一热门评论区时，MySQL 元数据和 Cassandra 正文被重复加载。

#### 写链路

当前写链路：

```text
POST comment
→ MySQL 事务插入 pending_comments + comment_write_outbox
→ 50ms Scheduler claim 最多 100 条
→ 每条 kafkaTemplate.send(...).get()
→ 每条 markPublished
→ comment-write Kafka
→ @Transactional CommentWriteConsumer
→ Cassandra + MySQL + Redis + Counter/Feedback Kafka + Wallet REQUIRES_NEW
```

主要问题：

1. outbox 每条消息同步等待 Kafka ACK；无法形成 producer batch。
2. 每条消息单独更新 published/retry 状态。
3. Cassandra 网络调用发生在 MySQL listener 事务期间，占用 Hikari 连接。
4. 钱包 `REQUIRES_NEW` 在评论消费线程同步执行。
5. Counter/Feedback producer 吞发送异常，物化后的副作用没有可靠事件事实。
6. `comment_write_outbox` 只能承载写请求，不能原子承载 `COMMENT_CREATED/DELETED`。
7. published 记录没有明确保留和清理边界。

### 1.2 已观察基线

下列数字仅用于确定改进方向，正式验收必须在同一环境重新采集 baseline：

| 场景 | 观察值 |
|------|--------|
| 纯读固定到达率 | 约 `998 successful req/s`；P95 `38.02ms`；max `208.64ms`；约 75 dropped iterations |
| 80/20 混合负载 | 查询约 `796.5 req/s`；写提交约 `198.2 req/s`；查询 P95 `235.36ms` |
| outbox 发布 | 约 `126 events/s` |
| 混合压测结束时 outbox backlog | 约 `4323` |

### 1.3 目标（locked）

1. 评论无 cursor 第一页采用现有项目风格的三级缓存：**Caffeine → Redis fragments → MySQL/Cassandra**。
2. 共享评论基础数据进入缓存，用户 `liked` 在返回前通过现有 Counter 位图批量叠加，禁止用户状态污染共享缓存。
3. MySQL 返回 commentIds 后，并发执行 Cassandra 正文、Counter counts 和 liked 查询。
4. 将 Counter 的 counts 与 liked 两次 pipeline 收敛为单次页面批量读取。
5. 将 `comment_write_outbox` 演进为评论领域统一 `comment_outbox`，可靠承载写请求和物化后事件。
6. outbox publisher 改为批量 claim、异步 Kafka send、按成功/失败子集批量更新状态。
7. Cassandra 正文在 JDBC 事务外先行幂等写入；成功后进入短 MySQL finalizer 事务。
8. `COMMENT_CREATED` 与 `comments + pending succeeded` 在同一 MySQL 事务中产生。
9. Counter、Reward、Feedback 从评论物化事务中移出，改为独立 Kafka consumer group。
10. 创建、回复、删除和审核变更触发 Caffeine/Redis 缓存失效；允许最多 30 秒陈旧。
11. 保持单机、单应用、单 MySQL、单 Redis、单 Kafka broker、单 Cassandra；不设计水平扩容。
12. 在相同负载下证明读吞吐、mixed 查询延迟、outbox 发布/物化吞吐均有可重复提升。

### 1.4 非目标（locked grill）

| 不做 | 说明 |
|------|------|
| 水平扩容 | 不增加 App 副本、Redis Cluster、Kafka broker、Cassandra 节点或 MySQL 读副本 |
| 微服务拆分 | 不增加 Gateway、Nacos、Feign 或独立 Comment Query/Command 服务 |
| Canal / Debezium | 当前 Canal 代码默认关闭、Compose 未部署且 ACK 可靠性不足；不进入本 feature |
| 更换事实存储 | 不引入 TiDB、Scylla、Elasticsearch 评论列表；MySQL 元数据、Cassandra 正文保持 |
| 第二套点赞事实 | 不增加用户点赞 Set 替代现有 Redis bitmap |
| 缓存所有 cursor 页 | V1 只缓存无 cursor 第一页 |
| 近强一致缓存 | 评论页、删除和共享计数允许最多 30 秒陈旧 |
| 固定绝对 QPS | 不以 10k/20k/100k 为完成门槛 |
| 应用层过载拒绝 | 不因 outbox backlog 主动返回 429/503；持续过载允许继续排队 |
| 复制 Feed 热路径缺陷 | 不逐条读取 Counter，不在每次 cache hit 打 `log.info`，不缓存带用户态的响应 |

---

## 2. 当前代码基线

### 2.1 HTTP 与状态

保持现有接口与响应模型：

```text
POST /api/v1/posts/{postId}/comments → 202 Accepted
GET  /api/v1/comments/status/{clientRequestId}
GET  /api/v1/posts/{postId}/comments
GET  /api/v1/comments/{rootId}/replies
DELETE /api/v1/comments/{commentId}
POST/DELETE /api/v1/comments/{commentId}/like
```

保持：

- `(creator_id, client_request_id)` 幂等。
- `pending/succeeded/failed` 状态。
- `CommentSubmitResponse`、`CommentStatusResponse`、`CommentPageResponse` 对外字段兼容。
- 删除评论返回 `[deleted]` 占位并保留回复结构。

### 2.2 数据事实

| 数据 | 当前事实存储 | V1处理 |
|------|--------------|--------|
| 评论关系、作者、状态、游标字段 | MySQL `comments` | 保持 |
| 评论正文 | Cassandra `comment_text_by_comment_id` | 保持 |
| 提交状态 | MySQL `pending_comments` | 保持 |
| 评论事件 | MySQL comment outbox | 从专用写 outbox 演进为领域 outbox |
| 点赞事实 | Redis bitmap | 保持 |
| 计数快照 | Redis SDS | 保持 |
| 增量计数 | Kafka `counter-events` + Redis 聚合桶 | 保持 |
| L1 | Caffeine | 新增评论页 Bean，复用现有机制 |
| L2 | Redis IDs/item fragments | 新增评论缓存键 |

### 2.3 当前 MySQL 索引

保留当前游标分页索引：

```text
idx_post_comments(post_id, parent_id, create_time, comment_id)
idx_root_replies(root_id, create_time, comment_id)
```

本 feature 不改为 OFFSET 分页。

---

## 3. 读链路设计（locked）

### 3.1 三级缓存定义

```text
L1：Caffeine CommentBasePage
L2：Redis comment index + comment item fragments + Counter SDS/bitmap
L3：MySQL metadata + Cassandra body
```

L3 对评论是组合回源，不新增聚合数据库表。

### 3.2 内部缓存 DTO

禁止将带当前用户状态的 `CommentItemResponse` 直接写入共享缓存。新增仅内部使用的数据结构：

```java
record CommentBaseItem(
        long commentId,
        long postId,
        long rootId,
        long parentId,
        long creatorId,
        String body,
        int status,
        long likeCount,
        long replyCount,
        LocalDateTime createTime,
        LocalDateTime updateTime
) {}

record CommentBasePage(
        List<CommentBaseItem> items,
        String nextCursor,
        boolean hasMore
) {}
```

`liked` 只在返回前叠加，不进入 `CommentBaseItem`。

### 3.3 L1：Caffeine

在现有 `CacheConfig`/`CacheProperties` 体系中增加评论页 Cache Bean，不新建缓存框架：

```text
Bean：commentPageCache
Key：comment:page:post:{postId}:head:{limit}
Key：comment:page:root:{rootId}:head:{limit}
Value：CommentBasePage
```

初始配置：

```text
maximumSize：5000（可配置，压测后调整）
expireAfterWrite：3秒
```

L1 命中路径：

```text
Caffeine CommentBasePage
→ Counter liked 批量叠加
→ CommentPageResponse
```

L1 命中时禁止访问 MySQL 和 Cassandra。

### 3.4 L2：Redis 评论索引

仅缓存无 cursor 第一页：

```text
comment:idx:post:{postId}:head:{limit}:ids
comment:idx:post:{postId}:head:{limit}:cursor
comment:idx:post:{postId}:head:{limit}:hasMore

comment:idx:root:{rootId}:head:{limit}:ids
comment:idx:root:{rootId}:head:{limit}:cursor
comment:idx:root:{rootId}:head:{limit}:hasMore
```

IDs 必须按 MySQL 返回顺序保存。若使用 Redis List，必须使用保持顺序的写法（例如 `rightPushAll`），禁止不经验证复制 Feed 的 `leftPushAll`。

空评论区使用短期空值索引，避免穿透：

```text
comment:idx:...:empty = 1
TTL：5–10秒
```

索引 TTL：

```text
20–30秒随机值
```

它定义了主动失效失败时的最大页面陈旧窗口。

### 3.5 L2：Redis comment item fragment

```text
comment:item:{commentId}
```

存储：

- MySQL 评论元数据。
- Cassandra 正文。
- status/createTime/updateTime。
- 不存当前用户 liked。
- likeCount/replyCount 可以有快照，但返回前以 Counter 批量结果覆盖。

TTL：

```text
300–600秒随机值
```

缓存写入顺序固定为：

```text
1. 写所有comment:item
2. 写ids/cursor/hasMore
3. 写Caffeine
```

如果任意 item 缺失或 JSON 无法解析，整页视为 L2 miss，进入 singleflight 回源；禁止返回残缺页面。

### 3.6 用户状态叠加

继续使用现有 Redis bitmap 事实和 `CounterService`。V1 不增加本地 liked Cache，也不增加用户点赞 Set。

L1/L2/L3 返回前均执行：

```text
按commentIds批量读取likeCount/replyCount
按commentIds批量读取当前用户liked
覆盖到CommentItemResponse
```

匿名用户跳过 liked 查询。

### 3.7 Counter 页面批量接口

增加页面级组合接口，单次 Redis pipeline 同时读取 SDS 和当前用户 bitmap：

```java
Map<Long, CommentPageCounterState> getPageStateBatch(
        String entityType,
        List<Long> entityIds,
        Long userId,
        List<String> metrics
);
```

要求：

- 继续读取现有 `cnt:v1:*` SDS。
- 继续读取现有 `bm:like:*` bitmap。
- 不改变 Counter 事实模型。
- 仅将当前两次 pipeline 合并为一次。
- 某个 key 缺失时按现有零值语义返回。

### 3.8 L3 冷回源与并发

MySQL 游标查询完成并得到 commentIds 后，并发执行：

```text
Cassandra getCommentTexts(commentIds)
Counter getPageStateBatch(commentIds, userId)
```

若 Counter 页面接口未一次覆盖所有数据，则 counts/liked 仍可作为同一专用 executor 中的独立 future，但禁止串行等待。

新增专用有界 executor：

```text
Bean：commentReadExecutor
corePoolSize：8（初始值，可配置）
maximumPoolSize：16（初始值，可配置）
queueCapacity：200
拒绝策略：CallerRunsPolicy
```

禁止使用 `ForkJoinPool.commonPool()`。

### 3.9 本地 singleflight

单机单 App 使用本地 singleflight，不使用 Redis 分布式锁：

```java
ConcurrentHashMap<String, CompletableFuture<CommentBasePage>>
```

Key 使用 Redis index key。同一 key 同时只能有一个 L3 回源。完成、失败、取消路径都必须在 `finally` 清理 flight；禁止遗留永久锁。

### 3.10 缓存失效与 30 秒一致性窗口

#### 评论创建成功

MySQL finalizer 提交后：

```text
删除post第一页Caffeine
删除post第一页Redis index
如果是回复：删除root第一页Caffeine/Redis index
可预热comment:item
```

#### 删除/审核

成功后：

```text
删除comment:item
删除对应post/root Redis index
删除对应Caffeine页
```

#### 点赞/取消点赞

不删除 Redis index/item；用户 liked 每次通过 bitmap 批量叠加。Caffeine 中共享计数最多受 3 秒 TTL 影响。

#### 失效失败

允许依靠 Redis index 的 20–30 秒 TTL 兜底。locked 可见性结论：

```text
创建、删除、审核、共享计数在缓存路径中允许最多30秒陈旧。
```

---

## 4. 写链路设计（locked）

### 4.1 评论领域统一 outbox

将 `comment_write_outbox` 演进为：

```sql
CREATE TABLE comment_outbox (
    event_id BIGINT PRIMARY KEY,
    event_type VARCHAR(40) NOT NULL,
    aggregate_id BIGINT NOT NULL,
    payload JSON NOT NULL,
    state TINYINT NOT NULL DEFAULT 0,
    retry_count INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    claim_token VARCHAR(64) NULL,
    claimed_until DATETIME NULL,
    last_error VARCHAR(500) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at DATETIME NULL,
    UNIQUE KEY uk_comment_event (event_type, aggregate_id),
    KEY idx_comment_outbox_ready (state, next_attempt_at, event_id)
);
```

事件 ID 使用当前 `IdService` 的 outbox ID namespace。Kafka payload 必须包含同一个稳定 `eventId`。

### 4.2 事件类型与 Topic

| event_type | 产生位置 | Topic | Kafka key |
|------------|----------|-------|-----------|
| `COMMENT_WRITE_REQUESTED` | submit 短事务 | `comment-write` | `commentId` |
| `COMMENT_CREATED` | materialization finalizer | `comment-events` | `commentId` |
| `COMMENT_DELETED` | 删除 MySQL 事务 | `comment-events` | `commentId` |
| `COMMENT_MODERATED` | 审核状态事务 | `comment-events` | `commentId` |

禁止使用 `postId` 作为 Kafka key。当前评论没有楼层号和评论区级严格顺序要求；`postId` 会让热门帖子成为单分区热点。

### 4.3 提交事务

保持 HTTP `202 Accepted`。短 MySQL 事务：

```text
INSERT pending_comments
INSERT comment_outbox(COMMENT_WRITE_REQUESTED)
COMMIT
```

两条插入必须保持原子。禁止在 submit 事务内同步调用 Kafka、Cassandra、Redis、Counter 或 Wallet。

### 4.4 单机批量 Outbox Dispatcher

单机只有一个 dispatcher，不设计跨实例锁或多节点 claim。

流程：

```text
fixedDelay调度
→ claim ready events（默认500，可配置）
→ 按event_type路由topic
→ 异步kafkaTemplate.send
→ 等待本批所有future完成
→ 成功子集批量markPublished
→ 失败子集批量markRetry
```

约束：

- 同一时刻只允许一轮 dispatch 执行；使用单线程 scheduler 或 `AtomicBoolean running`。
- 禁止逐条 `send().get()`。
- 禁止逐条 `UPDATE`。
- 最大 in-flight 批次数为 2–4，必须有界。
- 成功事件只有在 Kafka future 成功后才能 published。
- 部分成功时只更新成功子集；失败子集重试。
- consumer 仍按至少一次语义幂等。

Kafka producer 初始配置：

```text
acks=all
enable.idempotence=true
linger.ms=5
batch.size=32768
compression.type=lz4
```

### 4.5 Outbox 重试与清理

- Kafka 发送失败采用指数退避，最大间隔 30 秒。
- 不因 backlog 主动拒绝新 submit；MySQL outbox 是单机持久队列。
- published 事件保留 24 小时后批量清理。
- 清理任务每批最多 1000 行，禁止大事务删除。
- ready/claimed/published/retry 数量、oldest ready age、batch duration、Kafka send failure 必须有指标。

### 4.6 Cassandra-first 物化

`CommentWriteConsumer` 不再以整个 listener 方法持有 MySQL 事务：

```text
1. 读取pending状态
2. 已succeeded：幂等返回
3. Cassandra盲写正文
4. 调用@Transactional finalizer
5. ACK Kafka record
```

Cassandra 评论正文不可编辑，重复写采用普通幂等 upsert：

```text
commentId固定
body固定
version固定或使用确定值
```

禁止 `INSERT IF NOT EXISTS` LWT；Paxos 成本不适合本 feature 的吞吐目标。禁止当前 `findById → version+1 → save` 的读前写路径。

### 4.7 短 MySQL Finalizer

新增独立事务组件，保证 Spring 代理生效：

```java
@Transactional
public void finalizeMaterialization(CommentWriteEvent event) {
    insert comments idempotently;
    update pending -> succeeded;
    insert COMMENT_CREATED outbox idempotently;
}
```

要求：

- Cassandra 失败时不得进入 finalizer。
- Cassandra 成功、MySQL 失败时 Kafka 重试；重复 Cassandra upsert 安全。
- `COMMENT_CREATED` 与 `pending succeeded` 同一事务。
- `UNIQUE(event_type, aggregate_id)` 防止重复副作用事件。
- duplicate metadata 恢复路径仍必须把 pending 收敛到 succeeded，但不得重复产生副作用。

### 4.8 物化失败

保留现有 retry topic / DLT 思路：

- 可重试异常继续重试。
- DLT 后更新 pending 为 failed。
- 不允许 Cassandra 异常被吞。
- 不允许 MySQL finalizer 异常被吞。
- DLT 更新失败必须记录错误指标和日志。

### 4.9 异步副作用

`COMMENT_CREATED` 由以下独立 consumer group 消费：

#### CommentCounterConsumer

- 初始化评论计数。
- post comment_count +1。
- root reply_count +1。
- 使用稳定 eventId 派生 CounterEvent。
- 继续投递现有 `counter-events`，不重建 Counter 系统。

#### CommentRewardConsumer

- 调用现有 `ContentRewardService.rewardCommentCreation`。
- 保持 `businessRef=content-reward:comment:{commentId}` 幂等。
- 不在 CommentWriteConsumer 线程中执行 Wallet `REQUIRES_NEW`。

#### CommentFeedbackConsumer

- 将 `COMMENT_CREATED` 转换为现有 `CommentFeedbackEvent`。
- 可靠投递现有 `comment-feedback` topic。
- Kafka 发送失败必须使 consumer 重试，禁止吞异常。

#### CommentCacheInvalidationListener

- 使用本地 after-commit 事件失效 Caffeine 与 Redis index。
- `COMMENT_CREATED/DELETED/MODERATED` consumer 作为异步补充，不要求分布式广播；当前只有一个 App。

### 4.10 Consumer 并发

事务边界重构前，将 `comment-write` concurrency 从 8 降至 2–4 作为初始保护值；短事务完成后按压测逐级测试 4/6/8。

Hikari 初始保持 maximumPoolSize=10。只有指标证明 `acquire/pending` 是瓶颈且 MySQL 尚有余量时才调整，禁止先靠扩大连接池掩盖长事务。

---

## 5. 单机队列与过载语义（locked grill）

### 5.1 始终排队

用户选择不做应用层主动拒绝：

- 不因 outbox backlog、oldest pending age 或 Kafka lag 返回 429/503。
- submit 只要 MySQL pending/outbox 事务成功，就继续返回 202。
- backlog 可以在 MySQL 中持续增长。
- sustained overload 下 `accepted → succeeded` 延迟没有上限保证。

### 5.2 仍必须有界的内存结构

“始终排队”仅指持久化队列，不允许 JVM 内存无限增长：

- `commentReadExecutor` 队列有界。
- outbox Kafka in-flight 有界。
- Caffeine maximumSize 有界。
- singleflight map 在 finally 清理。
- Kafka 消费由 broker lag 承担，不把未完成消息复制到无界内存队列。

当有界 executor 满时使用 `CallerRunsPolicy` 形成调用方减速，不新增应用层 429/503 语义。

### 5.3 必须观测的风险

即使不拒绝，也必须暴露：

```text
pending count
oldest pending age
outbox ready/claimed count
outbox oldest ready age
Kafka consumer lag
accepted→succeeded latency
published/materialized rate
```

告警只提示容量超限，不改变请求接受行为。

---

## 6. 模块与代码落点

### 6.1 新增或调整的组件

```text
src/main/java/com/tongji/comment/
├── api/dto/
│   └── 对外DTO保持兼容
├── cache/
│   ├── CommentBaseItem.java
│   ├── CommentBasePage.java
│   ├── CommentPageCacheService.java
│   ├── CommentCacheKeys.java
│   └── CommentCacheInvalidationListener.java
├── event/
│   ├── CommentOutboxDispatcher.java
│   ├── CommentOutboxEvent.java
│   ├── CommentCreatedEvent.java
│   ├── CommentDeletedEvent.java
│   └── CommentModeratedEvent.java
├── consumer/
│   ├── CommentWriteConsumer.java
│   ├── CommentCounterConsumer.java
│   ├── CommentRewardConsumer.java
│   └── CommentFeedbackConsumer.java
├── service/
│   ├── CommentService.java                 # 对外接口保持
│   └── impl/
│       ├── CommentServiceImpl.java
│       └── CommentMaterializationService.java
└── mapper/
    └── CommentOutboxMapper.java
```

现有 `CommentWriteOutboxPublisher`、`CommentWriteProducer`、`CommentWriteOutboxMapper` 在完成 clean cutover 后删除，不保留别名或双实现。

### 6.2 Cache 配置

继续在：

```text
com.tongji.cache.config.CacheConfig
com.tongji.cache.config.CacheProperties
```

增加 comment page 配置，不创建第二套配置类体系。

### 6.3 ThreadPool 配置

继续在现有 `ThreadPoolConfig` 中增加具名有界 executor：

```text
commentReadExecutor
commentOutboxExecutor（仅用于Kafka future协调，不承载持久队列）
```

### 6.4 数据库结构

同时更新当前实际使用的 schema 初始化入口：

- `db/schema.sql`
- 评论 outbox initializer/mapper XML

迁移要求：

1. 新建 `comment_outbox`。
2. 将旧 `comment_write_outbox` 中非 published 行回填为 `COMMENT_WRITE_REQUESTED`。
3. 切换 submit/dispatcher/consumer。
4. 验证旧表无未处理记录。
5. 删除旧表和旧代码。

不保留长期双写。

---

## 7. 实施阶段

### 7.1 P0：基线与指标

- 固化当前 pure-read、mixed、outbox drain 三组基线。
- 压测使用已登录 token 池。
- mixed 场景固定读写比例和数据分布。
- 增加缓存、outbox、materialization、pending latency 指标。

### 7.2 P1：三级缓存读链路

- Caffeine `CommentBasePage`。
- Redis IDs/cursor/hasMore/item fragments。
- 空值缓存。
- 本地 singleflight。
- Counter 页面组合接口。
- L3 并发回源。
- 创建/回复/删除/审核缓存失效。

### 7.3 P2：批量 Outbox Dispatcher

- `comment_outbox` schema。
- 批量 claim。
- 异步 Kafka send。
- 批量 published/retry。
- Kafka producer batching/compression。
- published 清理。

### 7.4 P3：物化短事务

- Cassandra 去掉读前写，改普通幂等 upsert。
- listener 移除整方法 MySQL 事务。
- `CommentMaterializationService` 短事务。
- `COMMENT_CREATED` 同事务 outbox。
- 更新重复消息和 DLT 行为测试。

### 7.5 P4：副作用异步化

- Counter consumer。
- Reward consumer。
- Feedback consumer。
- 稳定 eventId。
- 删除物化线程内同步 Wallet 和 fire-and-forget producer。

### 7.6 P5：回归与性能验证

- 运行功能回归。
- 相同环境多轮对比。
- 验证 30 秒一致性窗口。
- 验证 backlog 持续增长时仍按 locked 决策接受提交。

---

## 8. 验收标准（locked）

### 8.1 功能正确性

| ID | 要求 |
|----|------|
| F1 | submit 仍返回 202、clientRequestId、pendingCommentId |
| F2 | 相同 creatorId/clientRequestId 不产生第二条评论 |
| F3 | Cassandra 写失败时 pending 不得 succeeded |
| F4 | MySQL finalizer 失败可重试，重复 Cassandra upsert 不改变正文 |
| F5 | 重复 `COMMENT_CREATED` 不产生重复 Counter、Reward 或 Feedback |
| F6 | Reward 保持现有 businessRef 幂等 |
| F7 | deleted 评论仍返回 `[deleted]` 并保留回复结构 |
| F8 | 用户 A 的 liked 不得进入用户 B 的 Caffeine/Redis 基础页 |
| F9 | Redis 缺任一 item fragment 时不得返回残缺页面 |
| F10 | Kafka future 成功前 outbox 不得标记 published |
| F11 | 部分 Kafka 发送成功时，只更新成功子集；失败子集可重试 |
| F12 | DLT 后 pending 进入 failed |

### 8.2 缓存行为

| ID | 要求 |
|----|------|
| C1 | L1 命中时 MySQL/Cassandra 调用数为 0 |
| C2 | L2 完整命中时 MySQL/Cassandra 调用数为 0 |
| C3 | 只有无 cursor 第一页进入 Caffeine/L2 index |
| C4 | Caffeine 只保存 `CommentBasePage`，不保存用户 liked |
| C5 | 同一 cache key 并发 miss 只发生一次 L3 回源 |
| C6 | singleflight 成功、异常、取消路径均清理 flight |
| C7 | 创建/回复/删除/审核缓存陈旧不超过 30 秒 |
| C8 | Redis IDs 顺序与 MySQL 游标排序一致 |
| C9 | 空评论区命中短期 empty cache，不重复穿透 L3 |
| C10 | 热路径不得逐请求记录 info 日志 |

### 8.3 性能对比

不设绝对 QPS。Baseline 与 Candidate 必须：

- 同一台机器。
- 相同 Docker 拓扑和资源配置。
- 相同种子数据。
- 相同 token 池。
- 相同到达率、读写比例、请求参数和持续时间。
- 每个场景至少运行 5 次，报告中位数和各轮原始值。

必须满足：

| ID | 要求 |
|----|------|
| P1 | Candidate pure-read 可持续 successful QPS 中位数高于 Baseline |
| P2 | Candidate mixed 场景查询 successful QPS 中位数高于 Baseline |
| P3 | Candidate mixed 查询 P95/P99 至少一项改善，另一项不得明显回退 |
| P4 | Candidate outbox published rate 中位数高于 Baseline |
| P5 | Candidate materialized/succeeded rate 中位数高于 Baseline |
| P6 | Candidate 同负载下 outbox backlog 增长斜率低于 Baseline |
| P7 | 至少 4/5 个 Candidate 运行在主要吞吐指标上优于 Baseline 中位数，避免单轮噪声冒充提升 |
| P8 | 错误率、数据重复、pending 错误终态不得回退 |

“有提升”必须由相同实验条件下的重复结果证明，单次运行或不同负载不可作为完成证据。

### 8.4 单机约束

验收过程中必须保持：

```text
1个Spring Boot App
1个MySQL
1个Redis
1个Kafka broker
1个Cassandra
```

不得通过增加实例或节点获得性能结论。

---

## 9. Grill 决策记录（locked）

| ID | 决策点 | 结论 |
|----|--------|------|
| G1 | 性能目标 | 不设绝对 QPS；相同条件下读写均有可重复提升即可 |
| G2 | Feature 范围 | 完整读写链路改造，不拆成只读或最小范围 feature |
| G3 | 缓存一致性 | 30 秒宽松最终一致；创建、删除、审核和共享计数允许短时陈旧 |
| G4 | 单机过载 | 始终尝试写入 pending/outbox，不因 backlog 主动 429/503；持续过载完成延迟无上限 |
| G5 | Outbox 模型 | 统一评论领域 `comment_outbox`，可靠承载 WRITE_REQUESTED/CREATED/DELETED/MODERATED |
| G6 | 物化顺序 | Cassandra 在 JDBC 事务外先写，成功后进入短 MySQL finalizer 事务 |
| G7 | 部署边界 | 单机单体，不做任何水平扩容 |

---

## 10. 代码已回答、未向用户追问的决策

以下问题可由当前代码或性能目标直接裁决，未占用 grill 问答，但决定同样属于 V1 边界：

| ID | 决策 | 理由 |
|----|------|------|
| D1 | 本地缓存使用 Caffeine | 当前 `CacheConfig`、Feed、详情和关系链路已经使用 Caffeine |
| D2 | 三级缓存为 Caffeine → Redis fragments → MySQL/Cassandra | 当前 Feed 已存在相同实际代码模式；评论 L3 是组合事实源 |
| D3 | 只缓存无 cursor 第一页 | 热点收益最大，避免单机内存被 cursor 组合污染 |
| D4 | Redis 使用 index/item 分离，不存完整评论页 JSON | 对应现有 Feed IDs/item 片段代码，也对应博客 reply_index/reply_content |
| D5 | Caffeine/Redis 只缓存基础评论，不缓存用户 liked | 防止用户状态交叉污染；当前 Counter 已提供批量 liked |
| D6 | 使用本地 singleflight | 当前只有一个 App，不需要 Redis 分布式锁 |
| D7 | Counter 保持 bitmap/SDS | 当前 Counter 代码已实现，不建立第二套点赞事实 |
| D8 | Counter counts/liked 合并为一个页面 pipeline | 当前两个独立 pipeline 可直接收敛，减少 Redis RTT |
| D9 | Cassandra 使用普通幂等 upsert，不用 LWT | 评论正文不可编辑；LWT Paxos 会损害写吞吐 |
| D10 | Kafka key 使用 commentId，不使用 postId | 只需要评论生命周期顺序；postId 会造成热门帖子单分区热点 |
| D11 | 单 dispatcher + 有界 async send | 单 App 无需跨实例抢锁；批量异步即可利用 Kafka producer batching |
| D12 | 不使用 Canal | 当前默认关闭、Compose 未部署且桥接 ACK 不可靠 |
| D13 | 保持 MySQL metadata + Cassandra body | 当前代码查询与物化均依赖该模型；换存储不是性能 feature 的必要条件 |
| D14 | 保持对外 HTTP DTO 和 202/status 语义 | 性能改造不要求 API 破坏性变更 |
| D15 | 不复制 Feed 的逐条 Counter 与每请求 info 日志 | 两者会直接损害单机热路径吞吐 |
| D16 | published outbox 定期小批清理 | 当前无清理会使 ready 索引和表持续膨胀 |
| D17 | executor、Caffeine、Kafka in-flight 必须有界 | 用户选择“始终排队”只适用于 MySQL/Kafka 持久队列，不允许 JVM 无界内存增长 |
| D18 | 删除路径 V1 保留 Cassandra-first 语义 | 当前代码和测试固定该顺序；QPS改造无需顺手改变删除失败语义 |

---

## 11. 开放项

无阻塞实现的产品/架构开放项。以下仅为压测调参，不改变 frozen 边界：

- Caffeine `maximumSize`。
- Caffeine TTL 在 1–3 秒范围内的最终值。
- `commentReadExecutor` 线程数和队列长度。
- outbox batch size（初始 500）。
- `comment-write` consumer concurrency（2/4/6/8 阶梯测试）。
- Kafka `batch.size` 在 32–64KiB 的最终值。

这些参数必须通过同机压测选择，不需要重新 grill；若改变单机、数据事实、30 秒一致性、始终排队或统一 outbox 等 locked 决定，则必须重新 grill。

---

## 12. 变更记录

| 版本 | 日期 | 变更 |
|------|------|------|
| `0.1.0` | 2026-08-06 | 初版 frozen；完成 G1–G7 grill，冻结单机三级缓存、统一评论 outbox、Cassandra-first 短事务、完整读写链路和相对性能验收边界 |
