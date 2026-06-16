# Tasks

## 1. Schema

- [x] 1.1 新增 Leaf Segment 表，例如 `leaf_alloc`。
- [x] 1.2 初始化 `reconciliation_task`、`admin_operation`、`audit_log` 等 `biz_tag`。
- [x] 1.3 为 Snowflake 增加 worker/datacenter 配置项。

## 2. Service

- [x] 2.1 定义 `IdService`，支持按 namespace 生成 ID。
- [x] 2.2 实现 Snowflake ID 生成路径。
- [x] 2.3 实现 Segment 双 Buffer 号段加载路径。
- [x] 2.4 实现 namespace 到模式的路由配置。
- [x] 2.5 处理 Segment 号段加载失败和 Snowflake 时钟回拨。

## 3. Integration

- [x] 3.1 `post_id`、`comment_id`、`pending_comment_id`、`publish_attempt_id`、`relation_id`（如 `following.id`）、`outbox_id` 使用 Snowflake；关系行 ID 使用 `IdNamespace.RELATION`，关系 outbox ID 使用 `IdNamespace.OUTBOX_EVENT`，不得复用 `OUTBOX_EVENT` 生成关系行 ID。
- [x] 3.2 `reconciliation_task_id`、`admin_operation_id`、`audit_log_id` 使用 Segment。
- [x] 3.3 保持 `user_id` 当前自增策略不变。

## 4. Verification

- [x] 4.1 增加 Snowflake 并发唯一性测试。
- [x] 4.2 增加 Segment 双 Buffer 切换测试。
- [x] 4.3 增加 ID 生成 smoke test 和简单吞吐压测。
