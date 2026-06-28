# Moderation Review Loop Implementation Plan

## Goal

按 `prd.md` 和 `design.md` 实现帖子、评论举报和 Spring AI Alibaba 自动审核闭环。

## Ordered Checklist

- [ ] 1. 升级 Spring Boot / Spring AI Alibaba 兼容基线
  - [ ] 将 Spring Boot parent 从 `3.2.4` 升级到 `3.5.10`
  - [ ] 增加 `spring-ai.version=1.1.2` 和 `spring-ai-alibaba.version=1.1.2.2`
  - [ ] 引入 `spring-ai-bom`
  - [ ] 引入 `spring-ai-alibaba-extensions-bom`
  - [ ] 引入 `spring-ai-alibaba-starter-dashscope`
  - [ ] 明确不使用 `2.0.0-M1.1` / Spring Boot 4 路线
  - [ ] 跑现有冒烟测试，先解决认证、MyBatis、Kafka、Cassandra、Redis、Elasticsearch、RocketMQ 等 starter 兼容问题
  - [ ] 如果升级造成大面积非 moderation 破坏，停止实现并回到规划拆前置升级任务

- [ ] 2. 新增 moderation ID 命名空间
  - [ ] `IdNamespace` 增加 `MODERATION_REPORT(SNOWFLAKE)`
  - [ ] 举报主键使用 `IdService.nextId(IdNamespace.MODERATION_REPORT)`
  - [ ] 更新 `IdNamespaceTest` 和相关 ID 路由测试

- [ ] 3. 固定 schema contract
  - [ ] 在 `db/schema.sql` 增加 `moderation_reports`
  - [ ] 增加举报状态、对象类型、LLM 结果、失败原因、重试字段、内容处置字段、索引和幂等唯一键
  - [ ] 增加 schema contract test，钉住表名、关键字段、唯一键、retry 索引和 ignored 失败原因字段

- [ ] 4. 增加审核配置
  - [ ] 在 `application.yml` 增加 `spring.ai.dashscope` 和 `moderation.llm` 配置占位
  - [ ] 增加 `moderation.llm.enabled`
  - [ ] 增加 `moderation.llm.min-confidence`
  - [ ] 增加 `moderation.llm.max-content-chars`
  - [ ] 增加 `moderation.llm.max-retries`
  - [ ] 增加 `moderation.notification.platform-actor-user-id`

- [ ] 5. 新增 moderation 模型和 Mapper
  - [ ] 新增 `ModerationReport`
  - [ ] 新增 `ModerationStatus`
  - [ ] 新增 `ModerationReason`
  - [ ] 新增 `ModerationDecision`
  - [ ] 新增 `ModerationLlmRequest`
  - [ ] 新增 `ModerationLlmResponse`
  - [ ] 新增 `ModerationReportMapper`
  - [ ] 新增 `ModerationReportMapper.xml`
  - [ ] 支持 insert、按 reporter+target 查询、按 id 查询、pending 到终态更新、pending 重试字段更新、待重试扫描

- [ ] 6. 补审核处置所需 Mapper 方法
  - [ ] `KnowPostMapper` 增加审核下架方法，按 postId 从 `published` 更新到 `rejected`
  - [ ] `CommentMapper` 增加审核软删方法，按 commentId 从 `status = 0` 更新到 `status = 1`
  - [ ] 封装帖子审核下架服务：帖子状态更新和 outbox 软删事件必须同事务，outbox 插入失败则回滚
  - [ ] 对帖子审核下架清理详情缓存
  - [ ] 评论审核成立只软删 `comments.status`，不调用 `TextStorageService.deleteCommentText`
  - [ ] 内容处置结果写回 `content_action_status/content_action_failure`

- [ ] 7. 封装 LLM 客户端
  - [ ] 新增 `ModerationLlmClient`
  - [ ] 新增 `SpringAiAlibabaModerationLlmClient`
  - [ ] 使用 Spring AI Alibaba / DashScope ChatClient 调用模型
  - [ ] 优先使用 ChatClient typed structured output 映射到 `ModerationLlmResponse`
  - [ ] 文本 fallback 只能接受严格 JSON，并解析为 `ModerationLlmResponse`
  - [ ] Jackson 解析失败、调用异常、低置信度按设计返回失败分类
  - [ ] 单元测试覆盖 approved、rejected、低置信度、非法 JSON、异常

- [ ] 8. 实现举报提交服务
  - [ ] 校验 `targetType` 只允许 `post/comment`
  - [ ] 校验 reason 枚举和 description 长度
  - [ ] 解析目标作者和文本
  - [ ] 重复举报直接返回既有记录，不再写审核请求事件
  - [ ] 新举报在同一事务内 insert pending 并写 moderation review-request outbox
  - [ ] 不在请求线程内调用 LLM

- [ ] 9. 实现审核 consumer
  - [ ] 消费 moderation review-request 事件
  - [ ] 只处理 `entity=moderation_report && op=review_requested`
  - [ ] 无关 outbox 行、缺少 reportId 的 payload、非法 JSON 必须 ack 后跳过并记录日志
  - [ ] 新增 `ModerationReviewExecutor`，让 consumer 和 retry job 复用同一套审核执行逻辑
  - [ ] 读取 `pending` 举报记录；非 pending 直接 ack
  - [ ] 构造 LLM 输入并调用 `ModerationLlmClient`
  - [ ] 临时失败在重试上限内更新 `retry_count/next_retry_at/failure_code/failure_reason`
  - [ ] 重试耗尽、低置信度、非法响应、输入不足更新为 `ignored`
  - [ ] 有效结论用 `WHERE status = 'pending'` 原子更新为 `approved/rejected`
  - [ ] `approved` 后执行内容处置
  - [ ] 写作者通知和举报人通知
  - [ ] 测试无关 outbox、非法 payload 和重复投递不会阻塞 consumer

- [ ] 10. 实现审核重试任务
  - [ ] 新增 `ModerationReviewRetryJob`
  - [ ] 扫描 `status = pending AND next_retry_at <= NOW(3)` 的记录
  - [ ] 按 `(next_retry_at, id)` 小批量处理
  - [ ] 复用 `ModerationReviewExecutor`
  - [ ] 测试临时失败会重试，超过上限后进入 `ignored`

- [ ] 11. 暴露举报 API
  - [ ] 新增 `POST /api/v1/moderation/reports`
  - [ ] 从 JWT 提取 reporter user id
  - [ ] 返回 `202 Accepted`、`reportId` 和 `status`
  - [ ] Controller test 覆盖成功、非法对象、重复举报

- [ ] 12. 扩展通知类型
  - [ ] `NotificationType` 增加 `moderation_action`
  - [ ] `NotificationType` 增加 `report_processed`
  - [ ] 通过现有 `NotificationService.create` 写入，保持 `notifications` 为唯一事实源
  - [ ] moderation 通知使用平台 actor id
  - [ ] 测试举报人通知不暴露审核结论字段
  - [ ] 测试举报人与内容作者相同场景不会因自通知过滤丢失举报处理通知

- [ ] 13. 验证和回归
  - [ ] 跑 moderation 相关单测
  - [ ] 跑通知相关单测
  - [ ] 跑帖子、评论服务相关单测
  - [ ] 跑依赖升级相关冒烟测试
  - [ ] 跑 Maven 测试或说明无法完整执行的原因

## Validation Commands

优先跑：

```bash
mvn -q -DskipTests compile
```

```bash
mvn -q -Dtest='*Moderation*' test
```

```bash
mvn -q -Dtest='Notification*Test,CommentServiceImplTest,KnowPost*Test' test
```

最后跑：

```bash
mvn -q test
```

## Risky Files

- `pom.xml`
- `src/main/resources/application.yml`
- `db/schema.sql`
- `src/main/java/com/tongji/common/id/IdNamespace.java`
- `src/main/java/com/tongji/relation/outbox/OutboxMapper.java`
- `src/main/resources/mapper/OutboxMapper.xml`
- `src/main/java/com/tongji/knowpost/mapper/KnowPostMapper.java`
- `src/main/resources/mapper/KnowPostMapper.xml`
- `src/main/java/com/tongji/comment/mapper/CommentMapper.java`
- `src/main/resources/mapper/CommentMapper.xml`
- `src/main/java/com/tongji/notification/model/NotificationType.java`
- 新增 `src/main/java/com/tongji/moderation/**`

## Review Gates Before `task.py start`

- [ ] `prd.md` 无开放产品问题
- [ ] `design.md` 明确依赖升级路线、异步审核链路、`ignored` 语义、LLM 失败策略、通知语义和内容处置边界
- [ ] `implement.md` 顺序可执行
- [ ] senior review 的 2 个 blocker 和 4 个 major 均已在规划文档里关闭
- [ ] second senior review 的 1 个 blocker、2 个 major 和 1 个 minor 均已在规划文档里关闭
- [ ] 用户确认可以开始实现

## Rollback Points

- 如果 Spring Boot 升级引发大面积非 moderation 兼容问题，回退依赖修改并拆出前置升级任务。
- 如果真实 LLM 调用无法在本地验证，保留接口和测试替身，但不能声称真实自动审核已通过。
- 如果审核 consumer 不稳定，关闭 `moderation.llm.enabled`，新举报停留在 `pending`，不错误下架内容。
- 如果帖子下架后的搜索索引回收不稳定，先保证状态更新与 outbox 事件的事实存在，并把补偿风险写入检查结果。
