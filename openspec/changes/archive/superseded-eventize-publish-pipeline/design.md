# Design: eventize-publish-pipeline

## 关键决策

### 1. Pipeline 执行模型：关键步骤同步完成，派生任务异步

接口收到发布请求后：
- **同步完成**：权限校验 → 状态校验 → MinIO 媒体验收 → Cassandra 正文写入 → DB 原子更新为 `published`
- **返回 202** 后异步触发派生任务

202 的价值是让客户端不等 ES/RAG 等慢任务，不是让核心发布逻辑也异步。

### 2. 派生任务触发：直接发 `content_published` Kafka 事件

正式发布后，业务代码直接向 `content-published` 主题投递事件，各派生消费者独立订阅：

| 消费者 | 任务 |
|--------|------|
| ES 索引消费者 | 写搜索索引 |
| RAG 消费者 | 预索引向量库 |
| Feed 消费者 | 失效 Feed/作者列表/详情缓存 |
| 计数消费者 | 初始化帖子计数结构 |
| 推荐消费者 | 消费 `content_published` 反馈 |

**不走 Canal Outbox**，避免链路过长（写表 → Canal binlog → Kafka）且边界模糊。

### 3. `draft → publishing` 原子迁移：CAS SQL

```sql
UPDATE know_posts
SET status = 'publishing', publish_attempt_id = ?, update_time = NOW()
WHERE id = ? AND status = 'draft'
```

检查影响行数：0 行 → 状态已变，拒绝本次发布并返回错误。无需悲观锁，与项目现有风格一致。

### 4. `publishing` 卡死恢复：定时任务扫描

每 5 分钟扫描 `status='publishing'` 且 `update_time < NOW() - 5分钟` 的帖子，通过 CAS 标记为 `publish_failed`：

```sql
UPDATE know_posts
SET status = 'publish_failed', update_time = NOW()
WHERE status = 'publishing' AND update_time < NOW() - INTERVAL 5 MINUTE
```

**定时任务只标记失败，不重触发 pipeline**，用户需手动重试。

**并发安全：** 定时任务和用户重试的 CAS 源状态不同，天然不冲突：
- 定时任务：`WHERE status='publishing'`
- 用户重试：`WHERE status='publish_failed'`
- 原 pipeline 线程复活后 `WHERE status='publishing'` 命中 0 行，静默放弃

### 5. 发布失败重试：创建新 attempt

每次重试生成新的 `publish_attempt_id`（`IdService.nextId(PUBLISH_ATTEMPT)`），旧 attempt 记录保留作历史。`know_posts.publish_attempt_id` 更新为最新 attempt。

重试入口的 CAS：
```sql
UPDATE know_posts
SET status = 'publishing', publish_attempt_id = ?, update_time = NOW()
WHERE id = ? AND status = 'publish_failed'
```

### 6. 派生任务失败补偿：写入 `reconciliation_task` 表

ES/RAG/Feed/计数/推荐任何一个失败，直接写一条对账任务到 `reconciliation_task`，由 `add-data-reconciliation` 统一调度补偿。不在 `publish_attempt` 里重复记录派生状态，职责边界清晰。

---

## 发布状态机

```
draft
  │
  │ 用户发布（CAS）
  ▼
publishing
  │                    │
  │ 关键步骤全部成功    │ 关键步骤失败 / 定时任务超时标记
  ▼                    ▼
published          publish_failed
                       │
                       │ 用户重试（CAS，创建新 attempt）
                       ▼
                   publishing → ...
```

**关键步骤**（失败 → `publish_failed`）：
1. 作者权限 & 帖子状态校验
2. 标题、标签、正文元信息校验
3. MinIO 媒体对象验收
4. 从 MinIO 读取文字，写入 Cassandra
5. DB 原子更新为 `published`

**派生步骤**（失败 → 写 `reconciliation_task`，帖子仍为 `published`）：
- ES 索引 / RAG 预索引 / Feed 缓存失效 / 计数初始化 / `content_published` 推荐事件

---

## 数据流

```
POST /api/v1/knowposts/{id}/publish
  │
  ├─ CAS: draft → publishing（0 行则拒绝）
  ├─ 创建 publish_attempt（IdService.nextId(PUBLISH_ATTEMPT)）
  ├─ [关键] 权限 & 状态校验
  ├─ [关键] 媒体对象验收（MinIO）
  ├─ [关键] 读 MinIO 文字 → 写 Cassandra
  ├─ [关键] DB 原子更新：publishing → published + publish_time
  └─ 返回 202 { attemptId }
        │
        └─ 发送 content_published 到 Kafka
              ├─ ES 消费者 → 失败写 reconciliation_task
              ├─ RAG 消费者 → 失败写 reconciliation_task
              ├─ Feed 消费者 → 失败写 reconciliation_task
              ├─ 计数消费者 → 失败写 reconciliation_task
              └─ 推荐消费者 → 消费 content_published 事件
```

---

## MySQL Schema 变更

```sql
-- publish_attempt 表
CREATE TABLE publish_attempt (
  attempt_id    BIGINT       NOT NULL,
  post_id       BIGINT       NOT NULL,
  creator_id    BIGINT       NOT NULL,
  status        VARCHAR(32)  NOT NULL DEFAULT 'running',  -- running/succeeded/failed
  failed_step   VARCHAR(64),
  error_message VARCHAR(512),
  retry_count   INT          NOT NULL DEFAULT 0,
  create_time   DATETIME(3)  NOT NULL,
  update_time   DATETIME(3)  NOT NULL,
  PRIMARY KEY (attempt_id),
  KEY idx_post_id (post_id)
);

-- know_posts 扩展字段
ALTER TABLE know_posts
  ADD COLUMN publish_attempt_id BIGINT,
  ADD COLUMN publish_failed_reason VARCHAR(512),
  MODIFY COLUMN status ENUM('draft','publishing','published','publish_failed','rejected','deleted');
```

---

## API 概览

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/v1/knowposts/{id}/publish` | 发布，返回 202 + attemptId |
| GET  | `/api/v1/knowposts/{id}/publish/status` | 查询发布状态 |
| POST | `/api/v1/knowposts/{id}/publish/retry` | 失败后重试（需 status=publish_failed） |
