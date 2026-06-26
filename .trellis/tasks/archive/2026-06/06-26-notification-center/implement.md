# Notification Center Implementation Plan

## Goal

按已确认 PRD 和 design，实现通知中心一期后端，不做审核通知。

## Ordered Checklist

- [ ] 1. 新增通知表 schema
  - [ ] 在 `db/schema.sql` 增加 `notifications`
  - [ ] 定义主键、唯一键、列表索引、未读索引
  - [ ] 支持 `aggregate_count`、`window_start`、`window_end`

- [ ] 2. 补点赞事件实例键
  - [ ] 给 `CounterEvent` 增加 `eventId`
  - [ ] 给 `CounterEvent` 增加 `occurredAt`
  - [ ] 在 `CounterServiceImpl` 产出点赞 / 收藏事件时填充新字段
  - [ ] 保证现有消费者反序列化不炸
  - [ ] 保证“取消后再次点赞”会生成新的 `eventId`

- [ ] 3. 搭通知领域模型和 Mapper
  - [ ] 新增 `Notification` model
  - [ ] 新增 `NotificationMapper`
  - [ ] 新增 mapper xml
  - [ ] 提供插入、分页查询、未读计数、单条已读、全部已读

- [ ] 4. 搭通知服务
  - [ ] 新增通知创建服务
  - [ ] 新增通知查询服务
  - [ ] 新增已读操作服务
  - [ ] 统一封装“自己给自己发通知则丢弃”

- [ ] 5. 接入关注通知消费者
  - [ ] 消费 `canal-outbox`
  - [ ] 解析 `RelationEvent`
  - [ ] 只处理 `FollowCreated`
  - [ ] 用 outbox 行 `id` 作为关注通知幂等键
  - [ ] 保证“取关后再次关注”因为 outbox 行不同而产生新通知

- [ ] 6. 接入评论通知消费者
  - [ ] 消费 `comment-feedback`
  - [ ] 只处理 `action = comment`
  - [ ] 顶级评论通知帖子作者
  - [ ] 回复评论只通知被回复评论作者
  - [ ] 用 `event_key = comment:create:{commentId}` 幂等插入

- [ ] 7. 接入点赞通知消费者
  - [ ] 消费 `counter-events`
  - [ ] 只处理 `metric = like && delta = 1`
  - [ ] 解析帖子点赞与评论点赞接收人
  - [ ] 先用 `eventId` 做 Redis 去重
  - [ ] 按“接收人 + 对象 + 5 分钟窗口”写 Redis 聚合桶
  - [ ] 新增点赞聚合 flush 任务
  - [ ] flush 时用窗口聚合键落一条通知
  - [ ] flush 成功后删除对应 Redis 桶

- [ ] 8. 暴露通知 API
  - [ ] `GET /api/v1/notifications`
  - [ ] `GET /api/v1/notifications/unread-count`
  - [ ] `POST /api/v1/notifications/{id}/read`
  - [ ] `POST /api/v1/notifications/read-all`

- [ ] 9. 写测试
  - [ ] Mapper / service 测试
  - [ ] 三类消费者测试
  - [ ] 点赞聚合 flush 测试
  - [ ] controller 测试
  - [ ] 回归测试：现有评论、关注、点赞测试仍通过

## Validation Commands

优先跑这些：

```powershell
mvn -q -Dtest=Notification* test
```

```powershell
mvn -q -Dtest=CommentWriteConsumerTest,CommentControllerTest,RelationManagerImplTest,FeedbackRecommendationConsumerTest test
```

```powershell
mvn -q test
```

如果项目测试集太大，至少跑所有改动直接相关测试。

## Risky Files

- `db/schema.sql`
- `src/main/java/com/tongji/counter/event/CounterEvent.java`
- `src/main/java/com/tongji/counter/service/impl/CounterServiceImpl.java`
- `src/main/java/com/tongji/comment/event/CommentFeedbackEvent.java`
- `src/main/java/com/tongji/relation/manager/RelationPublisher.java`

## Review Gates Before `task.py start`

- [ ] `prd.md` 无开放产品问题
- [ ] `design.md` 明确唯一事实源和幂等键
- [ ] `implement.md` 顺序可执行
- [ ] 用户确认规划可以进入实现

## Rollback Points

- 如果 `CounterEvent` 加字段导致现有消费者测试炸，先修消费者兼容，再继续通知模块。
- 如果通知列表 API 结构影响太大，保持结构化字段返回，不在一期拼复杂文案。
- 如果点赞聚合 flush 方案在本地验证不稳定，暂停点赞通知实现，不要拿逐赞写库硬顶。
