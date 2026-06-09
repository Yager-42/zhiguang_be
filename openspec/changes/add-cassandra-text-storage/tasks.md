# Tasks

## 1. Environment

- [ ] 1.1 在 `docker-compose.yml` 增加 Cassandra 服务、端口、数据卷和健康检查。
- [ ] 1.2 在 `application.yml` 增加 Cassandra keyspace、contact points、datacenter、timeout 配置。
- [ ] 1.3 增加 Cassandra 启动初始化脚本或应用启动建表逻辑。

## 2. Schema

- [ ] 2.1 创建 `post_text_by_post_id`，按 `post_id` 读取正文、版本、hash、更新时间。
- [ ] 2.2 创建 `comment_text_by_comment_id`，按 `comment_id` 批量读取评论/回复正文。
- [ ] 2.3 可选创建 `text_write_log_by_day`，用于写入审计和补偿扫描。

## 3. Service

- [ ] 3.1 定义 `TextStorageService`，支持保存、读取、批量读取、删除/软删正文。
- [ ] 3.2 实现 `CassandraTextStorageService`。
- [ ] 3.3 为发布正文和评论正文定义独立 key 规则。
- [ ] 3.4 增加写失败、读失败、缺正文的异常类型。

## 4. Integration

- [ ] 4.1 调整知文发布请求，使文字正文进入 Cassandra，媒体附件继续走 MinIO。
- [ ] 4.2 调整搜索索引与 RAG 索引构建，从 Cassandra 读取文字正文。
- [ ] 4.3 为评论系统提供正文写入与批量读取能力。

## 5. Verification

- [ ] 5.1 增加 Cassandra 容器启动文档。
- [ ] 5.2 增加 `TextStorageService` 单元测试。
- [ ] 5.3 增加 Cassandra 集成测试或本地 smoke test。
