# Design: add-cassandra-text-storage

## 关键决策

### 1. 正文写入时机：Publish attempt 的关键流程

文字正文在 publish attempt 被受理后的**关键发布流程**中写入 Cassandra，草稿阶段不写。初始发布 HTTP 请求已按 `align-publish-relation-architecture` 变更返回 `202 Accepted + publishAttemptId`；Cassandra 写入属于后台关键流程的一部分。

- 客户端仍通过 presigned PUT 上传文件到 MinIO（流程不变）
- `/content/confirm` 流程不变，MySQL 继续存 `content_object_key`
- publish attempt 进入 `publishing` 后，关键流程从 MinIO 读取文字内容并写入 Cassandra
- Cassandra 写入成功后，发布状态机才能继续推进到 `published`
- 之后 ES/RAG pipeline 从 Cassandra 读取正文构建索引

### 2. MinIO 文字文件：废弃但不删

新发布后 Cassandra 是唯一读路径，MinIO 文字文件自然废弃，**不主动清理**。

- 历史文件保留在 MinIO，不做迁移
- 新发布帖子不再依赖 MinIO 文字文件

### 3. 历史帖子兼容：Fallback 到 MinIO

老帖子正文只在 MinIO，Cassandra 无数据。ES/RAG 重建索引时：

```
读 Cassandra → 缺失 → 降级读 MinIO content_url
```

`TextStorageService` 的读取方法需感知缺失并触发 fallback，不抛异常。

### 4. Content Key：直接使用业务 ID

- 帖子：Cassandra 主键 = `post_id`
- 评论/回复：Cassandra 主键 = `comment_id`

MySQL **不新增** `content_key` 列，直接用现有业务 ID 查询 Cassandra。

### 5. text_write_log_by_day：v1 不建

写入审计和补偿扫描由 `add-data-reconciliation` 统一负责，v1 不重复建日志表。

### 6. Cassandra 写失败：阻断发布完成

发布关键流程中 Cassandra 写入失败 -> attempt 标记为 `failed`，帖子标记为 `publish_failed`，客户端通过发布状态接口看到失败并走 retry。它不会改变初始 publish HTTP 请求已经返回 `202 Accepted` 的事实。

- Cassandra 是正文事实源，缺失则 ES/RAG 无法构建，发布内容实际不可用
- 与 `align-publish-relation-architecture` 的 attempt 状态机一致：关键事实缺失时不得执行 `publishing -> published`

### 7. 删除行为：硬删

帖子或评论删除时，**直接删除** Cassandra 对应行。

- 可见性由 MySQL `status` 字段控制，Cassandra 不需要软删标记
- 避免读取时额外过滤 `deleted` 行

### 8. 重新发布（编辑后）：覆盖写

帖子编辑后重新发布，**覆盖写** Cassandra 同一行，`version` 字段自增用于乐观并发校验，不保留历史版本正文。

### 9. Schema 初始化：CQL 初始化脚本

与现有 `db/schema.sql` 风格保持一致，通过 **docker-compose 挂载 CQL 脚本**在容器启动时执行建表，应用不负责 DDL。

- 脚本路径：`db/cassandra/init.cql`
- Spring Data Cassandra 配置 `schema-action: none`

---

## 数据流（新发布）

```
客户端
  │
  ├─ PUT presigned URL ──────────────────▶ MinIO（文字文件 + 媒体）
  │
  └─ POST /content/confirm
         │
         ▼
       MySQL（存 content_object_key、etag、sha256）
         │
         ▼
       Publish 请求
         │
         ├─ 接受 attempt，返回 202 + publishAttemptId
         └─ 后台关键发布流程
              ├─ 从 MinIO 读取文字内容
              ├─ 写入 Cassandra post_text_by_post_id（失败则 attempt=failed/post=publish_failed）
              └─ MySQL 更新 status = published
               │
               ▼
            Kafka 事件 → ES/RAG pipeline → 从 Cassandra 读正文 → 建索引
```

## 数据流（索引重建，含历史帖子）

```
ES/RAG pipeline
  │
  ├─ 读 Cassandra（post_id）
  │     ├─ 命中 ──▶ 使用 Cassandra 正文
  │     └─ 缺失 ──▶ 降级读 MinIO content_url
  │
  └─ 构建 ES/RAG 文档
```

---

## Cassandra Schema

```cql
CREATE KEYSPACE IF NOT EXISTS zhiguang
  WITH replication = {'class': 'SimpleStrategy', 'replication_factor': 1};

CREATE TABLE IF NOT EXISTS zhiguang.post_text_by_post_id (
  post_id    BIGINT PRIMARY KEY,
  body       TEXT,
  version    INT,
  sha256     TEXT,
  updated_at TIMESTAMP
);

CREATE TABLE IF NOT EXISTS zhiguang.comment_text_by_comment_id (
  comment_id BIGINT PRIMARY KEY,
  body       TEXT,
  version    INT,
  updated_at TIMESTAMP
);
```

---

## TextStorageService 接口职责

| 方法 | 行为 |
|------|------|
| `savePostText(postId, body)` | 覆盖写，version 自增，失败抛异常 |
| `getPostText(postId)` | 读 Cassandra；缺失时 fallback MinIO；仍缺失返回 empty |
| `saveCommentText(commentId, body)` | 覆盖写，失败抛异常 |
| `getCommentTexts(commentIds)` | 批量读，返回 `Map<commentId, body>` |
| `deletePostText(postId)` | 硬删 Cassandra 行 |
| `deleteCommentText(commentId)` | 硬删 Cassandra 行 |
