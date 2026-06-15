# Design: add-comment-system

## 关键决策

### 1. pendingCommentId = 最终 comment_id

发布接口预先通过 `IdService.nextId(IdNamespace.COMMENT)` 生成 Snowflake ID，写入 Kafka 时即为最终 `comment_id`。消费者落库也使用同一 ID，无需二次映射。

客户端拿到 `pendingCommentId` 后可直接用于状态查询和评论展示。

### 2. 评论写入：独立 `comment-write` Kafka 主题

发布接口直接写 Kafka（不走 Canal Outbox），消费者批量消费落库。

- **不复用** Canal Outbox 模式，避免中间临时表和 Canal 解析的额外链路
- 提交幂等先由 `pending_comments` 的 `(creator_id, client_request_id)` 唯一键保证，重复提交复用既有 `pendingCommentId`，不重复发送 Kafka
- Consumer 侧 `comments` 的 `(creator_id, client_request_id)` 唯一键仅作为重复消息保护

### 3. 评论列表读路径：纯 MySQL 游标分页

v1 不加 Redis 缓存。`comments` 表建好索引（`post_id + status + create_time`），足以支撑当前量级。评论软删和异步写入场景下缓存失效成本高，等有性能压力再加。

### 4. 软删展示：占位符

删除评论后，列表保留楼层位置，正文替换为"该评论已删除"，回复仍正常显示。

- MySQL 标记 `status = deleted`，Cassandra 正文硬删
- 读接口检测 `status = deleted` 时返回占位符，不返回原始正文

### 5. clientRequestId 幂等：pending 提交去重 + Consumer 重复消息保护

发布接口先按 `(creator_id, client_request_id)` 查 `pending_comments`。若已有记录，直接返回既有 `pendingCommentId`，不生成新 `comment_id`，也不再次发送 Kafka。

- 持久保证，不依赖 Redis TTL 窗口
- 幂等发生在提交路径，避免重复请求进入异步写链路
- Consumer 写 `comments` 时保留 `(creator_id, client_request_id)` 唯一键作为 Kafka 重复消息保护，重复消息直接跳过（ack）

### 6. 计数扩展：复用现有 SDS 结构

直接启用现有 Counter SDS 预留字段：

| 计数类型 | entityType | 说明 |
|---------|-----------|------|
| 评论点赞数 | `comment` | 复用 `like` 位图 + SDS idx=1，传 `entityType=comment` |
| 帖子评论数 | `knowpost` | 使用 SDS 预留 `comment` 字段（idx=3） |
| 一级评论回复数 | `comment` | 使用 SDS 预留 `comment` 字段（idx=3）表示回复数 |

`CounterService.like/unlike` 已支持 `entityType` 路由，零改造即可复用位图去重逻辑。

### 7. 异步持久化失败：死信主题 + 回写失败状态

消费者耗尽重试后：
1. 消息投递到 dead-letter topic（`comment-write-dlt`）
2. 同时将 `pending_comment` 状态表中对应 `pendingCommentId` 标记为 `failed`

客户端轮询状态接口感知 `failed` 后提示用户重试，运维可通过死信补偿处理。

---

## 数据流

### 写路径

```
客户端 POST /comments
  │
  ├─ 按 creator_id + clientRequestId 查 pending_comments
  ├─ 已存在：复用 pendingCommentId 返回，不生成新 ID，不发送 Kafka
  ├─ 不存在：IdService.nextId(COMMENT) → comment_id = pendingCommentId
  ├─ 写 pending_comments 状态表（post_id、client_request_id、status=pending）
  └─ 发送到 comment-write 主题（包含 comment_id、clientRequestId、body）
       │
       ▼
  Comment Consumer（批量消费）
       ├─ 写 Cassandra comment_text_by_comment_id（body）
       ├─ 写 MySQL comments（元数据，(creator_id, client_request_id) 仅作重复消息保护）
       ├─ 更新 pending_comment 状态为 succeeded
       └─ 发布计数事件（帖子评论数 +1）
            │
            └─ 失败时：投 comment-write-dlt，回写 status=failed
```

### 读路径（评论列表）

```
客户端 GET /posts/{postId}/comments?cursor=...
  │
  ├─ MySQL 游标分页查 comments（post_id + status + create_time 索引）
  ├─ 过滤 status=deleted → 返回占位符正文
  └─ 批量读 Cassandra comment_text_by_comment_id → 组装响应
```

---

## MySQL Schema

```sql
CREATE TABLE comments (
  comment_id        BIGINT       NOT NULL,
  post_id           BIGINT       NOT NULL,
  root_id           BIGINT       NOT NULL DEFAULT 0,   -- 0 表示一级评论自身
  parent_id         BIGINT       NOT NULL DEFAULT 0,   -- 0 表示无父评论
  creator_id        BIGINT       NOT NULL,
  client_request_id VARCHAR(64)  NOT NULL,
  status            TINYINT      NOT NULL DEFAULT 0,   -- 0=normal, 1=deleted
  like_count        INT          NOT NULL DEFAULT 0,
  reply_count       INT          NOT NULL DEFAULT 0,
  create_time       DATETIME(3)  NOT NULL,
  update_time       DATETIME(3)  NOT NULL,
  PRIMARY KEY (comment_id),
  UNIQUE KEY uk_comment_creator_client_request (creator_id, client_request_id),
  KEY idx_post_comments (post_id, status, create_time),
  KEY idx_root_replies  (root_id, status, create_time),
  KEY idx_creator       (creator_id, create_time)
);

CREATE TABLE pending_comments (
  pending_comment_id BIGINT       NOT NULL,
  post_id            BIGINT       NOT NULL,
  creator_id         BIGINT       NOT NULL,
  client_request_id  VARCHAR(64)  NOT NULL,
  status             VARCHAR(16)  NOT NULL DEFAULT 'pending', -- pending/succeeded/failed
  create_time        DATETIME(3)  NOT NULL,
  update_time        DATETIME(3)  NOT NULL,
  PRIMARY KEY (pending_comment_id),
  UNIQUE KEY uk_creator_client_request (creator_id, client_request_id)
);
```

---

## API 概览

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/posts/{postId}/comments` | 发布评论，返回 202 + pendingCommentId |
| GET  | `/comments/{pendingCommentId}/status` | 查询提交状态 |
| GET  | `/posts/{postId}/comments` | 一级评论游标分页 |
| GET  | `/comments/{commentId}/replies` | 二级回复游标分页 |
| DELETE | `/comments/{commentId}` | 软删评论 |
