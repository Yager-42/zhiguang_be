# Tasks

## 1. Framework

- [ ] 1.1 新增 `reconciliation_task` 表。
- [ ] 1.2 新增 `reconciliation_checkpoint` 表。
- [ ] 1.3 新增 `reconciliation_error_log` 表。
- [ ] 1.4 定义任务类型、目标类型、状态机和重试策略。
- [ ] 1.5 实现任务调度器、执行器和分片锁。

## 2. Triggers

- [ ] 2.1 实现定时扫描任务。
- [ ] 2.2 发布成功后的派生任务失败时生成补偿任务。
- [ ] 2.3 Gorse、ES、RAG 投递失败时生成补偿任务。
- [ ] 2.4 服务启动后扫描卡住的 `publishing`、`processing`、`running` 任务。
- [ ] 2.5 提供手动重跑接口。
- [ ] 2.6 扫描卡住的 `publishing` 帖子时遵守 publish attempt 状态机，只标记失败或生成重试入口，不直接重放发布。

## 3. Reconcilers

- [ ] 3.1 实现点赞/收藏位图到 SDS/ES 计数字段对齐。
- [ ] 3.2 实现 following 到 follower、Redis 列表和用户计数对齐。
- [ ] 3.3 实现评论元数据到 Cassandra 正文、评论数、回复数、缓存对齐。
- [ ] 3.4 实现已发布帖子到 ES、RAG、Feed 缓存、Gorse item 对齐。
- [ ] 3.5 实现关注流 inbox 和 author posts 对齐。
- [ ] 3.6 实现推荐用户、物品、反馈补投。

## 4. Observability

- [ ] 4.1 记录任务执行耗时、失败原因、重试次数。
- [ ] 4.2 暴露任务查询接口。
- [ ] 4.3 为 dead 任务记录不可自动修复错误。

## 5. Verification

- [ ] 5.1 增加任务状态机测试。
- [ ] 5.2 增加分片扫描和 checkpoint 测试。
- [ ] 5.3 增加至少三个领域的补偿 smoke test。
