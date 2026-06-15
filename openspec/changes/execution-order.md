# OpenSpec Changes 执行顺序

## 决策原则

- **先基础设施，再架构主线**：统一 ID 能先降低后续所有实体改造成本；发布/关系 Manager 与 attempt 语义随后统一边界。
- **事实源先于派生源**：Cassandra 正文事实源稳定后，再做评论、推荐、Feed、对账修复器。
- **被吸收的 change 不再独立执行**：`eventize-publish-pipeline` 已移入 `archive/superseded-eventize-publish-pipeline`，其内容被拆分吸收。
- **微服务拆分只作约束**：`split-to-microservices` 当前是架构约束和 roadmap，不进入第一批代码落地。

## 执行阶段

### 阶段 0 — 架构约束先读

| 变更 | 说明 |
|------|------|
| `split-to-microservices` | 当前不拆服务、不引入 Gateway/Nacos；只读取其边界约束：不跨边界 JOIN、外部依赖走 Adapter、跨边界写入走事件、ID 通过 namespace 生成。 |

### 阶段 1 — ID 基础层

| 变更 | 说明 |
|------|------|
| `add-leaf-id-service` | 统一 ID 生成接口，替换散落的 Snowflake/随机 ID；为 post、publish attempt、comment、relation、outbox、reconciliation 提供 namespace。 |

### 阶段 2 — 发布/关系架构主线

| 变更 | 说明 |
|------|------|
| `align-publish-relation-architecture` | 引入 Manager 层、发布 `202 + publishAttemptId`、幂等、状态查询/重试、线程池隔离和 Sentinel Guard。 |

此阶段确立发布语义：`202 Accepted` 只表示 attempt 被受理，不表示帖子已发布。关键发布流程后台执行，成功后 `published`，失败后 `publish_failed`。

### 阶段 3 — 文本事实源

| 变更 | 说明 |
|------|------|
| `add-cassandra-text-storage` | 引入 Cassandra 作为发布正文和评论正文事实源；Cassandra 写失败会使 accepted attempt 失败并阻止 `publishing -> published`。 |

### 阶段 4 — 评论能力

| 变更 | 说明 |
|------|------|
| `add-comment-system` | 依赖 `IdService` 和 `TextStorageService`，新增二级评论、pending 状态、Kafka 异步写入、评论计数和软删。 |

### 阶段 5 — 推荐与关注流

| 变更 | 说明 |
|------|------|
| `add-recommendation-and-follow-feed` | 消费成功发布后的 `content_published` 事件、评论/点赞/收藏/关注反馈事件；实现 Gorse Adapter、本地关注流和首页混排。 |

### 阶段 6 — 数据对账

| 变更 | 说明 |
|------|------|
| `add-data-reconciliation` | 在主要事实源和派生源成形后完整落地；可先实现框架，但具体修复器应随 Cassandra、评论、推荐/关注流完成后扩展。 |

## 依赖关系图

```text
split-to-microservices (约束/roadmap，先读不先做)
         │
         ▼
add-leaf-id-service
         │
         ▼
align-publish-relation-architecture
         │
         ▼
add-cassandra-text-storage
         │
         ▼
add-comment-system
         │
         ▼
add-recommendation-and-follow-feed
         │
         ▼
add-data-reconciliation
```

## Superseded

| 变更 | 处理 |
|------|------|
| `eventize-publish-pipeline` | 已移入 `archive/superseded-eventize-publish-pipeline`，不再独立执行。CAS 状态机、卡死恢复、`content_published`、派生补偿等内容已分别吸收到 `align-publish-relation-architecture`、`add-cassandra-text-storage`、`add-recommendation-and-follow-feed`、`add-data-reconciliation`。 |

## 前置检查

| 变更 | 前置 |
|------|------|
| `add-leaf-id-service` | 无 |
| `align-publish-relation-architecture` | 建议先完成 `add-leaf-id-service`；若并行实现，必须先提供临时 `IdService` 兼容层 |
| `add-cassandra-text-storage` | `align-publish-relation-architecture` 的 publish attempt 语义已确定 |
| `add-comment-system` | `add-leaf-id-service`, `add-cassandra-text-storage` |
| `add-recommendation-and-follow-feed` | `align-publish-relation-architecture`, `add-comment-system` |
| `add-data-reconciliation` | 可先做框架；完整修复器依赖 Cassandra、评论、推荐/关注流 |
