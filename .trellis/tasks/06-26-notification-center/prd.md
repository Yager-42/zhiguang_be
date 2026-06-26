# Notification Center

## Goal

实现一个独立的站内通知中心后端模块，把“别人对我做了什么”统一收口，支持通知列表、未读数、单条已读、全部已读，并复用现有 Kafka / Outbox 事件流。

## Confirmed Facts

- 关注事件已经走现有 Outbox + Canal + Kafka 链路。
  - 关注写入由 `RelationManagerImpl` 完成。
  - 事件由 `RelationPublisher` 写入 `outbox` 表，类型为 `FollowCreated` / `FollowCanceled`。
  - 下游消费者通过 `OutboxTopics.CANAL_OUTBOX = canal-outbox` 消费。
- 点赞事件已经走现有 Kafka 链路。
  - `ActionController` 调用 `CounterService.like/unlike`。
  - `CounterServiceImpl` 在状态变化时发布 `CounterEvent` 到 `counter-events`。
  - `CounterEvent` 当前只包含 `entityType`、`entityId`、`metric`、`userId`、`delta`，没有稳定事件实例键。
- 评论事件已经走现有 Kafka 链路。
  - `CommentServiceImpl.submit` 发布 `CommentWriteEvent`。
  - `CommentWriteConsumer` 在评论最终落库后发布 `CommentFeedbackEvent` 到 `comment-feedback`。
  - `CommentFeedbackEvent` 包含 `commentId`、`postId`、`rootId`、`parentId`、`creatorId`、`action`。
- 当前数据库里还没有通知表或通知模块。
- 当前 `db/schema.sql` 已有可复用基础：
  - `outbox`
  - `comments`
  - `pending_comments`
  - `know_posts`
  - `publish_attempt`
  - `following`
  - `follower`
- 当前帖子发布状态机是：
  - `draft -> publishing -> published`
  - 失败态为 `publish_failed`
- 当前仓库没有已落地的 `moderation` / `report` 模块，也没有明确的审核结果事实事件源。
- 仓库里存在旧项目 `quxiangshe/backend` 的 RabbitMQ 通知实现痕迹，但当前 `com.tongji` 主链路已经以 Kafka / Outbox 为主。新通知中心不能沿用那套旧结构。

## Requirements

- 通知中心必须落在独立模块，预期包路径为 `src/main/java/com/tongji/notification/`。
- 一期只支持这三类通知：
  - 点赞通知
  - 评论通知
  - 关注通知
- 通知列表必须支持分页。
- 必须提供当前登录用户未读数接口。
- 必须提供单条已读能力。
- 必须提供全部已读能力。
- 未读数和通知列表必须共用同一事实源，不能各自维护一套状态。
- 通知写入必须复用现有 Kafka / Outbox 事件，不新增第二套 MQ 体系。
- 通知生产逻辑必须位于通知模块消费者侧，不允许把通知 DTO 临时拼进现有业务 controller 返回。
- 必须避免自通知：
  - 自己点赞自己内容不产生通知
  - 自己评论自己内容不产生通知
  - 自己关注自己本来就被业务禁止
- 评论通知规则：
  - 顶级评论通知帖子作者
  - 回复评论只通知被回复评论作者，不额外通知帖子作者
- 当前阶段不做：
  - 审核结果通知
  - 站外推送
  - 通知分组
  - 通知推荐
  - 实时推送
- 通知幂等只用于抵抗“同一次事件的重复投递”。
- 用户取消点赞后再次点赞，算新的点赞通知实例。
- 用户取关后再次关注，算新的关注通知实例。

## Hot Like Decision

- 点赞通知不按“一次点赞一条 MySQL 通知”落库。
- 点赞通知按“接收人 + 对象 + 5 分钟窗口”聚合后再落库。
- 同一对象在同一 5 分钟窗口内收到多次点赞，只形成一条聚合通知。
- 同一次点赞事件的重复投递只去重，不额外累加。
- 用户取消点赞后再次点赞，若属于新的正向事件实例，则计入当前窗口聚合。

## Acceptance Criteria

- [ ] 新增独立通知模块代码，不把通知逻辑散落进现有 controller。
- [ ] 新增通知持久化模型，能够保存通知接收人、触发人、通知类型、关联对象、已读状态、创建时间。
- [ ] 当用户点赞帖子时，帖子作者能在通知中心看到点赞通知。
- [ ] 当用户点赞评论时，评论作者能在通知中心看到点赞通知。
- [ ] 同一个帖子或评论在同一个 5 分钟窗口内收到多次点赞时，通知中心只落一条聚合点赞通知，不会逐赞批量写 MySQL。
- [ ] 当用户对帖子发表评论时，帖子作者能在通知中心看到评论通知。
- [ ] 当用户回复评论时，被回复评论作者能在通知中心看到评论通知，帖子作者不会因为这条回复额外收到第二条通知。
- [ ] 当用户关注另一个用户时，被关注用户能在通知中心看到关注通知。
- [ ] 同一用户请求通知列表时，能按时间倒序分页读取自己的通知。
- [ ] 同一用户请求未读数时，返回值与通知表中的未读记录一致。
- [ ] 标记单条已读后，该通知再次查询时状态为已读，未读数同步减少。
- [ ] 标记全部已读后，该用户全部通知状态为已读，未读数归零。
- [ ] 现有点赞、评论、关注主流程测试不被破坏。

## Out Of Scope

- 审核结果通知
- 举报 / 审核闭环
- 站外推送
- 通知分组
- 通知推荐
- 前端页面实现
- WebSocket / SSE 实时推送

## Open Questions

- None.
