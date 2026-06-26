# Notification Center Design

## Scope

一期只做站内通知中心后端，范围固定为：

- 点赞通知
- 评论通知
- 关注通知
- 通知列表分页
- 未读数
- 单条已读
- 全部已读

明确不做：

- 审核结果通知
- 站外推送
- 通知分组
- 通知推荐
- 实时推送

## One-Sentence Design

新增独立 `notification` 模块，消费现有 `counter-events`、`comment-feedback`、`canal-outbox`，把用户可见互动折叠成通知事实，再由通知 API 统一读取和更新已读状态。

## Module Boundary

新增包：

- `src/main/java/com/tongji/notification/api/`
- `src/main/java/com/tongji/notification/api/dto/`
- `src/main/java/com/tongji/notification/service/`
- `src/main/java/com/tongji/notification/service/impl/`
- `src/main/java/com/tongji/notification/consumer/`
- `src/main/java/com/tongji/notification/mapper/`
- `src/main/java/com/tongji/notification/model/`

通知模块只做三件事：

1. 从既有事件解析通知事实
2. 落库并保证幂等
3. 提供通知查询与已读接口

现有业务模块职责不变：

- `counter` 继续只负责点赞事实
- `comment` 继续只负责评论事实
- `relation` 继续只负责关注事实

## Data Model

新增表：`notifications`

建议字段：

- `id BIGINT UNSIGNED NOT NULL`
- `recipient_user_id BIGINT UNSIGNED NOT NULL`
- `actor_user_id BIGINT UNSIGNED NOT NULL`
- `type VARCHAR(32) NOT NULL`
- `entity_type VARCHAR(32) NOT NULL`
- `entity_id BIGINT UNSIGNED NOT NULL`
- `second_entity_type VARCHAR(32) NULL`
- `second_entity_id BIGINT UNSIGNED NULL`
- `event_key VARCHAR(128) NOT NULL`
- `aggregate_count INT NOT NULL DEFAULT 1`
- `window_start DATETIME(3) NULL`
- `window_end DATETIME(3) NULL`
- `is_read TINYINT NOT NULL DEFAULT 0`
- `created_at DATETIME(3) NOT NULL`
- `read_at DATETIME(3) NULL`

索引：

- 主键：`id`
- 唯一键：`uk_notification_event_key (event_key)`
- 列表索引：`idx_notification_recipient_created (recipient_user_id, created_at, id)`
- 未读索引：`idx_notification_recipient_read (recipient_user_id, is_read, id)`

数据源唯一原则：

- 列表和未读数都只读 `notifications`
- 不额外维护 Redis 未读计数
- 不维护第二张未读汇总表

## Notification Semantics

### Follow

来源：

- `canal-outbox`

只处理：

- outbox payload 可解析为 `RelationEvent`
- `type = FollowCreated`

接收人解析：

- `recipient_user_id = toUserId`

幂等键：

- 不能用 `relationId`
- 因为取关后再关注会复用原关系行
- 必须用 outbox 行主键
- `event_key = "follow:outbox:" + outboxRowId`

### Comment

来源：

- `comment-feedback`

只处理：

- `action = comment`

接收人解析：

- 顶级评论：接收人 = 帖子作者
- 回复评论：接收人 = `parentId` 对应评论作者

规则：

- 回复评论只通知被回复评论作者
- 不额外通知帖子作者

幂等键：

- `event_key = "comment:create:" + commentId`

### Like

来源：

- `counter-events`

只处理：

- `metric = like`
- `delta = 1`

接收人解析：

- `entityType = knowpost` -> 接收人是帖子作者
- `entityType = comment` -> 接收人是评论作者

热点保护规则：

- 不逐条写 MySQL
- 先写 Redis 聚合桶
- 再按 `5 分钟` 窗口 flush 成一条聚合通知

聚合维度：

- `recipientUserId`
- `entityType`
- `entityId`
- `windowStart`

Redis key：

- 事件去重 key：
  - `notif:like:event:{eventId}`
- 聚合桶 key：
  - `notif:like:bucket:{recipientUserId}:{entityType}:{entityId}:{windowStartEpochMillis}`

桶内至少保存：

- `count`
- `latestActorUserId`
- `latestEventAt`
- `recipientUserId`
- `entityType`
- `entityId`
- `windowStart`
- `windowEnd`

幂等策略：

- `CounterEvent.eventId` 只负责抵抗“同一次 Kafka 事件重复投递”
- 先 `SETNX notif:like:event:{eventId}`
- 成功后才累加聚合桶
- 最终通知行唯一键不是 `like:{eventId}`
- 最终通知行唯一键为：
  - `like:bucket:{recipientUserId}:{entityType}:{entityId}:{windowStart}`

这符合已经确认的产品边界：

- 再次点赞算新通知
- 但热点窗口内不逐赞写库

## Required Event Shape Change

必须给 `CounterEvent` 增加：

- `eventId`
- `occurredAt`

原因：

- 当前事件没有稳定实例键
- 无法可靠抵抗 Kafka 重投
- 无法在“再次点赞算新通知”的前提下区分旧事件和新事件

## Read API Boundary

新增控制器：`/api/v1/notifications`

建议接口：

- `GET /api/v1/notifications`
  - 参数：
    - `cursorCreatedAt` 可选
    - `cursorId` 可选
    - `limit` 默认 20，最大 100
  - 返回：
    - `items`
    - `nextCursorCreatedAt`
    - `nextCursorId`
    - `hasMore`

- `GET /api/v1/notifications/unread-count`
  - 返回：
    - `unreadCount`

- `POST /api/v1/notifications/{notificationId}/read`
  - 幂等标记单条已读

- `POST /api/v1/notifications/read-all`
  - 幂等标记全部已读

DTO 至少包含：

- `id`
- `type`
- `isRead`
- `createdAt`
- `actorUserId`
- `entityType`
- `entityId`
- `secondEntityType`
- `secondEntityId`
- `aggregateCount`
- `windowStart`
- `windowEnd`

一期先返回结构化事实，不在后端拼复杂文案。

## Required Cross-Module Reads

通知消费者需要查事实，不需要改上游主流程：

- 点赞帖子通知：
  - 从 `KnowPostMapper.findById` 找帖子作者
- 点赞评论通知：
  - 从 `CommentMapper.findById` 找评论作者
- 顶级评论通知：
  - 从 `KnowPostMapper.findById` 找帖子作者
- 回复评论通知：
  - 从 `CommentMapper.findById(parentId)` 找被回复评论作者

## Compatibility / Non-Breaking Rule

必须保证：

- 不改变现有点赞接口返回
- 不改变现有评论接口返回
- 不改变现有关注接口返回
- 不改变现有 Kafka topic 名称
- 不改变现有 Outbox topic 名称

允许新增：

- `CounterEvent` 字段，只要保持 Jackson 反序列化向后兼容
- 新通知表
- 新通知 controller / service / consumer

## Failure Handling

通知属于派生数据，不该拖垮主业务写路径。

因此：

- 关注通知消费失败，不影响关注成功
- 评论通知消费失败，不影响评论成功
- 点赞通知消费失败，不影响点赞成功

做法：

- 点赞先 Redis 聚合，再幂等写表
- 评论 / 关注直接幂等写表
- 消费端独立重试
- 失败时抛异常给消费框架重试

## Flush Safety

- flush 只处理已到期窗口
- flush 成功后再删除 Redis 桶
- MySQL 侧仍保留唯一键，避免 flush 重试重复插入

## Testing Strategy

至少覆盖：

1. 点赞帖子创建通知
2. 点赞评论创建通知
3. 自己点赞自己内容不创建通知
4. 同一对象 5 分钟窗口内多次点赞只落一条通知
5. 同一点赞事件重复投递不会重复累加桶
6. 不同 5 分钟窗口会形成两条通知
7. 顶级评论通知帖子作者
8. 回复评论只通知被回复评论作者
9. 自己回复自己内容不创建通知
10. 关注创建通知
11. 重复消费同一事件不重复插入通知
12. 通知列表分页正确
13. 单条已读正确减少未读数
14. 全部已读把当前用户未读数清零

## Risks

### Risk 1: CounterEvent lacks stable idempotency key

处理：

- 在实现前先补 `CounterEvent.eventId`

### Risk 2: Like aggregation flusher can lag or duplicate-write

处理：

- flush 只处理已到期窗口
- flush 时最终通知行使用窗口聚合键做唯一键
- flush 成功后再删除 Redis 桶，避免先删后写丢数据

### Risk 3: Comment like event payload is sparse

`CommentController` 发出的 comment like/unlike 事件只有 `commentId` 和 `creatorId`，其余字段为空。

处理：

- 通知消费者从 `CommentMapper.findById(commentId)` 反查接收人

### Risk 4: Old RabbitMQ notification leftovers cause confusion

处理：

- 完全忽略旧 `quxiangshe` 代码
- 只沿 `com.tongji` 主链路实现
