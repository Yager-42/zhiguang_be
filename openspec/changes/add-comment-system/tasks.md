# Tasks

## 1. Data Model

- [ ] 1.1 新增评论元数据表，包含 `comment_id`、`post_id`、`root_id`、`parent_id`、`creator_id`、`client_request_id`、状态、计数字段和时间，并用唯一键 `(creator_id, client_request_id)` 作为重复消息保护；不新增 `content_key`，评论正文用 `comment_id` 直接查询 Cassandra。
- [ ] 1.2 为一级评论分页、二级回复分页、作者查询建立索引。
- [ ] 1.3 新增 `pending_comments` 评论提交状态表，包含 `pending_comment_id`、`creator_id`、`post_id`、`client_request_id`、状态和时间，并用唯一键 `(creator_id, client_request_id)` 支持 pending/succeeded/failed 查询与提交幂等。

## 2. API

- [ ] 2.1 新增发布评论接口，返回 `202 Accepted`、`clientRequestId`、`pendingCommentId`。
- [ ] 2.2 新增评论提交状态查询接口。
- [ ] 2.3 新增一级评论游标分页接口。
- [ ] 2.4 新增二级回复游标分页接口。
- [ ] 2.5 新增评论软删接口。

## 3. Async Write

- [ ] 3.1 定义评论写入 Kafka 事件模型。
- [ ] 3.2 发布接口先按 `(creator_id, client_request_id)` 查找并复用已有 `pendingCommentId`；重复请求返回已有 pending/status，不重复写 Kafka。
- [ ] 3.3 实现批量消费者，写 Cassandra 正文和 MySQL 元数据。
- [ ] 3.4 实现失败重试、死信主题和失败状态回写；消费者遇到 `(creator_id, client_request_id)` 唯一键 `DuplicateKeyException` 时视为已有评论/提交成功并 ack，不写出不同 `comment_id` 的孤儿 Cassandra 正文。

## 4. Counting and Interaction

- [ ] 4.1 扩展计数系统支持 `comment` 实体点赞。
- [ ] 4.2 维护知文评论数和一级评论回复数。
- [ ] 4.3 评论发布、删除、点赞事件投递给推荐事件流。

## 5. Verification

- [ ] 5.1 增加评论发布幂等测试。
- [ ] 5.2 增加异步消费者批量落库测试。
- [ ] 5.3 增加评论分页和软删楼层结构测试。
- [ ] 5.4 增加评论点赞和计数重建测试。
