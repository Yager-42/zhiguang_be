# 举报审核闭环

## Goal

补齐社区治理的最小闭环：普通用户可以举报帖子或评论；平台记录举报原因，异步调用 LLM 做自动审核，并把审核结果落到主数据和通知里。

本任务来自 `docs/superpowers/plans/2026-06-25-community-closure-roadmap-134.md` 的 Item 3。路线图要求状态机保持简单：`pending -> approved/rejected/ignored`，并明确对象类型。本轮产品决策调整为不做人工审核员，由 LLM 产出审核结果，但主状态仍由本地数据库持久化，不能把 LLM 返回当成唯一事实源。

## Confirmed Facts

- 项目是单仓 Spring Boot 后端，业务按 `com.tongji.<module>` 分包，MyBatis Mapper XML 放在 `src/main/resources/mapper`。
- MySQL 初始化表结构集中在 `db/schema.sql`，本地 Docker 通过该文件初始化数据库。
- 帖子主表是 `know_posts`。现有状态包含 `draft`、`publishing`、`published`、`publish_failed`、`rejected`、`deleted`，删除走 `KnowPostMapper.softDelete(id, creatorId)`，当前只支持作者本人删除。
- 评论主表是 `comments`。现有 `status` 为 `TINYINT`，`0` 表示正常，`1` 表示软删除；列表读取时删除评论返回 `[deleted]` 占位文本以保留楼层结构。
- 通知模块已经存在 `notifications` 表和 `NotificationService.create(Notification)`，通知列表和未读数都以 `notifications` 表为唯一数据源。
- 当前鉴权只有 JWT 登录态，没有用户角色、管理员角色或审核员权限模型。`SecurityConfig` 只区分公开接口和登录接口。
- `IdNamespace` 目前有 `NOTIFICATION`、`ADMIN_OPERATION`、`AUDIT_LOG` 等命名空间，但还没有举报或审核记录命名空间；本任务需要新增 `MODERATION_REPORT(SNOWFLAKE)`。
- 代码库当前没有已落地的 LLM 运行时接入；历史任务 `06-25-remove-ai-features` 要求移除 Spring AI、OpenAI-compatible chat client、vector store 和 `com.tongji.llm` 包。
- 当前项目使用 Spring Boot `3.2.4`。Spring AI Alibaba starter 是本任务硬要求，因此本任务必须先完成 Spring Boot / Spring AI / Spring AI Alibaba 的兼容版本升级，再实现审核链路。
- 用户已确认：不做举报用户，不做审核员角色，用 LLM 替代人工审核。
- 用户已确认：`ignored` 后举报人通知仍显示“举报已处理”，不显示“待进一步处理”。
- 用户已确认：评论审核成立后只软删评论元数据，不物理删除 Cassandra 评论正文。

## Requirements

- 用户可以提交举报，举报对象类型必须显式为 `post` 或 `comment`。
- 举报请求必须包含原因枚举，可选补充说明。
- 重复举报要有明确规则，不能因为重复点击制造多条无法区分的待处理记录。
- 系统要持久化举报记录，至少记录举报人、被举报对象、原因、状态、创建时间和更新时间。
- 举报接口只负责校验、写入举报记录和写入审核请求事件，返回 `202 Accepted`、`reportId` 和 `pending` 状态。
- LLM 审核必须由后台 consumer 异步执行，不在举报请求线程内调用 LLM。
- 举报入库后由后台 consumer 调用 LLM 审核，不新增人工审核员角色、审核台或人工处理接口。
- 审核状态只允许从 `pending` 流转到 `approved`、`rejected` 或 `ignored`。
- 审核处理必须记录 LLM 提供方、模型名、结论、置信度或等价分数、处理时间和审核摘要。
- `approved` 表示举报成立，需要自动处置主数据；帖子下架为 `rejected`，评论软删为 `status = 1`。
- 评论审核成立后不删除 Cassandra 正文，只通过 `comments.status = 1` 隐藏用户可见内容，并保留内部审计能力。
- `rejected` 表示举报不成立，不改主数据。
- `ignored` 是一个特殊状态，只表示 LLM 没有给出可采纳结论，不能用来表达“人工认为无需处理”。
- 只有 LLM 不可用、超时、响应格式无效、输入不足、低置信度等无法可靠判断的情况，举报才可以进入 `ignored`。
- `ignored` 是自动审核失败终态；本轮只保留可筛选的失败原因、重试字段和后续人审所需数据，不实现人工审核入口。
- 后台 consumer 可对 LLM 临时失败做有限次数系统重试；超过重试上限后才落为 `ignored`。
- 审核结果需要通知被处理内容的作者。
- 举报人也需要收到举报结果通知。
- 举报人通知只表达“举报已处理”，不暴露 `approved`、`rejected`、`ignored` 等具体审核结论；`ignored` 也按“举报已处理”展示。
- 作者通知可以表达内容状态变化，例如举报成立导致帖子下架或评论删除。
- 审核通知使用平台 actor，不能因为举报人与内容作者相同而被现有自通知丢弃逻辑吞掉。
- 帖子和评论两类对象的处理边界要分开定义，不能用一个含糊字段兼容所有对象。
- 重复举报规则：同一用户对同一对象重复提交只返回既有举报记录，不新建记录；若记录仍是 `pending` 或正在系统重试，不重复触发审核；若已终态，返回终态结果但举报人通知仍只表达“已处理”。

## Initial Non-Goals

- 不做复杂工单系统。
- 不做多级审核流。
- 不做举报用户。
- 不做审核员角色、审核台或人工审核处理接口。
- 不接外部风控平台；LLM 只作为本任务的自动审核依赖。
- 不在本轮补完整后台角色/权限体系。
- 不在本轮实现 `ignored` 记录的人工复核处理入口。
- 不恢复旧 `com.tongji.llm`、RAG、向量库或通用 AI 能力。

## Acceptance Criteria

- [ ] 项目依赖升级到与 Spring AI Alibaba starter 兼容的 Spring Boot / Spring AI / Spring AI Alibaba 版本，且现有测试通过或兼容风险被明确记录。
- [ ] `IdNamespace` 新增 `MODERATION_REPORT(SNOWFLAKE)`，举报主键由 `IdService.nextId(IdNamespace.MODERATION_REPORT)` 生成，并有路由测试覆盖。
- [ ] `db/schema.sql` 包含举报记录和审核处理所需表结构、状态字段、对象类型字段、索引、幂等约束、重试字段和 `ignored` 失败原因字段。
- [ ] `src/main/java/com/tongji/moderation/` 或等价模块中有清晰的 API、Service、Mapper、Model/DTO 分层。
- [ ] 普通登录用户可以举报帖子和评论；举报用户、非法对象、非法原因、空对象 ID 会被拒绝。
- [ ] 举报接口返回 `202 Accepted`、`reportId` 和 `pending`，不会在请求线程内调用 LLM。
- [ ] 举报记录入库后会通过审核请求事件触发后台 LLM 审核，LLM 结果被持久化为本地审核状态。
- [ ] 审核状态只允许从 `pending` 流转到 `approved`、`rejected` 或 `ignored`，已终态记录不能再次处理。
- [ ] `approved` 后帖子会下架为 `rejected`，并与搜索 outbox 软删事件处于同一事务边界；评论会软删为 `status = 1` 且不删除 Cassandra 正文。
- [ ] LLM 不可用或无法可靠判断时不会错误下架内容；系统重试耗尽后只能落为 `ignored`，并能按失败原因筛出用于后续人工审核。
- [ ] 审核结果通知被处理内容作者和举报人，且复用现有 `notifications` 表，不新增第二套未读或通知状态。
- [ ] 举报人通知不暴露具体审核结论，`ignored` 也显示“举报已处理”；作者通知可以暴露内容处置结果。
- [ ] 审核通知不会因为现有自通知过滤规则而丢失。
- [ ] 覆盖 Controller/Service/Mapper 或 schema contract 测试，验证举报提交、重复举报、LLM 结果落库、状态流转、通知写入和非法状态流转。

## Technical Decisions

- LLM 接入使用 Spring AI Alibaba 的 DashScope starter；这是硬要求，因此依赖升级是本任务前置步骤。
- 目标依赖路线锁定为 Spring Boot `3.5.10`、Spring AI `1.1.2`、Spring AI Alibaba `1.1.2.2`。
- DashScope starter 由 `spring-ai-alibaba-extensions-bom` 管理；本任务导入 `spring-ai-bom` 和 `spring-ai-alibaba-extensions-bom`，不走 Spring AI Alibaba `2.0.0-M1.1` / Spring Boot 4 路线。
- 模型和凭据通过配置提供，审核业务仍通过本地 `ModerationLlmClient` 接口隔离具体框架。
