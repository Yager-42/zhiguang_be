# Tasks

## 1. State and Tables

- [ ] 1.1 新增 `publish_attempt` 表，记录 attempt、post、状态、步骤、错误和重试次数。
- [ ] 1.2 扩展 `know_posts.status` 支持 `publishing`、`publish_failed`、`rejected`。
- [ ] 1.3 在 `know_posts` 增加当前 `publish_attempt_id` 和发布失败原因字段。

## 2. API

- [ ] 2.1 调整发布接口返回 `202 Accepted` 和 `attemptId`。
- [ ] 2.2 新增发布状态查询接口。
- [ ] 2.3 新增发布失败后重试接口。

## 3. Pipeline

- [ ] 3.1 实现发布尝试创建和 `draft -> publishing` 原子迁移。
- [ ] 3.2 实现作者权限、帖子状态、标题、标签、正文和媒体对象校验。
- [ ] 3.3 实现 MinIO 媒体对象验收和 Cassandra 正文验收。
- [ ] 3.4 实现正文解析，生成纯文本、摘要候选、字数和标题结构。
- [ ] 3.5 实现正式发布 DB 原子更新。

## 4. Derived Tasks

- [ ] 4.1 发布后更新 ES 搜索索引。
- [ ] 4.2 发布后执行 RAG 预索引。
- [ ] 4.3 发布后失效 Feed、作者列表和详情缓存。
- [ ] 4.4 发布后初始化帖子计数结构。
- [ ] 4.5 发布后投递 `content_published` 推荐事件。
- [ ] 4.6 派生任务失败时生成补偿任务。

## 5. Verification

- [ ] 5.1 增加发布状态机测试。
- [ ] 5.2 增加关键动作失败进入 `publish_failed` 测试。
- [ ] 5.3 增加派生任务失败不回滚 `published` 测试。
- [ ] 5.4 增加卡在 `publishing` 的启动修复测试。
