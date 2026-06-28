# Moderation Review Loop Design

## Scope

本任务实现举报和 LLM 自动审核闭环，只覆盖帖子和评论：

- 用户举报帖子
- 用户举报评论
- 举报记录落库
- 审核请求事件落库
- Spring AI Alibaba 后台 consumer 调用 LLM 自动审核
- 审核结果落库
- `approved` 后自动处置内容
- 通知内容作者和举报人
- Spring Boot / Spring AI / Spring AI Alibaba 兼容升级

明确不做：

- 举报用户
- 审核员角色
- 人工审核台
- 多级审核流
- 外部风控平台
- 站外推送
- `ignored` 记录的人工复核处理入口

## State Semantics

举报状态只允许：

- `pending`：举报已入库，等待 LLM 审核。
- `approved`：LLM 给出可采纳结论，举报成立。
- `rejected`：LLM 给出可采纳结论，举报不成立。
- `ignored`：特殊终态，只表示 LLM 没有给出可采纳结论。

`ignored` 不能表示“平台认为无需处理”。只有 LLM 不可用、超时、响应格式无效、输入不足、低置信度等无法可靠判断时才可以进入 `ignored`。这些记录必须保留失败原因、重试次数和下一次重试时间，并为后续人工复核任务保留 `status = ignored` 的筛选能力；本轮不实现人工复核入口。

状态更新必须是原子的：

- LLM consumer 只能用 `WHERE status = 'pending'` 把记录推进到终态。
- 已经是 `approved/rejected/ignored` 的记录不能再次处理。
- 临时失败在重试上限内保持 `pending`，只更新 `retry_count`、`next_retry_at` 和失败原因。

## Data Model

新增表：`moderation_reports`

建议字段：

- `id BIGINT UNSIGNED NOT NULL`
- `reporter_user_id BIGINT UNSIGNED NOT NULL`
- `target_type VARCHAR(16) NOT NULL`
- `target_id BIGINT UNSIGNED NOT NULL`
- `target_owner_user_id BIGINT UNSIGNED NOT NULL`
- `reason VARCHAR(32) NOT NULL`
- `description VARCHAR(512) NULL`
- `status VARCHAR(16) NOT NULL DEFAULT 'pending'`
- `llm_provider VARCHAR(32) NULL`
- `llm_model VARCHAR(128) NULL`
- `llm_decision VARCHAR(16) NULL`
- `llm_confidence DECIMAL(5,4) NULL`
- `llm_summary VARCHAR(512) NULL`
- `failure_code VARCHAR(64) NULL`
- `failure_reason VARCHAR(512) NULL`
- `retry_count INT NOT NULL DEFAULT 0`
- `next_retry_at DATETIME(3) NULL`
- `content_action_status VARCHAR(16) NULL`
- `content_action_failure VARCHAR(512) NULL`
- `reviewed_at DATETIME(3) NULL`
- `created_at DATETIME(3) NOT NULL`
- `updated_at DATETIME(3) NOT NULL`

索引和约束：

- 主键：`id`
- 幂等键：`uk_moderation_report_reporter_target (reporter_user_id, target_type, target_id)`
- 待人工筛选：`idx_moderation_report_status_created (status, created_at, id)`
- 重试扫描：`idx_moderation_report_retry (status, next_retry_at, id)`
- 对象查询：`idx_moderation_report_target (target_type, target_id, created_at)`

`target_type` 只允许业务层写入 `post` 或 `comment`。数据库可以用 `VARCHAR` 保持项目现有风格，枚举合法性由 Service 统一校验。

举报主键由 `IdService.nextId(IdNamespace.MODERATION_REPORT)` 生成。本任务需要新增 `MODERATION_REPORT(SNOWFLAKE)`，并更新 ID namespace 路由测试。

审核请求事件复用现有 `outbox` 表，新增 payload：

```json
{
  "entity": "moderation_report",
  "op": "review_requested",
  "reportId": 123,
  "targetType": "post",
  "targetId": 456
}
```

`moderation_reports` 是审核状态事实源；`outbox` 只负责触发首次后台处理，重复投递必须靠 `WHERE status = 'pending'` 幂等。LLM 临时失败后的重试由定时任务扫描 `status = 'pending' AND next_retry_at <= NOW(3)` 的记录触发，不依赖 outbox 重新投递。

`ModerationReviewConsumer` 监听共享 `canal-outbox` 时必须先过滤 payload：

- 只处理 `entity = moderation_report && op = review_requested`。
- 无关 outbox 行直接 ack 并跳过。
- payload 缺少 `reportId` 或 JSON 解析失败时直接 ack，并记录日志；不能阻塞其他 outbox 消费。
- 测试必须覆盖无关 outbox、非法 payload 和重复投递。

## Module Boundary

新增包：`src/main/java/com/tongji/moderation/`

建议结构：

- `api/ModerationReportController`
- `api/dto/*`
- `service/ModerationReportService`
- `service/ModerationLlmClient`
- `service/ModerationReviewExecutor`
- `consumer/ModerationReviewConsumer`
- `schedule/ModerationReviewRetryJob`
- `service/impl/ModerationReportServiceImpl`
- `service/impl/SpringAiAlibabaModerationLlmClient`
- `mapper/ModerationReportMapper`
- `model/ModerationReport`
- `model/ModerationDecision`
- `model/ModerationReason`
- `model/ModerationStatus`
- `config/ModerationProperties`

审核主流程只依赖 `ModerationLlmClient` 接口，不在业务 Service 中直接写 Spring AI Alibaba 调用细节。

Spring AI Alibaba 是硬要求，因此模块实现前必须先完成依赖升级。锁定路线：

- Spring Boot：升级到 `3.5.10`
- Spring AI：使用 `1.1.2` BOM
- Spring AI Alibaba：使用 `1.1.2.2`
- BOM：导入 `spring-ai-bom` 和 `spring-ai-alibaba-extensions-bom`
- Starter：使用 `spring-ai-alibaba-starter-dashscope`

不使用 `spring-ai-alibaba-starter-dashscope` 的 `2.0.0-M1.1` / Spring Boot 4 路线。不能继续停留在 Spring Boot `3.2.4` 后直接接 starter。

## API Boundary

新增接口：

- `POST /api/v1/moderation/reports`

请求字段：

- `targetType`: `post` 或 `comment`
- `targetId`
- `reason`
- `description`

返回字段：

- `reportId`
- `status = pending`

提交成功后返回 `202 Accepted`。接口只做输入校验、目标解析、幂等插入和审核请求 outbox 写入，不在请求线程内调用 LLM。

重复提交：

- 如果同一用户对同一对象已有举报，直接返回既有 `reportId` 和当前 `status`。
- 如果既有记录仍是 `pending` 或处于系统重试等待，不重复写审核请求事件。
- 如果既有记录已终态，仍返回该终态，但举报人侧产品文案只展示“举报已处理”。

## Target Resolution

举报帖子：

- 读取 `KnowPostMapper.findById(targetId)`
- 只允许举报存在且未删除的帖子
- `target_owner_user_id = know_posts.creator_id`

举报评论：

- 读取 `CommentMapper.findById(targetId)`
- 只允许举报存在且 `status = 0` 的评论
- `target_owner_user_id = comments.creator_id`

不禁止举报自己的内容。审核通知必须使用平台 actor，而不是 reporter user id，避免现有通知服务的自通知过滤把结果通知吞掉。

## LLM Review

接入方式：

- 使用 Spring AI Alibaba DashScope starter。
- `pom.xml` 先升级 Spring Boot，再引入 Spring AI BOM、Spring AI Alibaba extensions BOM 和 `spring-ai-alibaba-starter-dashscope`。
- `application.yml` 使用 `spring.ai.dashscope.api-key` 和 `spring.ai.dashscope.chat.options.model`。
- 业务配置使用 `moderation.llm.enabled`、`moderation.llm.min-confidence`、`moderation.llm.max-content-chars`、`moderation.llm.max-retries`。

LLM 输入包含：

- 对象类型
- 举报原因
- 举报补充说明
- 标题或评论上下文
- 正文截断内容

正文读取：

- 帖子正文通过 `TextStorageService.getPostText(postId, contentUrl)`。
- 评论正文通过 `TextStorageService.getCommentTexts(List.of(commentId))`。

LLM 输出使用 typed contract。优先使用 Spring AI ChatClient 的结构化输出能力映射到本地 DTO；如果框架返回文本，则只能接受严格 JSON 后再解析成本地 DTO。

```java
record ModerationLlmResponse(
        String decision,
        BigDecimal confidence,
        String summary
) {
}
```

业务只接受：

- `decision = approved` 或 `rejected`
- `confidence >= moderation.llm.min-confidence`
- `summary` 非空且长度在持久化字段限制内

失败处理：

- 调用失败、超时、模型暂时不可用：在重试上限内保持 `pending`，更新 `retry_count`、`next_retry_at`、`failure_code`、`failure_reason`。
- 响应格式无效、`decision` 不合法、低置信度、输入不足或重试耗尽：落为 `ignored`。
- `ignored` 后不处置内容，但举报人仍收到“举报已处理”通知。

重试处理：

- `ModerationReviewConsumer` 和 `ModerationReviewRetryJob` 复用同一个 `ModerationReviewExecutor`。
- Consumer 负责 outbox 首次触发；RetryJob 负责扫描到期的 `pending` 记录。
- RetryJob 每批按 `(next_retry_at, id)` 小批量处理，避免一次性拉取大量失败记录。
- `ModerationReviewExecutor` 入口对每个 report id 使用 Redisson 分布式单飞锁，锁 key 为 `moderation:review:lock:{reportId}`。同一条举报被 Kafka 重复投递或被多实例 RetryJob 扫描到时，只有抢到锁的入口会调用 LLM；抢不到锁的入口直接跳过，下一次是否再处理仍由状态和 `next_retry_at` 决定。
- 任一执行入口在处理前都必须重新读取记录并检查 `status = pending`。

## Content Action

`approved` 后自动处置：

- 帖子：新增审核专用 Mapper 方法，把 `published` 帖子更新为 `rejected`。
- 评论：新增审核专用 Mapper 方法，把 `status = 0` 评论更新为 `status = 1`。

帖子下架必须同时做两件事：

- 清理帖子详情缓存。
- 写 `outbox` 软删事件，驱动搜索索引删除或降权。

帖子状态更新和搜索 outbox 插入必须处在同一个数据库事务内。不能像现有作者删除路径那样吞掉 outbox 失败后继续报告成功；如果 outbox 插入失败，内容处置事务必须回滚。只有事务外的后续补偿或通知失败，才允许把 `content_action_status` 标记为 `failed` 并记录 `content_action_failure`。

评论审核成立只更新 `comments.status = 1`，不调用 `TextStorageService.deleteCommentText`，不物理删除 Cassandra 正文。列表读取沿用现有 `[deleted]` 占位语义，内部保留正文用于审计和后续复核。

内容处置结果写回 `moderation_reports`：

- `content_action_status = success`：主数据和必要 outbox 已完成。
- `content_action_status = failed`：审核结论已落库，但主数据处置失败，需要补偿。
- `content_action_failure`：记录失败摘要。

不要复用作者删除方法来做审核处置，因为现有 `softDelete` 要求 `creatorId`，语义是作者操作，不是平台审核操作。

## Notifications

复用现有 `notifications` 表和 `NotificationService.create(Notification)`，不新增第二套未读状态。

新增通知类型建议：

- `moderation_action`：发给内容作者，表示内容因举报成立被处置。
- `report_processed`：发给举报人，只表示举报已处理，不暴露 `approved`、`rejected`、`ignored`。

通知 actor：

- 使用平台 actor id，不使用 reporter user id。
- 平台 actor id 由配置提供，例如 `moderation.notification.platform-actor-user-id`。
- 这样即使举报人和内容作者是同一人，也不会被 `NotificationService.create` 的自通知过滤丢弃。

幂等键：

- 作者通知：`moderation:action:{reportId}`
- 举报人通知：`moderation:report-processed:{reportId}`

通知关联对象：

- `entity_type = targetType`
- `entity_id = targetId`
- `second_entity_type = report`
- `second_entity_id = reportId`

举报人通知规则：

- `approved/rejected/ignored` 都只展示“举报已处理”。
- 不向举报人暴露具体审核结论。

作者通知规则：

- 只有内容被处置时发送 `moderation_action`。
- 作者通知可以表达帖子下架或评论删除。

## Error Handling

- `targetType` 非 `post/comment`：`BAD_REQUEST`
- 目标不存在、帖子已删除、评论已软删：`BAD_REQUEST`
- 原因非法：`BAD_REQUEST`
- 重复举报：返回既有举报记录的 `reportId` 和当前 `status`，不再次调用 LLM。
- 终态记录不能再次从 `approved/rejected/ignored` 回到其他状态。
- LLM 故障不抛给用户，不下架内容；临时失败先重试，最终无法处理才记录为 `ignored`。
- 审核请求事件重复投递：如果记录不再是 `pending`，consumer 直接确认并退出。

## Compatibility

历史任务曾移除 AI runtime，本任务明确重新引入 Spring AI Alibaba，但只限 moderation 模块。不要恢复旧 `com.tongji.llm`、RAG、向量库或通用 AI 能力。

新增依赖前先升级 Spring Boot 到 `3.5.10`，并使用 Spring AI `1.1.2` 与 Spring AI Alibaba `1.1.2.2` 的 extensions BOM 管理 DashScope starter。升级必须作为实现计划的第一阶段，并通过现有测试回归确认不会破坏认证、MyBatis、Kafka、Cassandra、Redis、Elasticsearch、RocketMQ 等 starter 组合。
