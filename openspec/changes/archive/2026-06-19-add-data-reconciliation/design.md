# Design: add-data-reconciliation

## 关键决策

### 1. 任务执行模型：DB 轮询

Spring `@Scheduled` 定期扫描 `status='pending'` 且 `next_execute_at <= NOW()` 的任务，批量拉取后执行，完成后更新状态。

- `reconciliation_task` 表是唯一事实源，不引入 Kafka 双重机制
- 执行前抢 Redis 分布式锁（见决策 4），避免多实例重复执行

### 2. 重试策略：指数退避，最多 5 次

| 第 N 次失败 | 下次执行等待 |
|------------|------------|
| 1 | 1 分钟 |
| 2 | 2 分钟 |
| 3 | 4 分钟 |
| 4 | 8 分钟 |
| 5 | 16 分钟 → 超出进入 `dead` |

`retry_count` 存储已经记录的失败次数。一次失败发生时，先读取本次失败前的 `old_retry_count`：

- 当 `old_retry_count < 5` 时，`delay_minutes = 2^(old_retry_count)`，然后把 `retry_count` 更新为 `old_retry_count + 1`，并设置 `next_execute_at = NOW() + delay_minutes 分钟`。
- 因此连续失败的等待时间明确为 1、2、4、8、16 分钟；也可以在更新后用 `2^(new_retry_count - 1)` 得到相同延迟。
- 当 `old_retry_count >= 5` 时，不再调度下一次执行，任务进入 `dead` 并记录错误日志。

### 3. 定时扫描：游标分页 + Checkpoint

- 每批扫描 1000 条，按主键 ID 升序游标分页
- `reconciliation_checkpoint` 记录每种扫描类型的 `last_scanned_id`
- 下次调度接续上次游标，扫完一轮后 checkpoint 归零重头开始
- 避免全表一次性扫描阻塞，支持跨多个调度周期完成

### 4. 多实例并发：Redis 分布式锁

执行任务前抢锁 `recon:lock:{taskId}`（TTL = 预计执行时间 × 2），抢到才执行，失败则跳过（该任务由其他实例处理）。执行完成后释放锁并更新任务状态。

### 5. Dead 任务：可手动重置为 pending

`dead` 不是真正的终态，管理接口支持将其重置：
- `POST /api/v1/reconciliation/tasks/{id}/retry`：`dead → pending`，`retry_count` 归零，`next_execute_at = NOW()`
- 适用于 Gorse/ES/Cassandra 长时间不可用恢复后批量重试

### 6. 卡住的 running 任务：定时扫描重置

定时任务扫描 `status='running'` 且 `update_time < NOW() - 10分钟` 的任务，重置为 `pending`（retry_count 不变）。覆盖进程崩溃和执行超时两种场景，不依赖重启触发。

---

## 任务状态机

```
pending
  │
  │ 调度器拉取，抢 Redis 锁成功
  ▼
running
  ├─ 执行成功 ──────────────────────────▶ succeeded（终态）
  │
  ├─ 执行失败，old_retry_count < 5
  │    └─ 按 old_retry_count 计算 1/2/4/8/16 分钟延迟，再 retry_count++ ──▶ pending
  │
  ├─ 执行失败，old_retry_count >= 5 ─────▶ dead
  │
  └─ 超时未完成（定时扫描检测）──────────▶ pending（retry_count 不变）

dead
  └─ 手动 retry ────────────────────────▶ pending（retry_count=0）
```

---

## MySQL Schema

```sql
CREATE TABLE reconciliation_task (
  id              BIGINT       NOT NULL,           -- IdService Segment
  task_type       VARCHAR(64)  NOT NULL,           -- 任务类型，如 es_index, gorse_item, cassandra_text
  target_type     VARCHAR(32)  NOT NULL,           -- 目标实体类型，如 post, comment
  target_id       BIGINT       NOT NULL,
  status          VARCHAR(16)  NOT NULL DEFAULT 'pending', -- pending/running/succeeded/dead
  retry_count     INT          NOT NULL DEFAULT 0,
  next_execute_at DATETIME(3)  NOT NULL,
  last_error      VARCHAR(512),
  created_at      DATETIME(3)  NOT NULL,
  updated_at      DATETIME(3)  NOT NULL,
  PRIMARY KEY (id),
  KEY idx_scheduled (status, next_execute_at),
  KEY idx_target (target_type, target_id, task_type)
);

CREATE TABLE reconciliation_checkpoint (
  scan_type       VARCHAR(64)  NOT NULL,           -- 扫描类型，如 post_es, comment_cassandra
  last_scanned_id BIGINT       NOT NULL DEFAULT 0,
  updated_at      DATETIME(3)  NOT NULL,
  PRIMARY KEY (scan_type)
);

CREATE TABLE reconciliation_error_log (
  id              BIGINT       NOT NULL,
  task_id         BIGINT       NOT NULL,
  error_message   TEXT,
  stack_trace     TEXT,
  created_at      DATETIME(3)  NOT NULL,
  PRIMARY KEY (id),
  KEY idx_task_id (task_id)
);
```

---

## 任务类型（task_type）清单

| task_type | 目标 | 触发来源 | 修复器 |
|-----------|------|---------|--------|
| `es_index` | post | 发布派生失败、定时扫描 | 重建 ES 文档 |
| `rag_index` | post | 发布派生失败、定时扫描 | 重建 RAG 向量 |
| `feed_cache_invalidate` | post | 发布派生失败 | 失效 Feed 缓存 |
| `gorse_item_upsert` | post | 发布派生失败、定时扫描 | upsert Gorse item |
| `gorse_feedback` | user/post | 反馈投递失败 | 补投 feedback |
| `cassandra_text` | post/comment | 检测到文本缺失 | 从 MinIO 重写 Cassandra |
| `comment_count` | post/comment | 计数异常检测 | 重建评论计数 |
| `follow_inbox` | user | 关注流 inbox 异常 | 重建 inbox ZSet |

---

## 定时扫描覆盖范围

| 扫描类型（scan_type） | 扫描逻辑 |
|---------------------|---------|
| `post_es` | 已发布帖子 vs ES 文档，缺失则创建 `es_index` 任务 |
| `post_rag` | 已发布帖子 vs RAG 向量，缺失则创建 `rag_index` 任务 |
| `post_gorse` | 已发布帖子 vs Gorse item，缺失则创建 `gorse_item_upsert` 任务 |
| `post_cassandra` | 已发布帖子 vs Cassandra text，缺失则创建 `cassandra_text` 任务 |
| `comment_cassandra` | MySQL 评论元数据 vs Cassandra text，缺失则创建 `cassandra_text` 任务 |
| `running_timeout` | `running` 超 10 分钟的任务，重置为 `pending` |

---

## 数据流

```
派生任务失败（ES/RAG/Gorse/Cassandra）
  └─ 直接 INSERT reconciliation_task（status=pending，next_execute_at=NOW()）

定时调度器（每分钟）
  └─ SELECT * FROM reconciliation_task
       WHERE status='pending' AND next_execute_at <= NOW()
       LIMIT 100
       ORDER BY next_execute_at ASC
         │
         ├─ 对每个任务抢 Redis 锁 recon:lock:{taskId}
         │    ├─ 抢到 → UPDATE status='running' → 执行修复器
         │    │         ├─ 成功 → UPDATE status='succeeded'，释放锁
         │    │         └─ 失败 → old_retry_count < 5 时按旧值计算指数退避，
         │    │                   UPDATE status='pending'，retry_count=old+1，
         │    │                   next_execute_at=NOW()+1/2/4/8/16 分钟，释放锁
         │    │                   old_retry_count >= 5 时 UPDATE status='dead'，写 error_log
         │    └─ 未抢到 → 跳过（其他实例正在执行）

定时扫描（每5分钟）
  └─ 按 checkpoint 游标分页扫全量数据 → 检测差异 → 创建补偿任务
```

---

## API 概览

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/reconciliation/tasks` | 查询任务列表（支持 status/target_type/target_id 过滤） |
| GET | `/api/v1/reconciliation/tasks/{id}` | 查询单个任务详情 |
| POST | `/api/v1/reconciliation/tasks/{id}/retry` | 重置 dead 任务为 pending |
| POST | `/api/v1/reconciliation/targets/{type}/{id}/rerun` | 按目标 ID 手动重跑所有相关任务 |
