# Grill-Me Decision Log

本文档记录 OpenSpec 规划前的关键追问、推荐答案与最终选择，用于保证后续 `proposal.md`、`spec.md`、`tasks.md` 的边界一致。

## Q1. 变更组织方式

**问题：** 这些能力要写成一个 OpenSpec 大变更，还是拆成多个可独立评审/落地的变更？

**推荐答案：** 拆成多个 OpenSpec changes。

**最终选择：** 拆成多个 OpenSpec changes。

**拆分方案：**

| Change ID | 覆盖内容 |
|---|---|
| `add-comment-system` | 评论系统、评论点赞、评论计数 |
| `add-recommendation-and-follow-feed` | 个性化推荐、热点/相似推荐、关注流推拉 |
| `eventize-publish-pipeline` | 发布流程事件化、Outbox、索引/RAG/Feed 异步处理 |
| `add-rbac-and-auth-hardening` | RBAC、内容审核权限、真实短信/邮件发送 |
| `add-data-reconciliation` | 计数/关系/评论/搜索数据对齐与补偿 |
| `add-platform-infrastructure` | 多云对象存储、独立 ID 服务、短文本 KV/宽表存储 |
| `split-to-microservices` | Gateway、Nacos、服务拆分、Feign 或 RPC 边界 |

**原因：** 这些能力覆盖业务、架构、基础设施和数据一致性，属于平台二期升级。拆分后每个 change 有独立边界，便于评审、实现、验收和归档。

## Q2. 规划目标

**问题：** 这些 changes 的目标是真实准备落地实现，还是主要服务于简历/项目包装？

**推荐答案：** 按真实可落地实现来写，但任务拆到二期规划级别。

**最终选择：** 真实落地实现优先，`spec` / `tasks` 必须能指导开发。

**原因：** 真实落地优先可以避免 OpenSpec 变成空泛包装。后续每个需求都必须能映射到当前项目的代码、数据模型、接口、异步链路或部署组件演进。

## Q3. 微服务拆分时机

**问题：** `split-to-microservices` 要不要作为这批 changes 的真实落地目标？

**推荐答案：** 暂时不把微服务拆分作为第一批落地目标，只写为后续架构演进 change。

**最终选择：** 先保留单体模块化，微服务只写为后续演进。

**原因：** 当前项目是单体 Spring Boot，但模块边界已经比较清楚。优先在单体内补齐评论、推荐、发布事件化、RBAC、对账和基础设施抽象，能降低实现风险，并为未来拆分服务保留边界。

## Q4. 第一批真实落地顺序

**问题：** 第一批真实落地的业务顺序怎么排？

**推荐答案：** 评论系统 → 发布事件化 → 数据对齐 → 推荐/关注流。

**最终选择：** 评论系统 → 发布事件化 → 数据对齐 → 推荐/关注流。

**原因：** 评论会引入新的互动实体和计数字段；发布事件化会影响搜索索引、RAG、Feed 缓存失效；数据对齐要覆盖点赞、收藏、关注、评论和搜索索引；推荐和关注流依赖内容、关系、互动事件稳定后再做，能减少返工。

## Q5. 评论系统第一版范围

**问题：** 评论系统第一版要做到什么范围？

**推荐答案：** 完整的二级评论 + 评论点赞 + 评论计数 + 软删闭环，但不做无限楼中楼。

**最终选择：** 二级评论 + 点赞 + 计数 + 软删。

**范围：**

| 能力 | 决策 |
|---|---|
| 评论层级 | 一级评论 + 二级回复 |
| 评论发布 | 支持对知文评论、对一级评论回复 |
| 评论删除 | 软删，保留楼层结构 |
| 评论点赞 | 复用/扩展当前计数系统，支持 `comment` 实体 |
| 评论计数 | 知文评论数、一级评论回复数 |
| 分页 | 一级评论游标分页，二级回复分页 |
| 内容存储 | 短文本先 MySQL，后续再迁移 KV/宽表 |
| 暂不做 | 无限嵌套、评论图片、评论审核流、敏感词系统 |

## Q6. 评论发布写入模型

**问题：** 评论发布采用什么写入模型？

**推荐答案：** 同步写评论主表 + 同事务写 Outbox，异步更新计数、缓存、推荐事件。

**最终选择：** 完全异步写，接口只入 MQ，消费者批量落库。

**影响：** 评论发布接口不能简单返回“评论已持久化”。后续 spec 必须定义提交凭证、处理中状态、失败回执、重试策略、幂等键、前端乐观展示规则，以及消费者批量落库与死信补偿策略。

## Q7. 异步评论接口语义

**问题：** 评论采用完全异步写后，接口返回什么语义？

**推荐答案：** 返回 `202 Accepted + clientRequestId + pendingCommentId`，前端先乐观展示“发送中”，消费者落库成功后通过查询接口看到正式评论，失败通过状态查询接口或重试队列暴露。

**最终选择：** `202 Accepted`，返回 pending ID，前端显示发送中。

**原因：** 该语义明确区分“请求已接收”和“评论已持久化”，能支撑异步写的一致性边界。`pendingCommentId` 用于前端稳定展示、幂等重试和后续状态查询。

## Q8. 评论消息基础设施

**问题：** 评论异步写使用哪种消息基础设施？

**推荐答案：** 复用当前 Kafka，不再引入 RabbitMQ/RocketMQ。

**最终选择：** 复用 Kafka。

**原因：** 当前项目已经具备 Kafka、Canal Outbox、计数事件链路。评论异步写、批量消费、失败重试和死信主题都可以基于 Kafka 完成，避免引入新的消息中间件增加部署和运维复杂度。

## Q9. 发布 Pipeline 第一版范围

**问题：** 异步发布 pipeline 要完成哪些动作？

**推荐答案：** 完整版：校验 + 对象验收 + 正式发布 + ES + RAG + Feed 失效 + 计数初始化 + 推荐事件 + 失败重试。

**最终选择：** 完整版。

**Pipeline 动作：**

| 阶段 | 动作 | 目的 |
|---|---|---|
| 1 | 创建 `publish_attempt`，状态 `draft -> publishing` | 防重复发布、记录发布批次、支持失败重试 |
| 2 | 校验作者权限、帖子状态、标题、标签、正文对象、对象 Key | 防止脏数据进入正式发布 |
| 3 | 校验对象存在、大小、ETag、SHA256、Content-Type | 确认前端直传内容真实可用 |
| 4 | 拉取正文并解析纯文本、首图、摘要候选、字数、标题结构 | 给搜索、RAG、推荐、Feed 使用 |
| 5 | 内容安全/审核预留 | 保留 `rejected` 和审核扩展点 |
| 6 | 原子更新帖子为 `published`，写入 `publish_time` 和 `attempt_id` | 形成内容上线事实点 |
| 7 | 写入/更新 ES 文档 | 保证发布后可搜索 |
| 8 | 对公开已发布知文做 RAG 预索引 | 避免首次问答等待索引 |
| 9 | 失效首页 Feed、作者发布列表、详情缓存 | 避免新旧内容不一致 |
| 10 | 初始化帖子维度计数结构 | 避免首读大量重建 |
| 11 | 投递 `content_published` 推荐事件 | 给推荐、热点、相似内容模块积累候选 |
| 12 | 失败记录、重试、补偿 | 避免卡死在 `publishing` |

**状态机：** `draft -> publishing -> published`，`draft -> publishing -> publish_failed`，`publishing -> rejected`，`published -> deleted`。

## Q10. 发布 Pipeline 失败边界

**问题：** 发布 pipeline 中，哪些失败会阻止帖子正式发布？

**推荐答案：** 关键动作失败阻止发布；派生动作失败不回滚已发布内容，只进入重试/补偿。

**最终选择：** 关键失败阻止发布，派生失败只重试补偿。

**规则：**

| 类型 | 动作 | 失败结果 |
|---|---|---|
| 关键动作 | 权限/状态校验、对象验收、正文可读性、内容安全硬规则、正式发布 DB 更新 | 状态进入 `publish_failed` 或 `rejected`，用户需要重试或修改 |
| 派生动作 | ES 索引、RAG 预索引、Feed 失效、计数初始化、推荐事件 | 帖子保持 `published`，任务记录失败并重试，数据对齐服务兜底修复 |

**原因：** ES、RAG、缓存、推荐属于外部或派生依赖，不应成为用户发布成功的强阻塞条件。真正阻塞发布的是内容本身能否安全、完整地上线。

## Q11. RBAC 是否保留

**问题：** 当前 `zhiguang` 没有真实角色体系，是否仍有必要在本轮补 RBAC？

**推荐答案：** 如果当前没有管理端、审核台、运营后台或多角色协作场景，RBAC 不应作为本轮优先事项。

**最终选择：** 本轮不做 RBAC。

**原因：** 当前项目的主要目标是内容社区能力补齐和异步架构增强。没有明确管理端和多角色业务时引入 RBAC，会增加表结构、JWT、权限校验和测试复杂度，但短期业务收益不高。

**影响：** 原计划中的 `add-rbac-and-auth-hardening` 不再覆盖 RBAC。后续若保留该 change，应重命名或缩小为认证强化，例如真实短信/邮件、验证码风控、登录审计增强等。

## Q12. 真实短信/邮件是否保留

**问题：** RBAC 去掉后，真实短信/邮件服务还要不要作为本轮 OpenSpec change？

**推荐答案：** 不单独作为核心 change，只作为认证模块的小型增强任务保留。

**最终选择：** 不要，移出本轮。

**原因：** 当前验证码已有 Redis 存储、发送间隔、日限额、尝试次数和日志发送器。真实短信/邮件属于供应商集成，不是内容平台核心能力，本轮优先补评论、发布 pipeline、对账、推荐和基础设施。

## 当前保留范围

| Change ID | 要引入的能力 | 状态 |
|---|---|---|
| `add-comment-system` | 二级评论、评论点赞、评论计数、软删、异步写入 Kafka、批量落库、pending 状态 | 保留 |
| `eventize-publish-pipeline` | 发布尝试、发布状态机、对象验收、内容解析、ES/RAG/Feed/计数/推荐派生任务、失败重试 | 保留 |
| `add-data-reconciliation` | 点赞、收藏、关注、评论、搜索索引、发布派生任务的数据对齐、补偿、重试、异常记录 | 保留 |
| `add-recommendation-and-follow-feed` | 个性化推荐、热点推荐、相似内容、关注流推拉结合、大 V pull、活跃粉丝优先推送 | 保留 |
| `add-platform-infrastructure` | 多云对象存储策略、独立 ID 服务、短文本 KV/宽表存储 | 保留 |
| `split-to-microservices` | 微服务拆分、Gateway、Nacos、服务边界 | 仅后续演进，不作为第一批落地 |
| RBAC | 角色、权限、用户角色关系、权限校验 | 移出本轮 |
| 真实短信/邮件 | 短信/邮件供应商接入 | 移出本轮 |
| RabbitMQ/RocketMQ | 新消息中间件 | 不引入，复用 Kafka |

## Q13. 平台基础设施落地范围

**问题：** `add-platform-infrastructure` 里的多云对象存储、独立 ID 服务、短文本 KV/宽表存储要不要都在本轮真实落地？

**推荐答案：** 独立 ID 服务和多云对象存储策略本轮落地；短文本 KV/宽表先做抽象和表结构预留，不真实引入 Cassandra/RocksDB。

**最终选择：** 本轮引入 Cassandra。

**影响：** `add-platform-infrastructure` 将包含 Cassandra 本地环境、连接配置、短文本存储抽象、表设计、写入/读取链路、失败降级和数据补偿任务。评论系统和发布 pipeline 的文本处理需要明确哪些内容进入 Cassandra，哪些继续保留在 MySQL 或对象存储。

## Q14. Cassandra 与 ES 的职责边界

**问题：** 发布内容的图片视频等存 OSS/MinIO/S3，文字内容存 Cassandra 或 ES 时，两者在该场景的差别是什么？最终文字内容事实源选谁？

**推荐答案：** Cassandra 作为文字正文和评论正文的事实存储；ES 只作为可重建的搜索索引；向量库只作为可重建的 RAG 索引。

**最终选择：** 文字正文/评论正文进 Cassandra，OSS 只存媒体附件，ES/向量库做派生索引。

**职责分工：**

| 数据 | 事实存储 | 派生存储 |
|---|---|---|
| 图片、视频、附件 | OSS/MinIO/S3 | CDN 或附件元信息 |
| 发布正文文字 | Cassandra | ES 搜索索引、向量库 RAG 索引 |
| 评论/回复正文 | Cassandra | 可选 ES 评论搜索索引 |
| 知文标题、标签、状态、作者、可见性 | MySQL | ES 搜索索引 |
| 计数、互动状态 | Redis/事件/SDS | ES 中只放排序快照字段 |

**原因：** ES 不应作为唯一事实源。搜索索引可能重建、删除、调整 mapping 或同步失败；正文需要一个可按 ID 稳定读取、可驱动 ES/RAG 重建的事实存储。

## Q15. Cassandra 第一版查询模型

**问题：** Cassandra 第一版查询模型怎么定？

**推荐答案：** Cassandra 只负责按 ID 读取正文/评论正文，不承担 Feed、作者列表、搜索、关系分页这类列表查询。列表仍由 MySQL/Redis/ES 负责。

**最终选择：** Cassandra 只按 ID 读正文，列表查询仍走 MySQL/Redis/ES。

**表模型：**

| 表 | 查询用途 |
|---|---|
| `post_text_by_post_id` | 根据 `post_id` 读取发布正文文字、正文版本、hash |
| `comment_text_by_comment_id` | 根据 `comment_id` 批量读取评论正文 |
| 可选 `text_write_log_by_day` | 写入审计/补偿扫描，不做用户查询 |

**原因：** Cassandra 必须按查询模式建表。第一版只让 Cassandra 做正文事实源，可以避免过早引入作者维度列表、时间线、评论分页等多张反范式表。评论分页先查 MySQL 元数据得到 `comment_id` 列表，再批量读取 Cassandra 正文。

## Q16. 独立 ID 服务落地形态

**问题：** 独立 ID 服务第一版怎么落地？

**推荐答案：** 先做单体内独立 ID 模块/接口，不启动独立进程，但设计成未来可拆服务。

**最终选择：** 接入 Leaf，支持号段模式 + Snowflake。

**影响：** 本轮需要引入 Leaf 风格号段表、ID 业务标签、双 Buffer 号段加载、Snowflake 配置与时钟回拨处理。`add-platform-infrastructure` 的 tasks 需要覆盖数据库初始化、ID 模式路由、压测和故障降级。

## Q17. Leaf 模式路由

**问题：** Leaf 的 Segment 和 Snowflake 两种模式分别给哪些实体用？

**推荐答案：** 业务实体主键用 Snowflake，强顺序/可控号段场景用 Segment。

**最终选择：** 业务实体 Snowflake，后台任务/审计 Segment。

**路由：**

| ID 类型 | 模式 | 理由 |
|---|---|---|
| `post_id` | Snowflake | 当前已有雪花 ID，时间有序，适合内容主键 |
| `comment_id` | Snowflake | 高频生成，适合分布式并发 |
| `pending_comment_id` | Snowflake | 需要快速返回前端 |
| `publish_attempt_id` | Snowflake | 时间有序，便于排查 |
| `reconciliation_task_id` | Segment | 后台任务量较低，可控递增便于运维 |
| `outbox_id` | Snowflake | 事件链路高并发、时间有序 |
| `user_id` | 暂不改 | 当前自增，没必要本轮改用户 ID |

**Segment 适用范围：** `reconciliation_task`、`admin_operation`、`audit_log` 等低频后台记录。

## Q18. 多云对象存储范围

**问题：** 多云对象存储第一版支持哪些 provider？

**推荐答案：** 首版支持 `minio` 和 `s3-compatible` 两类，不直接接阿里云 OSS SDK。

**最终选择：** 只保留当前 MinIO，不做多云策略。

**原因：** 本轮已经引入评论、发布 pipeline、数据对齐、推荐、Cassandra 和 Leaf ID。继续扩展多云对象存储会增加 provider 抽象、签名差异和测试矩阵，但短期业务收益不高。

**影响：** `add-platform-infrastructure` 不再覆盖多云对象存储。对象存储仍保留当前 MinIO 能力，并服务于媒体附件上传。

## Q19. 推荐系统首版方案

**问题：** 推荐系统第一版采用自研轻量推荐、直接接入 Gorse，还是接入训练型推荐框架？

**推荐答案：** 自研推荐 Pipeline + 可选 Gorse 候选源。

**最终选择：** 先使用 Gorse，但封装一层推荐系统 Adapter，后续替换 Gorse 时只切换 Adapter 实现。

**原因：** Gorse 能较快补齐热门、相似、协同过滤和离线推荐能力；Adapter 能隔离外部推荐服务协议，避免业务层直接依赖 Gorse API。

**影响：** `add-recommendation-and-follow-feed` 需要定义 `RecommendationEngine` 接口、`GorseRecommendationAdapter` 实现、候选召回结果模型、失败降级策略，以及后续替换为自研/训练型推荐系统时的扩展点。

## Q20. 推荐 Adapter 职责边界

**问题：** 推荐 Adapter 的职责边界是什么？

**推荐答案：** Adapter 只负责返回候选内容 ID 和基础分数，不直接组装 Feed 卡片，也不决定最终混排。

**最终选择：** Adapter 返回候选 ID + score + reason，本地完成补全、过滤、混排。

**链路：**

```text
Gorse / 未来推荐引擎
  -> RecommendationEngine 返回 candidateIds + score + reason
  -> 本地 Hydration 补齐帖子详情、作者、计数、liked/faved
  -> 本地过滤可见性/拉黑/已删除
  -> 本地混排关注流、热点、推荐候选
  -> 返回 Feed
```

**原因：** 推荐引擎不应绑定业务展示模型。只返回候选 ID、分数和原因，可以让本地系统继续掌握权限过滤、缓存、计数、展示结构和后续混排策略。

## Q21. 关注流与推荐 Adapter 的关系

**问题：** 关注流要不要也走 Gorse/Recommendation Adapter？

**推荐答案：** 关注流不走 Gorse，作为本地确定性 Feed 源；推荐 Adapter 只负责推荐候选。

**最终选择：** 关注流本地实现，Gorse 只做推荐候选。

**分工：**

| Feed 源 | 实现 |
|---|---|
| 关注流 | 本地 fanout push/pull |
| 热点内容 | 可本地计算，也可作为 Gorse fallback |
| 相似内容 | 优先 Gorse Adapter |
| 个性化推荐 | Gorse Adapter |
| 最终首页 | 本地混排关注流 + 推荐候选 + 热点兜底 |

**原因：** 关注流是强关系场景，语义是“我关注的人发了什么”，应该可解释、可控、可补偿。Gorse 更适合个人推荐、相似内容和热门候选。

## Q22. 关注流推拉模型

**问题：** 关注流推拉模型的阈值怎么定？

**推荐答案：** 按作者粉丝数划分：普通作者 push，大 V pull，超级大 V 只进入 pull + 热点候选，并对活跃粉丝优先 push。

**最终选择：** 普通作者 push，大 V pull，超级大 V 只 pull/推荐，活跃粉丝优先。

**阈值：**

| 作者类型 | 粉丝数阈值 | 处理方式 |
|---|---:|---|
| 普通作者 | `< 10,000` | 发布后 fanout push 到粉丝收件箱 Redis ZSET |
| 大 V | `10,000 - 500,000` | 不全量 push；用户拉取时按关注的大 V 列表 pull 最近内容 |
| 超级大 V | `>= 500,000` | 只进入 pull 源和热点/推荐候选，不做粉丝收件箱写入 |
| 活跃粉丝 | 最近 30 天活跃 | 可对活跃粉丝优先 push，非活跃用户 pull |

**数据结构：**

| Key | 用途 |
|---|---|
| `feed:inbox:{userId}` | 用户关注流收件箱，ZSET(postId, publishTime) |
| `feed:author:posts:{authorId}` | 作者最近发布内容，ZSET(postId, publishTime) |
| `feed:active-followers:{authorId}` | 可选，活跃粉丝集合 |

## Q23. 数据对齐/补偿覆盖范围

**问题：** 数据对齐/补偿服务第一版覆盖哪些对象？

**推荐答案：** 覆盖所有“事实源 -> 派生源”的链路，但按优先级分层实现。

**最终选择：** 覆盖全部事实源到派生源链路，分层实现。

**覆盖范围：**

| 领域 | 事实源 | 派生源 | 对齐内容 |
|---|---|---|---|
| 点赞/收藏计数 | Redis 位图事实 | SDS 计数、Feed 展示计数、ES 排序计数字段 | 计数重建、ES 计数刷新 |
| 关注关系 | MySQL `following` | `follower` 伪从、Redis 关系列表、用户计数 | 粉丝表修复、列表缓存修复、关注/粉丝计数修复 |
| 评论 | MySQL 评论元数据 + Cassandra 正文 | 评论数、回复数、评论点赞数、评论列表缓存 | 评论数重算、缺正文检测、缓存修复 |
| 发布 pipeline | MySQL 帖子状态 + Cassandra 正文 | ES、RAG、Feed 缓存、推荐事件 | 已发布但未索引、未预索引、未入推荐的补偿 |
| 关注流 | MySQL 关注关系 + 已发布帖子 | Redis inbox、author posts | inbox 缺失补推、过期清理 |
| 推荐 | 行为事件事实表/日志 | Gorse 用户、物品、反馈数据 | Gorse 反馈补投、物品元数据补投 |

**执行原则：** 不做全量实时强一致，采用定时扫描 + 分片任务 + 差异检测 + 修复任务 + 失败记录 + 手动重跑。

## Q24. 数据对齐任务执行模型

**问题：** 数据对齐任务怎么触发和执行？

**推荐答案：** 同时支持定时任务、事件失败补偿、手动重跑；执行模型用任务表 + 分片 + 状态机 + Kafka 重试。

**最终选择：** 定时 + 失败补偿 + 手动重跑，任务表状态机执行。

**触发方式：**

| 触发方式 | 用途 |
|---|---|
| 定时任务 | 每小时/每日扫描关键数据差异 |
| 事件失败补偿 | 发布 pipeline、Gorse 投递、ES/RAG 索引失败后生成补偿任务 |
| 手动重跑 | 对指定 post/user/comment/task 分片重跑 |
| 启动修复 | 服务启动后扫描卡在 `publishing`、`processing` 的任务 |

**任务状态机：**

```text
pending -> running -> succeeded
pending -> running -> failed -> retrying -> succeeded
pending -> running -> failed -> dead
```

**任务表：**

| 表 | 作用 |
|---|---|
| `reconciliation_task` | 记录任务类型、目标 ID、分片、状态、失败原因、重试次数 |
| `reconciliation_checkpoint` | 记录每类扫描任务的进度 |
| `reconciliation_error_log` | 记录不可自动修复的问题 |

## Q25. OpenSpec Changes 最终拆分

**问题：** OpenSpec changes 最终怎么拆？

**推荐答案：** 按能力边界拆分为基础设施、评论、发布、推荐、对账和后续微服务演进。

**最终选择：** 按表格拆分。纠正：这是 6 个落地 changes + 1 个后续演进 change，不是 5 个落地 changes。

**拆分：**

| Change ID | 内容 | 类型 |
|---|---|---|
| `add-cassandra-text-storage` | Cassandra 作为发布文字正文、评论正文事实源 | 落地 |
| `add-leaf-id-service` | Leaf Segment + Snowflake ID 能力 | 落地 |
| `add-comment-system` | 异步评论、二级评论、评论点赞、评论计数、软删 | 落地 |
| `eventize-publish-pipeline` | 发布状态机、`publish_attempt`、对象/正文验收、ES/RAG/Feed/推荐派生任务 | 落地 |
| `add-recommendation-and-follow-feed` | Gorse Adapter、推荐候选、关注流推拉、首页混排 | 落地 |
| `add-data-reconciliation` | 全链路对账、补偿任务、手动重跑、失败状态机 | 落地 |
| `split-to-microservices` | Gateway、Nacos、服务边界拆分 | 后续演进，不作为第一批实现 |

**依赖顺序：** Cassandra/Leaf -> 评论 -> 发布 Pipeline -> 推荐/关注流 -> 数据对齐；微服务拆分独立作为后续演进。
