# add-data-reconciliation

## Why

当前系统已有事件驱动计数、关系伪从、ES 索引、RAG 索引和缓存。新增评论、发布 pipeline、Gorse 和 Cassandra 后，事实源与派生源更多。必须提供系统化对账和补偿能力，避免异常长期积累。

## What

- 覆盖所有“事实源 -> 派生源”的链路。
- 支持定时扫描、事件失败补偿、手动重跑和启动修复。
- 使用任务表、分片、状态机和 Kafka 重试执行补偿。
- 覆盖计数、关系、评论、发布 pipeline、关注流、推荐/Gorse。
- 记录不可自动修复的错误，供人工处理。

## Impact

- 新增 `reconciliation_task`、`reconciliation_checkpoint`、`reconciliation_error_log`。
- 发布 pipeline、推荐事件、ES/RAG 任务失败时需要创建补偿任务。
- 需要为各领域实现差异检测和修复器。
- 管理接口需要支持按目标 ID 或任务 ID 手动重跑。

## Non-goals

- 不追求跨所有派生源的实时强一致。
- 不替代正常事件消费链路。
- 不做完整运营后台，只提供后端接口和任务能力。
