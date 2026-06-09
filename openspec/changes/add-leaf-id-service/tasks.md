# Tasks

## 1. Schema

- [ ] 1.1 新增 Leaf Segment 表，例如 `leaf_alloc`。
- [ ] 1.2 初始化 `reconciliation_task`、`admin_operation`、`audit_log` 等 `biz_tag`。
- [ ] 1.3 为 Snowflake 增加 worker/datacenter 配置项。

## 2. Service

- [ ] 2.1 定义 `IdService`，支持按 namespace 生成 ID。
- [ ] 2.2 实现 Snowflake ID 生成路径。
- [ ] 2.3 实现 Segment 双 Buffer 号段加载路径。
- [ ] 2.4 实现 namespace 到模式的路由配置。
- [ ] 2.5 处理 Segment 号段加载失败和 Snowflake 时钟回拨。

## 3. Integration

- [ ] 3.1 `post_id`、`comment_id`、`pending_comment_id`、`publish_attempt_id`、`outbox_id` 使用 Snowflake。
- [ ] 3.2 `reconciliation_task_id`、`admin_operation_id`、`audit_log_id` 使用 Segment。
- [ ] 3.3 保持 `user_id` 当前自增策略不变。

## 4. Verification

- [ ] 4.1 增加 Snowflake 并发唯一性测试。
- [ ] 4.2 增加 Segment 双 Buffer 切换测试。
- [ ] 4.3 增加 ID 生成 smoke test 和简单吞吐压测。
