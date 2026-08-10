# ZhiGuang 后端系统架构契约（zhiguang_be）

| 字段 | 值 |
|------|-----|
| **contract_version** | `0.4.0` |
| **status** | **active**（本文档首次建立；后续架构/契约变更必须同步修改本文并升版本） |
| **updated** | 2026-08-10 |
| **scope** | 单体应用 `com.tongji`（`src/main/java/com/tongji`，390 个 Java 文件）的运行时边界、模块分层、HTTP/事件/存储契约、状态机、配置键、错误码；`db/schema.sql`、`db/cassandra/init.cql`、`src/main/resources/application.yml`、`docker-compose.yml` 承载的外部系统边界 |
| **roadmap** | OpenSpec 变更与执行顺序见 [`openspec/changes/execution-order.md`](../../openspec/changes/execution-order.md)；本仓为单体演进、微服务拆分仅作约束（见该文件阶段 0） |
| **out of scope for this doc** | 前端 `zhiguang_fe/`（当前为空占位目录）、`loadtest/` 压测方案细节、各业务请求/响应 JSON 逐字段表（以代码 DTO 为准） |

---

## 0. 效力与变更

1. **效力**：实现、评审、重构以本文为准。冲突代码不得合并，除非先改契约。
2. **变更**：改决策摘要/分层/依赖/技术栈/模块边界/事件或存储契约 → 升 `contract_version`。
3. **本文所有描述以实际代码为支撑**：每条事实可追溯到 `src/main/java/com/tongji/**`、`src/main/resources/**`、`db/**`、`pom.xml`、`application.yml` 中的具体文件。标注 `[INFERENCE]` 的条目为代码边界推断，非直接观测。
4. **事实缺口登记**：代码声明但未接线的配置/类型列入 §10，修代码或修配置时须同步更新该清单。

---

## 1. 决策摘要（Binding）

| ID | 主题 | 决定（代码证据） | 禁止 |
|----|------|------|------|
| D1 | 进程拓扑 | **单体 Spring Boot 3.5.10 / Java 21**（`pom.xml` parent `spring-boot-starter-parent:3.5.10`、`java.version=21`），单一应用 `ZhiGuangApplication`（`ZhiGuangApplication.java`，`@SpringBootApplication` + `@EnableScheduling`），包根 `com.tongji` | 引入第二个应用/服务进程承载本仓领域逻辑 |
| D2 | 模块分层 | 每域模块 = `api`（Controller+DTO）→ `service`(+`impl`) → `manager`/`mapper`/`event`/`consumer`/`model`；跨模块调用走 **service 接口** 或 **Kafka 事件**（详见 §3.3 模块依赖） | controller 直连 mapper；跨模块直读他人表 |
| D3 | HTTP 错误契约 | 全局 `@RestControllerAdvice`（`common/web/GlobalExceptionHandler.java`）：`BusinessException`→400 + `{code,message}`；`@Valid` 失败→400 + `BAD_REQUEST`；无匹配资源→404 + `{code:"NOT_FOUND",message:"请求资源不存在"}`；兜底 `Exception`→500 + `{code:"INTERNAL_ERROR", message:"服务异常，请稍后重试"}`；业务码枚举 `common/exception/ErrorCode.java`（27 值） | 各 controller 自造错误体 |
| D4 | ID 生成 | 统一 `IdService.nextId(IdNamespace)`（`common/id/`）：**Snowflake** 为默认（41+5+5+12 位，EPOCH 2024-01-01，时钟回拨抛 `ClockBackwardException`），推广历史 command 与保证金分别使用 `PROMOTION_COMMAND`、`PROMOTION_ESCROW`；**Segment**（`leaf_alloc` 表双缓冲，50% 阈值预加载）仅用于 `reconciliation_task/admin_operation/audit_log`；命名空间见 `IdNamespace.java` | 各模块自造随机/自增 ID |
| D5 | 异步一致性 | **outbox 表 + Canal CDC + Kafka `canal-outbox` 主题** 为跨模块事件总线（`CanalKafkaBridge.java` 监听 `zhiguang.outbox`，仅转发 INSERT/UPDATE 的 payload 列）；业务事务内写 outbox，事务提交后被转发；**at-least-once + 消费端幂等** | 业务事务内直发 Kafka |
| D6 | 存储分工 | **MySQL**：长期事实/账务（用户、帖子、评论、发布尝试、outbox、钱包总余额、推广保证金授权与投影、对账、通知、关系）；**Redis**：推广窗口运行期间的竞价状态、顺序、排名、已授权保证金占用与 Stream 决策日志实时权威，以及计数 SDS/位图事实、缓存、分布式协调（singleflight/锁）；推广 Stream 在 MySQL checkpoint 推进后安全裁剪并保留最近 100000 条；**Kafka**：非推广域异步事件总线；**Cassandra**：长文本正文与关注流时间线；**Elasticsearch**：搜索；**MinIO**：对象。见 §5 | 将 Redis 余额占用扩展为可超出 MySQL 预授权总额的账务事实；未投影事件被裁剪；正文大字段进 MySQL |
| D7 | 计数模型 | 实体计数（like/fav）三层：**位图分片事实层**（`bm:*`，32768 位/分片）+ **Kafka 事件聚合桶**（`counter-events` → `agg:v1:*`，每秒折叠 SDS）+ **SDS 固定结构**（`cnt:v1:*`，5×uint32 大端）；用户计数 `ucnt:{userId}` 同 SDS 布局（`counter/schema/*.java`） | 计数直接 INCR 单一计数器键 |
| D8 | 发布语义 | **202 Accepted 只表示 attempt 被受理**：`POST /knowposts/{id}/publish` 恒 202 + `publishAttemptId`；`know_posts.status` 状态机 `draft→publishing→published / publish_failed / rejected / deleted`，全部守卫 UPDATE（`KnowPostMapper.xml`）；`publish_attempt` 独立状态机 + 5 分钟卡死恢复（`PublishAttemptService.java`） | 同步发布返回 200 表示已发布；无守卫状态流转 |
| D9 | 钱包 | 三态余额 `available/held/escrowed` + **只追加流水** + `(owner_user_id, business_ref)` 幂等 + `wallet_business_ref` 全局 claim 串行化；托管六态状态机不变。推广竞价新增唯一写边界 `POST /api/v1/promotions/campaigns/{id}/escrow`：只在出价前增加 MySQL `held` 与 `promotion_bid_escrow.authorized_amount`，不得进入单条竞价决策路径 | 余额绝对值覆盖写；删改流水；在有序竞价消费者逐条写钱包 |
| D10 | 推广竞价 | 窗口式 slot 竞价与 GSP 结算不变；**Elia 热路径**：保证金预授权 MySQL 事务提交后同步投影 Redis，成功才返回可竞价；STOMP 与原生 WebSocket 共用收单协议，所有请求直接执行同槽 Redis Lua。Lua 使用 Redis TIME，按 `>= endAt` 关窗，先校验 key 类型和 `state.decisionVersion == Stream last id`，再原子完成幂等、reserve、单 campaign 递增、授权额度、状态/排名/currentHold 更新、显式 `<decisionVersion>-0` XADD 和 PUBLISH；接受或拒绝结果直接返回原连接，不再返回 `PUBLISHED`。拒绝不推进版本、不写 Stream；相同 commandId+requestHash 重放原结果，hash 冲突稳定拒绝。Pub/Sub 仅作唤醒，fanout 与 MySQL 投影只信 Stream；窗口初始化后登记 `promotion:auction:active-streams`，启动及 2 秒 sweep 只遍历该集合，窗口 SETTLED 且 checkpoint 追平后移除，MySQL 中未完成的 REDIS_STREAM 窗口负责恢复登记，禁止扫描 Redis 全键空间。checkpoint 成功后仅裁剪已投影事件并保留最近 100000 条。Redis 使用 AOF everysec，故障时返回可重试 `PROMOTION_AUCTION_PAUSED`，不得降级到 MySQL 裁决；不承诺金融级零丢失。窗口不可变 decision_path 保证 clean cutover，禁止同一窗口双写或双裁决。 | 网关判定竞价接受；绕过 requestHash 幂等；使用应用时钟裁决终场；先修改状态再校验 Stream/version；把 Pub/Sub 当事件事实；全库 SCAN/KEYS 恢复推广 Stream；裁剪未投影事件；Redis 故障时访问 MySQL 接受竞价；返回 `PUBLISHED` 等待二次 outcome；推广竞价使用 RocketMQ/Kafka；同一窗口双路径裁决 |
| D11 | 对账补偿 | 独立对账模块：任务表 + checkpoint 扫描 + 10 个 Reconciler + Redisson 锁执行器（指数退避、卡死恢复），事件消费失败与周期扫描双来源建任务（`reconciliation/**`） | 事件丢失不补；手工修数 |
| D12 | 通知 | 幂等键 `notifications.event_key` 唯一 + 捕获 `DuplicateKeyException`；点赞通知 5 分钟 Redis 窗口聚合，以 `notif:like:bucket:due` ZSet 按窗口结束时间登记，30 秒定时限量读取到期成员并落库 | 同事件重复落多条；在共享 Redis 使用 `KEYS`/全库 `SCAN` 查找到期桶 |
| D13 | 场景开关 | 外部系统默认关闭、显式开启：`canal.enabled=false`、`recommendation.gorse.enabled=false`、`promotion.bprime.enabled=false`、`moderation.llm.enabled=false`、`counter.rebuild.enabled=false`、`feed.home.mixed-enabled=false`（`application.yml`）；bprime 关闭时保证金事务在冻结资金前暂停，Redis Stream worker 与实时推送不得执行 | 生产依赖未开启的能力 |
| D14 | 大整数序列化 | 所有 Snowflake ID（>2^53）出参一律 **String 序列化** 防 JS 精度丢失（DTO 注释与实现：`NotificationItemResponse`、`KnowPostDraftCreateResponse`、`ModerationReportResponse`、`PromotionRankingItem` 等） | long 直出到 JSON |
| D15 | 幂等三支柱 | ① 请求幂等：评论/发布使用唯一键；推广出价以 `(window,user,idempotencyKey)` 生成确定性 commandId，同槽分钟桶 hash 以 commandId field 紧凑保存 `requestHash+decision`，当前桶写入、TTL 范围内各桶回放，默认保留 10 分钟且桶整体过期；保证金授权由 `promotion_bid_escrow uk(window,campaign)` 与钱包 businessRef 判等；② 事件幂等：消费端去重键/唯一键与推广 checkpoint；③ 对账兜底：事件失败建对账任务（§6.4） | 依赖 at-most-once 投递；为每个高频推广请求创建独立 Redis key |
| D16 | 技术栈绑定 | Spring Boot 3.5.10 / Java 21 / MyBatis 3.0.3 + MySQL 8.4 / Redis 7.4 AOF everysec + Redisson 3.52 / Kafka（spring-kafka，非推广域）/ Cassandra 4.1 / ES（客户端 8.12.2，容器 9.2.1+IK）/ MinIO / Spring AI 1.1.2（DashScope，审核）/ Sentinel core 1.8.10（规则外部下发）/ Caffeine 3.1.8 / Canal client 1.1.8 / WebSocket STOMP（推广实时） | 未 ADR 换主框架；推广重新接入 broker |
| D17 | 推广实时性能 | 公共合并事件显式携带 `[fromDecisionVersion,toDecisionVersion]`，客户端以该区间是否覆盖本地 `decisionVersion + 1` 判断真实缺口；`eventVersion` 仅保留为 `toDecisionVersion` 兼容字段，不得按逐消息连续序号解释。原生 WebSocket 维护 `windowId -> SessionWriter set` 房间索引，公共事件不得遍历全体连接；每连接仍由专用有界 outbound executor 执行单写泵，私有 ACK 使用 FIFO，公共状态使用可覆盖槽。公共合并周期在配置的 100–250ms 范围内按房间订阅数与待写公共槽占比自适应，同 campaign 覆盖不得触发 snapshot，只有区间缺口才恢复。网关使用有界本地 route L1，Redis route 仍是冷启动与跨实例事实；单标热路径缓存命中后只执行一次 Redis Lua。裁决执行器使用可配置的固定线程数与有界队列，过载明确返回 `UNAVAILABLE`。Lua 以 Stream `last-generated-id` 校验版本、减少重复 TTL 写，并用同槽 wakeup key 将多次 XADD 合并为一次 Pub/Sub 提示；Stream 始终是 fanout 与投影事实。 | 把合并后的 `eventVersion` 当逐消息连续序号；公共事件遍历全体连接；每标读取 Redis route；无界裁决并发或队列；把 Pub/Sub 当逐事件事实 |

### 1.1 已废弃 / 未采纳

| 项 | 现状 |
|------|------|
| 微服务拆分 | `openspec/changes/split-to-microservices` 仅作**架构约束**（不跨边界 JOIN、外部依赖走 Adapter、跨边界写走事件、ID 走 namespace），不落地拆分（`execution-order.md` 阶段 0） |
| 双写/直发消息 | 事务内直发 Kafka 的模式不存在；全部经 outbox（§6.1） |

---

## 2. 系统是什么 / 不是什么

### 2.1 是什么

- **知光（ZhiGuang）内容社区后端单体**：注册登录（验证码/密码）、资料、知文（图文帖子）发布与 Feed、评论、点赞/收藏计数、关注关系、搜索、通知、内容审核（LLM 可选）、钱包（三态余额+托管+内容奖励）、推广位竞价（含 bprime 异步决策链路）、推荐（Gorse 可选 + 关注流）、对账补偿。
- **事件驱动的一致性**：跨模块状态同步（follower 镜像、ES 索引、Gorse 反馈、关注流扇出、通知、审核推进）全部经 `outbox → Canal → canal-outbox → 各消费组` 或独立事件主题（§6）。
- **可观测的补偿体系**：对账模块（§7.12）以任务/扫描/修复器保证派生数据最终一致。

### 2.2 不是什么

- 不是微服务/分布式网关架构（单体演进，`execution-order.md` 阶段 0）；
- 不依赖 Gorse/Canal/moderation-LLM/bprime 默认可用（默认关闭，§D13）；
- 不使用 LangChain/独立 AI 内核：LLM 仅经 Spring AI 用于内容审核（`moderation/service/impl/SpringAi*ModerationLlmClient.java`）；
- `zhiguang_fe/` 当前为空，前端契约不在本仓。

---

## 3. 逻辑架构

### 3.1 总览

```text
Clients (HTTP / WebSocket STOMP)
        │
        ▼
Spring Security (OAuth2 Resource Server JWT, RS256) ── permitAll 白名单 §4.3
        │
        ▼
Controllers (com.tongji.<module>.api)
        │
        ▼
Services / Managers (事务边界) ──→ Mappers (MyBatis) ──→ MySQL
        │                              │
        │                              ├─→ Cassandra（正文 / 关注流时间线）
        │                              ├─→ Redis（计数 SDS / 缓存 / 锁 / singleflight / 热状态）
        │                              ├─→ Elasticsearch（zhiguang_content_index）
        │                              └─→ MinIO（对象预签名）
        │
        ├─→ outbox 表（同事务）── Canal CDC ──→ Kafka canal-outbox ──→ 各消费组
        ├─→ Kafka：comment-write / comment-feedback / counter-events
        ├─→ Redis Lua + Stream：promotion auction decisions
        └─→ 失败补偿 ──→ reconciliation_task 表 ──→ Reconciler 执行器
```

### 3.2 模块地图（`src/main/java/com/tongji/`）

| 模块 | 职责一句话 | 核心入口 |
|------|-----------|---------|
| `auth` | 验证码/注册/登录/刷新/登出/重置 + JWT + 登录审计 | `AuthController` |
| `user` | 用户实体持久化 | `UserService` |
| `profile` | 资料 PATCH / 头像，更新发 `user_profile_updated` outbox 事件 | `ProfileController` |
| `knowpost` | 知文草稿/发布（attempt 状态机）/Feed/详情/置顶/可见性/软删 | `KnowPostController` |
| `comment` | 异步评论管道：pending→outbox→comment-write→落库+Cassandra 正文+feedback | `CommentController` |
| `counter` | 实体计数（like/fav/comment）与用户计数（SDS） | `ActionController`/`CounterController` |
| `relation` | 关注/取关（following 同步 + follower 事件最终一致）+ 关注列表 | `RelationController` |
| `moderation` | 举报→LLM 审核→处置/通知（可关） | `ModerationReportController` |
| `notification` | 评论/关注/点赞通知（点赞窗口聚合） | `NotificationController` |
| `promotion` | 推广活动/竞价窗口/出价命令/结算/位分配/快照（含 `bprime` 异步链路） | `PromotionController` |
| `recommendation` | Gorse 推荐 + 首页混排 + 关注流（Cassandra inbox/author_feed 扇出） | 无 Controller（被 knowpost 调用） |
| `search` | ES 搜索/联想 + canal-outbox 增量索引 | `SearchController` |
| `storage` | MinIO 预签名直传 + Cassandra 正文读写（`text` 子包） | `StorageController` |
| `wallet` | 三态余额账务 + 托管 + 注册赠币 + 内容奖励（只读 HTTP） | `WalletController` |
| `reconciliation` | 对账任务/扫描/修复器/结算补偿分析 | `ReconciliationController` |
| `common` | 异常/ID/Sentinel 守卫/singleflight/缓存/热键/outbox 解析 | — |
| `cache` | Caffeine L1 缓存 Bean + 热点检测 | — |
| `config` | ES/Redisson/RestTemplate/线程池配置 | — |

### 3.3 模块依赖方向（代码事实）

- **进程内 service 调用**（跨模块）：`knowpost → storage.text / counter / wallet(ContentReward) / minio / search.index`；`comment → storage.text / counter / wallet(ContentReward)`；`relation → counter(UserCounterService)`；`moderation → knowpost.mapper / comment.mapper / notification / singleflight`；`promotion → wallet / knowpost.feed / recommendation(HomeFeedMixing) / search`；`recommendation → relation.mapper / knowpost.mapper / counter`；`search → counter / promotion(allocation) / knowpost(dto)`；`auth → wallet(WalletRegistrationGrantService)`；`reconciliation → 全部派生源`。
- **事件方向**（Kafka）：各模块生产者 → `canal-outbox` / `comment-write` / `comment-feedback` / `counter-events` / `zhiguang.promotion.auction.decisions.v2`；消费组见 §6.2。
- **禁止反向**：`common` 不依赖任何业务模块；`storage.text` 只依赖 Cassandra；领域 mapper 不互相引用（跨模块读经 service 或自有 SQL）。

### 3.4 线程池（`config/ThreadPoolConfig.java`，全部 `waitForTasksToCompleteOnShutdown=true`）

| Bean | core/max | 队列 | 拒绝策略 | 用途 |
|------|----------|------|----------|------|
| `taskExecutor` | 10/50 | 200 | CallerRuns | 通用异步（feed 扇出等） |
| `publishExecutor` | 8/16 | 100 | CallerRuns | 发布流水线 `runPublish` |
| `relationEventExecutor` | 4/8 | 200 | CallerRuns | 关系事件处理 |
| `canalOutboxExecutor` | 2/4 | 50 | **Abort** | Canal 桥接消息转投 |
| `reconciliationExecutor` | 2/4 | 100 | CallerRuns | 对账任务/发布派生工作 |
| `commentReadExecutor` | 8/16 | 200 | CallerRuns | 评论页 Cassandra/Counter 并行读取 |
| `commentOutboxExecutor` | 2/4 | 50 | CallerRuns | 评论 outbox future 协调 |
| `commentCacheInvalidationScheduler` | 1 | 100ms 合并窗口 | 专用单线程延迟调度，不注册为 Spring `TaskScheduler` | 评论缓存失效去重与合并 |
| `promotionBidSubmissionExecutor` | 32/32（可配置） | 16384（可配置） | **Abort** | Redis Lua 同步裁决；固定并发避免队列先于线程扩容 |
| `promotionBidWebSocketOutboundExecutor` | 32/32（可配置） | 65536（可配置） | **Abort** | 原生 WebSocket 私有 ACK 与可覆盖公共状态单写泵；跨 session 并行、单 session 串行 |
| `promotionPublicUpdateScheduler` | 2（可配置） | 100ms 基础 tick，按房间压力自适应至 250ms（可配置） | **Abort** | 按窗口合并公共增量 |

---

## 4. HTTP 契约

### 4.1 全局

- 基路径统一 `/api/v1/**`；控制器注解 `@RequestMapping("/api/v1/<domain>")`。
- 错误响应体恒为 `{"code": String, "message": String}`（`GlobalExceptionHandler.java`）；`BusinessException(ErrorCode)` → 400。
- 成功语义按模块：写操作用 200/202/204；**异步受理用 202**（评论提交、发布受理、举报受理），状态另行查询。
- 分页：评论/通知用**游标**（`cursorCreateTime+cursorCommentId`、`cursorCreatedAt+cursorId`，键集分页 SQL）；feed/钱包用**页码**（page/size，钳制上限）；关注列表支持 offset+cursor 双模式。
- **ID 一律 String 出参**（D14）：`commentId/creatorId/entityId/reportId/commandId/auctionWindowId` 等 Snowflake ID。

### 4.2 认证

- Spring Security + OAuth2 Resource Server（JWT RS256，Nimbus）：`auth/config/SecurityConfig.java`；无自定义 JWT Filter，无 Session，CSRF 关闭。
- JWT claims（`auth/token/JwtService.java`）：`iss=zhiguang`、`sub=userId`、`jti`、`token_type∈{access,refresh}`、`uid`、`nickname`（仅 access）。access TTL 15m、refresh 7d（`AuthProperties`）。
- Controller 取用户：`@AuthenticationPrincipal Jwt` + `JwtService.extractUserId`；`/me` 等端点由 JWT 决定身份，**不做可信任的用户参数**（防越权）。
- 刷新令牌：Redis 白名单 `auth:rt:{userId}:{tokenId}`（TTL 7d）+ 轮换（旧 jti 即失效）+ `revokeAll` 全端下线（`RedisRefreshTokenStore.java`）。

### 4.3 permitAll 白名单（`SecurityConfig.java`）

`/actuator/health`、`/actuator/info`、`/api/v1/knowposts/feed`、`GET /api/v1/knowposts/detail/*`、`/api/v1/auth/send-code|register|login|token/refresh|logout|password/reset`；**其余全部需 JWT**（含搜索、计数读取、钱包查询——控制器无匿名注解）。

### 4.4 CORS

- 全局：`*` 来源，GET/POST/PUT/DELETE/OPTIONS，`Authorization/Content-Type/X-Requested-With` 头，`allowCredentials=false`（`SecurityConfig.java`）。
- `profile` 叠加独立 `CorsFilter`（`allowedOriginPatterns=*`、含 PATCH、`maxAge=3600`，仅 `/api/v1/profile/**`，`profile/config/CorsConfig.java`）。

---

## 5. 存储契约

### 5.1 MySQL（`db/schema.sql` 共 27 表；Docker 初始化挂载 `docker-compose.yml` mysql volume；MyBatis `classpath*:mapper/**/*.xml`，`map-underscore-to-camel-case`）

| 域 | 表 | 关键约束/说明 |
|----|----|----|
| 账号 | `users` | `uk_phone/uk_email/uk_zg_id`；密码 BCrypt |
| 审计 | `login_logs` | `(user_id, created_at)` 索引 |
| 内容 | `know_posts` | 业务层雪花 ID；`tags/img_urls` JSON；正文只存 `content_url/object_key/etag/sha256`；状态守卫索引 |
| 发布 | `publish_attempt` | `uk(creator_id, post_id, idempotent_key)` 幂等；fallback_* 派生失败回退列 |
| 事件 | `outbox` | 总线表：`aggregate_type/aggregate_id/type/payload(JSON)`；Canal 订阅目标 |
| 评论 | `comments` / `pending_comments` | 正文**不在此表**（Cassandra）；`status` 0 活跃/1 软删；`uk(creator_id, client_request_id)` |
| 评论 outbox | `comment_outbox` | **统一事件表**：原子承载 `COMMENT_WRITE_REQUESTED/COMMENT_CREATED/COMMENT_DELETED/COMMENT_MODERATED`；DDL 同时由 `db/schema.sql` 与 `CommentOutboxSchemaInitializer` 保持一致；`state/claim_token/claim_until/next_attempt_at` 构成有界批量 dispatcher 状态机 |
| 关系 | `following` / `follower` | 双向镜像；`rel_status` 1 有效/0 取消；`uk(from,to)`/`uk(to,from)` |
| 通知 | `notifications` | `uk_notification_event_key` 幂等；聚合窗口列 |
| 审核 | `moderation_reports` | `uk(reporter, target_type, target_id)` 去重；LLM/重试/处置列 |
| 钱包 | `wallet_account` / `wallet_ledger` / `wallet_escrow` / `wallet_business_ref` | 三态余额 CHECK 非负；ledger `uk(owner, business_ref)` + `amount>0`；escrow `uk(business_ref)`；ref claim 表 PK=business_ref |
| 推广 | `promotion_campaign` / `promotion_auction_window` / `promotion_bid_escrow` / `promotion_bid` / `promotion_slot_allocation` / `promotion_projection_checkpoint` | escrow `uk(window,campaign)`，保存 `authorized_amount/current_hold/status`；bid `uk(campaign, window)` + `uk(command_id)`；window `uk(resource, start, end)`；checkpoint PK=window_id 并保存 `last_stream_id` |
| 对账 | `reconciliation_task` / `reconciliation_checkpoint` / `reconciliation_error_log` | task 活动去重：生成列 `active_dedupe_scope`（仅 pending/running 生效）+ 唯一键 |
| ID | `leaf_alloc` | `biz_tag` PK；segment 段分配 |

### 5.2 Cassandra（`db/cassandra/init.cql`，keyspace `zhiguang`，SimpleStrategy RF=1）

| 表 | 用途 | 特性 |
|----|------|------|
| `post_text_by_post_id(post_id PK, body, version, sha256, updated_at)` | 知文正文事实源 | 版本递增覆盖写（`CassandraTextStorageService.savePostText`） |
| `comment_text_by_comment_id(comment_id PK, body, version, updated_at)` | 评论正文事实源 | 同上 |
| `feed_inbox(user_id, publish_ts, content_id, author_id)` | 关注流 push 半区 | `CLUSTERING (publish_ts DESC, content_id DESC)`，TTL 30 天，TWCS 日窗口，`gc_grace_seconds=0` |
| `feed_author_feed(author_id, publish_ts, content_id)` | 关注流 pull 半区（大 V） | 同上 |

### 5.3 Elasticsearch

- 索引 `zhiguang_content_index`（`search/index/SearchIndexService.java` INDEX 常量）：文档 _id=postId，字段 `content_id/content_type/title/description/author_*/publish_time/status/tags/img_urls/is_top/body/like_count/favorite_count/view_count/title_suggest`；body 截断 4000 字。
- Mapping（`SearchIndexInitializer.java`）：IK 分词（容器镜像 `zhiguang-elasticsearch:9.2.1-ik`，`Dockerfile.elasticsearch` 安装 infini cloud analysis-ik 9.2.1）、`completion` suggester（联想）。
- 查询（`SearchServiceImpl.java`）：`multi_match` 宽召回 + `function_score` 业务加权 + highlight + `search_after` 游标 + 首屏商业位（`promotion:allocation:active:search_top_slot`）。
- 写入 `Refresh.WaitFor`；软删 = 覆盖写 `{content_id, status:"deleted"}`；启动 `count==0` 时全量回灌。
- **版本事实**：pom 客户端 `elasticsearch-java 8.12.2`，compose 服务端镜像 9.2.1 —— 客户端/服务端版本不一致，以现状运行通过为准（`pom.xml` + `docker-compose.yml`）。

### 5.4 Redis 键注册表（键模板全部为代码字面量）

| 域 | 键 | 结构 / TTL | 出处 |
|----|----|-----------|------|
| 认证 | `auth:rt:{userId}:{tokenId}` | string / refresh TTL 7d | `RedisRefreshTokenStore` |
| 认证 | `auth:code:{scene}:{identifier}` | hash {code,maxAttempts,attempts} / 5m（attempts 用尽改 30m） | `RedisVerificationCodeStore` |
| 认证 | `auth:code:last:{scene}:{identifier}` | string / 60s 发送间隔 | `VerificationService` |
| 认证 | `auth:code:count:{scene}:{identifier}:{yyyyMMdd}` | INCR / 1d 每日 10 次 | 同上 |
| 计数 | `cnt:v1:{etype}:{eid}` | SDS 5×uint32 大端（like=1,fav=2,comment=3） | `CounterKeys`/`CounterSchema` |
| 计数 | `bm:{metric}:{etype}:{eid}:{chunk}` | 位图事实（chunk=uid/32768，bit=uid%32768） | `BitmapShard` |
| 计数 | `bm:index:{metric}:{etype}:{eid}` | 分片索引（启动 SCAN 回填） | `CounterBitmapShardIndexInitializer` |
| 计数 | `agg:v1:{etype}:{eid}` / `agg:v1:dirty` | hash 聚合桶 + dirty set（每秒折叠） | `CounterAggregationConsumer` |
| 计数 | `ucnt:{userId}` / `ucnt:chk:{userId}` | 用户 SDS / 采样锁 300s | `UserCounterKeys`/`RelationController` |
| 关系 | `uf:flws:{userId}` / `uf:fans:{userId}` | zset（score=时间戳）/ 2h | `RelationEventProcessor`/`RelationServiceImpl` |
| 关系 | `dedup:rel:{type}:{from}:{to}:{id}` | SET NX 幂等 / 10m | `RelationEventProcessor` |
| 关系 | `rl:follow:{fromUserId}` | hash 令牌桶（容量100，速率1/s）Lua / 60s | `RelationManagerImpl` |
| 缓存 | `knowpost:detail:{id}:v1` | JSON 或 `"NULL"` 防穿透 / 60s±rand（热点 +20/60/120s） | `KnowPostServiceImpl` |
| 缓存 | `feed:public:ids:{size}:{hourSlot}:{page}` | list 片段 / 60–89s（`frTtl=60+rand(0..29)`） | `KnowPostFeedServiceImpl` |
| 缓存 | `feed:public:ids:{size}:{hourSlot}:{page}:hasMore` | string 软缓存 / 10–20s（满页 true 10+rand(0..10)，否则 10s） | 同上 |
| 缓存 | `feed:public:index:{postId}:{hourSlot}` | 反向索引 set（SADD+expire frTtl 60–89s，内容更新时定位受影响页） | 同上 |
| 缓存 | `feed:public:pages` | 页面键集合（仅 SADD，无 TTL/清理） | 同上 |
| 缓存 | `feed:timeline:{userId}` | TimelinePage JSON / 300s（仅默认页大小） | `FollowFeedServiceImpl` |
| 缓存 | `feed:author:{authorId}:head` | List<TimelineItem> JSON / 120s（singleflight `feed-author-head` 重建） | 同上 |
| 推广 | `promotion:allocation:active:{type}` | JSON 列表 / 300s | `PromotionAllocationCacheService` |
| 推广 | `promotion:auction:{{windowId}}:state` / `:commands:{minuteBucket}` / `:ranking` / `:campaign:{cid}` / `:escrow` / `:events` / `:pub` / `:wakeup` | 窗口内 key 使用同一 Redis Cluster hash slot；state 内含 `decisionVersion/status/reservePrice/windowEndAtEpochMs`，escrow hash 字段为 `{campaignId}:authorizedAmount/currentHold`；ranking score=`-bidAmount`，member 以前导零首次接受版本保证同价先到优先；commands 分钟桶 hash 以 commandId field 保存紧凑 `requestHash+decision`，默认 10 分钟 TTL；`:pub` 仅发送 Stream ID 唤醒 | `PromotionAuctionRedisKeys`+Lua |
| 推广 | `promotion:auction:active-streams` | set；登记需投影/恢复的 REDIS_STREAM 窗口，窗口 SETTLED 且 checkpoint 追平后移除 | `PromotionAuctionHotStateRepository`/`PromotionRedisStreamProjector` |
| 推广 | `promotion:bprime:route:{campaignId}` | 保证金授权后写入的出价路由 JSON（owner/window/post/resource/reserve/status/endAt）/ 覆盖窗口结束后的有界 TTL | `PromotionBidRouteRepository` |
| 通知 | `notif:like:event:{eventId}` | 去重 / 6h | `LikeNotificationConsumer` |
| 通知 | `notif:like:bucket:{recipient}:{etype}:{eid}:{windowStart}` / `notif:like:bucket:due` | hash 聚合桶 / 20min；ZSet member=桶 key、score=窗口结束毫秒，Flush 每批限量读取到期成员 | `LikeNotificationConsumer`/`LikeNotificationFlushJob` |
| 对账 | `recon:lock:{taskId}` | Redisson RLock | `ReconciliationTaskExecutor` |
| 单飞 | `zg:singleflight:meta:{sha256}:{key}` / `:result:` / `:owner-seq` / `stream:{key}` | hash/result/INCR 序号/Stream（XADD+XREAD BLOCK） | `RedisSingleFlightCoordinatorRepository`/`RedisSingleFlightNotificationService` |

### 5.5 MinIO（`storage/`）

- bucket `zhiguang`（compose `minio-init` 自动建 + 匿名下载）；`storage.*` 配置（endpoint/access-key/bucket/public-domain）。
- `POST /api/v1/storage/presign`：`scene=knowpost_content|knowpost_image` + `postId` 归属校验 → 返回 `objectKey/putUrl/headers/expiresIn`（`MinioStorageService`）。
- 头像：`POST /api/v1/profile/avatar` 服务端直传 `uploadAvatar`（`ProfileController` + `MinioStorageService`）。
- 正文读取回退：Cassandra 缺失时 `fallbackContentUrl` HTTP 拉取（`CassandraTextStorageService`，`RestTemplate` 5s/10s）。

---

## 6. 事件 / 消息契约

### 6.1 outbox → Canal → Kafka 总线（跨模块主总线）

1. **写**：业务事务内 `outboxMapper.insert(id, aggregateType, aggregateId, type, payloadJson)`（`OutboxMapper.xml`）；id=`IdNamespace.OUTBOX_EVENT`（Snowflake）。
2. **桥**：`CanalKafkaBridge`（`relation/outbox/CanalKafkaBridge.java`）`SmartLifecycle`，`canal.enabled` 门控；订阅 `zhiguang.outbox`（`canal.filter`），只转发 `EventType.INSERT/UPDATE`，**只取 `payload` 列**，封装 `{"table":"outbox","type":"INSERT|UPDATE","data":[{payload:...}]}` 发到 `canal-outbox`；批次 ack（至少一次）。
3. **解**：`OutboxMessageUtil.extractRows`（`common/util/`）——`table==outbox && type∈{INSERT,UPDATE} && data` 数组才返回行。
4. **事件类型注册表**（outbox `type` 字段，代码字面量）：`user_profile_updated`（profile）、`content_published` / `publish_derived_failure` / `KnowPostMetadataUpdated` / `KnowPostDeleted` / `KnowPostModerationRejected`（knowpost+moderation）、`review_requested`（moderation）、`FollowCreated` / `FollowCanceled`（relation，payload=RelationEvent JSON）、`moderation` 处置 delete 事件（`{entity:knowpost, op:delete, source:moderation}`）。

### 6.2 Kafka 主题清单（精确字符串）

| 主题 | 生产者 | 消费组 | 载荷要点 |
|------|--------|--------|----------|
| `canal-outbox` | `CanalKafkaBridge` | `relation-outbox-consumer`、`moderation-review-consumer`、`notification-follow-consumer`、`feed-timeline-consumer`、`recommendation-content-published-consumer`、`recommendation-relation-feedback-consumer`、`recommendation-user-profile-consumer`、`search-index-consumer` | 上节事件注册表 |
| `comment-write`（`comment.kafka.write-topic`，8 分区） | `CommentOutboxDispatcher` 从 `comment_outbox` 有界批量异步发送，key=`aggregateId`；成功/失败子集分别更新 | `comment-write-consumer`（初始并发 4；`@RetryableTopic` → `comment-write-dlt`） | `CommentOutboxEvent(eventId,eventType,commentId,postId,rootId,parentId,creatorId,clientRequestId,body,occurredAt)` |
| `comment-events`（`comment.kafka.event-topic`） | `CommentOutboxDispatcher`，key=`aggregateId` | `comment-counter-effects`、`comment-reward-effects`、`comment-feedback-effects` 三个独立组 | `CommentOutboxEvent`，类型为 `COMMENT_CREATED/COMMENT_DELETED/COMMENT_MODERATED` |
| `comment-feedback`（`comment.kafka.feedback-topic`） | `CommentFeedbackProducer`（best-effort）+ Controller 内联 | `notification-comment-consumer`、`recommendation-comment-feedback-consumer` | `CommentFeedbackEvent(...,action∈{comment,delete,like,unlike})` |
| `counter-events`（`CounterTopics.EVENTS`） | `CounterEventProducer`（无 key 异步；序列化失败静默） | `counter-agg`（每秒折叠 SDS）、`counter-rebuild`（earliest 回放，`counter.rebuild.enabled` 门控）、`notification-like-consumer`、`recommendation-counter-feedback-consumer` | `CounterEvent(eventId,occurredAt,entityType,entityId,metric,idx,userId,delta)` |

全局：`auto-offset-reset=earliest`、`enable-auto-commit=false`、`ack-mode=manual`、String 序列化、`admin.auto-create=true`（`application.yml`）。

### 6.3 Redis Stream（推广决策）

- 每窗口 Stream：`promotion:auction:{windowId}:events`；Pub/Sub：`promotion:auction:{windowId}:pub`。全部窗口内 key 使用相同 `{windowId}` hash tag。
- Stream ID 固定为 `<decisionVersion>-0`；`decision` 字段保存完整 JSON 决策。state 版本与最后 Stream ID 必须锁步。
- `BID_ACCEPTED` 与 `WINDOW_CLOSED` 写 Stream；业务拒绝直接返回并缓存幂等结果，不推进版本。保证金授权以 MySQL 为事实并同步投影 Redis，不写竞价 Stream。
- Pub/Sub 仅提示窗口可能有新事件。窗口初始化登记 `promotion:auction:active-streams`；启动先从 MySQL 恢复 OPEN 或 checkpoint 未追平的 REDIS_STREAM 窗口，再每 2 秒只遍历该 set 并从 checkpoint 继续 XRANGE；不得直接消费 Pub/Sub payload 作为事实，不得扫描 Redis 全键空间。
- MySQL checkpoint 提交后，允许裁剪 `min(checkpointVersion, currentVersion-100000)` 之前的事件；checkpoint 落后或不可用时停止裁剪。窗口结算且投影追平后，热键保留 24 小时。

### 6.4 幂等 / 失败兜底矩阵

| 环节 | 机制 |
|------|------|
| 评论提交 | `pending_comments uk(creator,client_request_id)` + `DuplicateKeyException` 返回既有 |
| 发布受理 | `publish_attempt uk(creator,post,idempotent_key)` |
| 推广竞价 | 确定性 commandId + `request_hash`（SHA-256）一致校验 + Redis Lua 在 TTL 覆盖的同槽分钟桶中按 commandId field 重放；接受结果与 Stream append 同一 Lua 原子完成并直接返回，拒绝缓存结果但不写 Stream |
| 推广保证金 | `promotion_bid_escrow uk(window,campaign)` + 授权目标金额 businessRef + Redis `authorizedAmount` 仅接受单调增加 |
| 决策投影 | checkpoint 版本严格连续校验 + 同版本同 decisionId 幂等跳过 |
| 关系事件 | `dedup:rel:*` SET NX（10m）+ follower 表 upsert |
| 通知 | `uk_notification_event_key` + 吞 DuplicateKey；点赞另有 `notif:like:event:*` 去重 |
| 钱包 | `uk(owner,business_ref)` + 整组判等 + `wallet_business_ref` claim |
| 事件失败兜底 | 消费异常 → 不 ack 重投；或建对账任务：`ES_INDEX`（search）、`FOLLOW_INBOX`（feed）、`GORSE_ITEM_UPSERT/GORSE_FEEDBACK`（recommendation）；Gorse 用户资料失败仅不 ack |

---

## 7. 模块契约

### 7.1 auth / user / profile

**API**（`AuthController`，`/api/v1/auth`）：`POST send-code`（scene∈REGISTER/LOGIN/RESET_PASSWORD，存在性不变量：REGISTER 要求标识不存在，其余要求存在）、`register`（`agreeTerms` 必 true；注册自动登录+建钱包赠币 `WalletRegistrationGrantService.createUserAndGrant` 同事务）、`login`（password 或 code）、`token/refresh`（轮换）、`logout`（204，无效静默）、`password/reset`（成功后 `revokeAll`）、`GET me`。`ProfileController`（`/api/v1/profile`）：`PATCH`（仅非 null 字段、trim、gender 大写、zgId 正则 `^[a-zA-Z0-9_]{4,32}$` 且唯一）、`POST /avatar`。

**验证码**（`VerificationService` + `RedisVerificationCodeStore`）：6 位纯数字；发送 60s 间隔 + 每日 10 次；校验 5 次上限，用尽 TTL 延至 30m；状态 SUCCESS/NOT_FOUND/MISMATCH/TOO_MANY_ATTEMPTS（EXPIRED 枚举声明但 TTL 过期表现为键消失→NOT_FOUND）。

**密码策略**（`AuthService.validatePassword`）：≥`auth.password.min-length`(8) 且同时含字母和数字；BCrypt strength 12。

**审计**：登录/注册写 `login_logs`（channel∈PASSWORD/CODE/REGISTER，status∈SUCCESS/FAILED）；IP 解析 `X-Forwarded-For`→`X-Real-IP`→remoteAddr。

**事件**：资料更新写 outbox `user_profile_updated`（payload `{eventType,user:{...}}`）→ `UserProfileRecommendationConsumer` 同步 Gorse。

**关键类**：`auth/config/SecurityConfig.java`、`AuthConfiguration.java`（BCrypt+Nimbus RS256，`PemUtils` PKCS#8/X.509）、`token/JwtService.java`、`token/RedisRefreshTokenStore.java`、`verification/*`（9 文件）、`audit/LoginLogService.java`、`user/service/UserServiceImpl.java`、`profile/service/impl/ProfileServiceImpl.java`、`profile/event/UserProfileUpdatedProducer.java`。

### 7.2 knowpost（知文）

**API**（`/api/v1/knowposts`，13 端点）：`POST drafts`、`POST {id}/content/confirm`（objectKey+etag+size+sha256）、`PATCH {id}`、`POST {id}/publish`（202+attemptId）、`GET {id}/publish/status`、`POST {id}/publish/{attemptId}/retry`（202）、`PATCH {id}/top`、`PATCH {id}/visibility`（public/followers/school/private/unlisted）、`DELETE {id}`（软删）、`GET feed`（登录+`feed.home.mixed-enabled` 走混排）、`GET feed/follow`（cursor `{ts}:{contentId}`）、`GET mine`、`GET detail/{id}`（匿名可读；非 public 仅作者）。

**发布流水线**（`PublishManagerImpl.runPublish`，`publishExecutor`）：
1. `storePostText`：MinIO 拉正文 → 落 Cassandra `post_text_by_post_id`（带 sha256）；
2. `completePublish`：guard `publish:content-published` 内写 outbox `content_published`（关键路径，guard 降级即失败）→ `know_posts` 守卫更新 → attempt succeeded → `ContentRewardService.rewardPostCreation`（独立事务不阻塞）；
3. 失败 → `failPublish("critical_publish")`；
4. 派生工作：guard `publish:user-counter` 增作者发文计数；失败写 `publish_derived_failure` 事件（再失败落 attempt `fallback_*` 列）。

**状态机**：`know_posts.status`：`draft→publishing→published`；`publishing→publish_failed`；`publish_failed→publishing`（retry）；`published→deleted`（软删无守卫）；`published→rejected`（审核，`WHERE status='published'`）。`publish_attempt.status`：`publishing→succeeded|failed`、`failed→publishing`（retry_count+1）；失败步骤 `critical_publish`/`stuck_publishing`（5 分钟卡死，读路径+60s 定时+启动三处恢复）。

**缓存**：详情 `knowpost:detail:{id}:v1`（Caffeine L1 + Redis L2 + `"NULL"` 防穿透 + HotKeyDetector 续期 + 写路径双删）；公共 Feed 三级缓存（Caffeine 页 → Redis ids 片段/hasMore + `feed:item:*` 条目 + 反向索引失效）；`FeedCacheInvalidationListener` 以 Spring `@EventListener` 监听**进程内** `CounterEvent`（`CounterServiceImpl` 发 Kafka 的同时 `publishEvent`）本地更新快照计数与作者获赞/获藏计数。

**事件**：outbox `content_published` 驱动 ES 索引（`CanalOutboxConsumerSearch`，失败建 ES_INDEX 对账）、Gorse upsert（失败建 GORSE_ITEM_UPSERT）、关注流扇出（`TimelineDispatcher`，失败建 FOLLOW_INBOX）。

**关键类**：`manager/PublishManagerImpl.java`、`manager/PublishAttemptService.java`、`manager/PublishValidationHelper.java`、`publish/ContentPublishedPublisher.java`、`service/impl/KnowPostServiceImpl.java`、`service/impl/KnowPostFeedServiceImpl.java`、`listener/FeedCacheInvalidationListener.java`。

### 7.3 comment

**API**（`/api/v1/...`）：`POST posts/{postId}/comments`（**恒 202**）、`GET comments/{pendingCommentId}/status`、`GET posts/{postId}/comments`（游标 `cursorCreateTime+cursorCommentId`，1≤limit≤100）、`GET comments/{commentId}/replies`（按 root_id，两级评论）、`DELETE comments/{commentId}`（204 属主软删）、`POST/DELETE comments/{commentId}/like`（仅返回 `{changed}`；生效时才发 feedback like/unlike 事件；点赞状态在 counter 模块 `ActionController` 以 `{changed,liked}` 返回）。

**写路径**：`submit` 幂等（`pending_comments` 查重/唯一键）→ 同事务写 `comment_outbox(COMMENT_WRITE_REQUESTED)` → `CommentOutboxDispatcher` 批量 claim、异步发送并按成功/失败子集批量更新 → `CommentWriteConsumer` 读取并校验 pending 后先对 Cassandra 正文做固定版本幂等 upsert，再把已校验 pending 传入代理后的短事务 `CommentMaterializationService`，原子写 `comments`、`pending=succeeded` 与 `COMMENT_CREATED` outbox；仅在条件更新丢失并发竞争时回读 pending。`COMMENT_CREATED` 由 Counter、Reward、Feedback 三个独立 consumer group 处理，物化线程不串行执行副作用；DLT 仅将仍为 pending 的记录置 failed。

**outbox 状态机**（`CommentOutboxMapper.xml`）：`ready(0)→claimed(1)→published(2)` 或退回 `ready(0)`（重试退避）；claim 超时可回收。published 保留 24 小时后由 cleaner 每批最多删除 1000 条。旧 `comment_write_outbox` 不迁移、不双写，clean cutover 前置检查要求旧表无未发布记录。

**不变量**：回复必须 parent 为顶层（`parent.parentId==0`）、post_id 一致、status=0；`root_id=parent_id=parent.commentId`；已删评论 body 恒 `[deleted]` 且不查 Cassandra；计数服务故障降级为空 Map 不影响列表。

**缓存失效**：事务提交事件与 Kafka 重投以 outbox eventId 去重，默认在 100ms 窗口内按 post/root scope 合并；一批反向索引只读取一次，Caffeine 批量失效，Redis index/item/scope key 使用单次 multi-key `UNLINK`。页面重建使用 pipeline 批量写 item fragment，index metadata 保持独立的短 `MULTI/EXEC`；缓存失效调度器不参与 Spring 全局 `@Scheduled` 任务调度。

**关键类**：`service/impl/CommentServiceImpl.java`、`service/impl/CommentMaterializationService.java`、`service/impl/CommentMutationService.java`、`event/CommentOutboxDispatcher.java`、`event/CommentOutboxCleaner.java`、`consumer/CommentWriteConsumer.java`、`consumer/CommentCounterConsumer.java`、`consumer/CommentRewardConsumer.java`、`consumer/CommentFeedbackConsumer.java`、`config/CommentKafkaConfig.java`、`config/CommentOutboxSchemaInitializer.java`。

### 7.4 counter

**API**：`POST /api/v1/action/{like,unlike,fav,unfav}`（JWT，`ActionRequest{entityType,entityId}` → `{changed,liked/faved}`，位图原子翻转，重复点击 changed=false）；`GET /api/v1/counter/{etype}/{eid}?metrics=`（metrics∈like/fav/comment，未知过滤；SDS 缺失触发重建，受限时返回全 0）。

**三层模型**：
1. 事实层：`TOGGLE_LUA` 位图原子翻转 + `SADD bm:index`（`CounterServiceImpl`；分片 `BitmapShard.CHUNK_SIZE=32768`，chunk=uid/32768）；
2. 事件层：`CounterEventProducer` → `counter-events`（无 key）→ `CounterAggregationConsumer`（`HINCRBY aggKey` + dirty set，手动 ack）→ 每秒 `@Scheduled(fixedDelay=1000)` 折叠 SDS（INCR_FIELD_LUA 大端 uint32 下限 0 → DECR_FIELD → HLEN==0 才 DEL 桶）；
3. 读取层：`cnt:v1:*` SDS（5×4B，idx：like=1,fav=2,comment=3）；缺失 → singleflight（stage `counter-sds`，flight key=`{etype}:{eid}:{metrics}`）从位图重建；限流时返回零值。

**用户计数**（`UserCounterServiceImpl`）：`ucnt:{userId}` 同布局（段1 关注/段2 粉丝/段3 发文/段4 获赞/段5 获藏）；增量走 Lua 折叠；`UserCounterRebuildAdapter.rebuildAndRead` 全量重建（聚合作者全部知文 like/fav；`counter.rebuild.enabled` 时另有 Kafka earliest 回放消费者 `CounterRebuildConsumer`）。

**关键类**：`service/impl/CounterServiceImpl.java`、`service/impl/UserCounterServiceImpl.java`、`schema/CounterSchema.java`、`schema/CounterKeys.java`、`schema/BitmapShard.java`、`event/CounterAggregationConsumer.java`、`service/CounterBitmapShardIndexInitializer.java`（启动 SCAN `bm:*` 回填分片索引）。

### 7.5 relation

**API**（`/api/v1/relation`）：`POST follow/unfollow`（`toUserId` → boolean）、`GET status`（`{following,followedBy,mutual}` 实时双查）、`GET following/followers`（offset+cursor 双分页 → `ProfileResponse` 列表）、`GET counter`（`{followings,followers,posts,likedPosts,favedPosts}` + `ucnt:chk` 采样 300s 校验 + singleflight `user-counter` 重建）。

**写路径**（`RelationManagerImpl` @Transactional）：幂等检查 → 令牌桶限流（`rl:follow:*` Lua，容量 100/1rps）→ 写 `following`（ON DUPLICATE rel_status=1）→ 回读主键（null 即回滚）→ guard `relation:outbox-publish` 内写 outbox `FollowCreated/FollowCanceled`（guard 降级回滚）——**following 与 outbox 同事务**。

**消费路径**（`CanalOutboxConsumer` group `relation-outbox-consumer`）：手动 ack + `relationEventExecutor` 并发处理（全成功才 ack）；`RelationEventProcessor`：`dedup:rel:*` SET NX 幂等（10m）→ 写 `follower` 镜像（id 复用 following 行 id）→ 维护 `uf:flws/uf:fans` ZSet（2h）→ `UserCounterService` 增减。取消时 `follower` 表 `cancelFollower` 无 rel_status 条件（与 `cancelFollowing` 不同）。

**读路径**（`RelationServiceImpl`）：ZSet 优先 → DB 回填（fillZSet）；大 V（粉丝段≥500,000）Top500 Caffeine 缓存（10m/1000）。

### 7.6 moderation

**API**：`POST /api/v1/moderation/reports`（恒 202；reason∈spam/harassment/violence/pornography/illegal/other；目标前置校验：post 须 published、comment 须 status!=1；同 (reporter,target_type,target_id) 去重返回既有）。

**流水线**：写 outbox `review_requested`（`{entity:moderation_report, op:review_requested, reportId, targetType, targetId}`）→ `ModerationReviewConsumer`（`moderation.llm.enabled=true` 才注册）→ `ModerationReviewExecutorImpl`：
1. singleflight（stage `moderation-llm`，key=`report:{id}:retry:{n}`）去重 LLM 调用；
2. LLM 结果决策：retryable 失败 → `scheduleRetry`（next_retry_at=now+min 数分钟，`scheduleRetry` CAS 防并发；≥maxRetries(3) → ignored）；invalid/低置信（<0.8）/决策不支持/摘要空 → `markIgnored`；
3. `markReviewed(approved|rejected)` 守卫成功 → approved 处置：post → `status='rejected'`（守卫 published）+ outbox `KnowPostModerationRejected`；comment → `status=1` 软删；缓存失效（afterCommit：`knowpost:detail:*`、`feed:item:*`、`feed:public:*` 反向索引）→ 通知（平台演员号 0；MODERATION_ACTION → 目标 owner；REPORT_PROCESSED → 举报人；失败仅记列）。

**状态机**：`pending → approved | rejected | ignored`（无 reviewing 态，`ModerationStatus` 常量）；重试在 pending 内自循环。失败码字面量：`INPUT_UNAVAILABLE/LLM_UNAVAILABLE/LLM_DISABLED`(retryable)、`INPUT_INSUFFICIENT/INVALID_RESPONSE/LOW_CONFIDENCE`(invalid)。

**LLM 客户端**：`SpringAiModerationLlmClient`（provider=opencode，`spring.ai.openai.chat.options.model` 默认 `deepseek-v4-flash-free`）与 `SpringAiAlibabaModerationLlmClient`（provider=dashscope，默认 `qwen-plus`）逐行同构；`BeanOutputConverter` 强制 JSON、prompt 声明 JSON 不可信防注入；`DisabledModerationLlmClient`（enabled=false）恒 `LLM_DISABLED`。

### 7.7 notification

**API**（`/api/v1/notifications`）：`GET`（键集游标分页 `(created_at,id)<(cursor)`）、`GET unread-count`、`POST {id}/read`（归属校验 + `is_read=0` 条件更新）、`POST read-all`。列表项 ID 全 String。

**消费**：`CommentNotificationConsumer`（`comment-feedback`，action=comment → 收件人=父评论作者或帖主；eventKey `comment:create:{commentId}`）；`FollowNotificationConsumer`（`canal-outbox`，FollowCreated → eventKey `follow:outbox:{outboxRowId}`）；`LikeNotificationConsumer`（`counter-events`，metric=like & delta=1 → Redis 桶聚合：5 分钟窗口 `windowStart=(t/300000)*300000`、`notif:like:event:*` 去重 6h、桶 TTL 20min）→ `LikeNotificationFlushJob`（30s：窗口到期落库 + 清桶；无锁，靠 event_key 唯一键兜底）。

**聚合语义**：同 (recipient, entityType, entityId) 窗口合并：aggregate_count、latestActorUserId、latestEventAt；eventKey `like:bucket:{recipient}:{etype}:{eid}:{windowStart}`。

**不变量**：`uk_notification_event_key` + 吞 DuplicateKey；不给自己发通知（create 与 like 入桶前双重检查）；`NotificationType` 常量：like/comment/follow/moderation_action/report_processed（后两个由 moderation 写入）。

### 7.8 promotion（含 bprime）

**API**（`/api/v1/promotions`）：`POST campaigns`、`GET campaigns/{id}`；`POST campaigns/{id}/escrow` 在出价前把授权上限从 available 冻结到 held，并在事务提交后同步投影 Redis，投影成功才返回 window/authorizedAmount。竞价提交不开放 REST，只允许兼容 WebSocket STOMP `/app/promotion-auctions/bids` 与高活动原生 WebSocket `/ws/promotion-auction-native`，两者共用收单和协议服务。一次请求只返回一个私有结果：`ACCEPTED`、`REJECTED` 或可重试 `UNAVAILABLE`；不再返回 `PUBLISHED`，也不再通过独立 outcome channel 补发最终结果。STOMP 结果发往当前用户 `/user/queue/promotion-auction-bid-acks`，原生 WS 在同一连接返回同结构 JSON。订阅房间后接收合并后的 `RANKING_DELTA` 与终场事件；公共版本缺口由 snapshot 恢复。未授权/授权不足仍由稳定业务错误或 Redis Lua 给出最终拒绝；另有 active allocation 与窗口 snapshot 读接口。

**窗口生命周期**（`PromotionAuctionWindowService` + `PromotionAuctionScheduler` 3×30s fixedDelay）：epoch 对齐 60 分钟窗口；恒保「当前 OPEN + 下一窗口」；`closeDueWindows` 到期（+30s 延迟）非 bprime 直结 / bprime 追加 `WINDOW_CLOSED` 决策；`refreshAllocations` 周期刷 `promotion:allocation:active:*`。

**bprime Redis 热链路**（`promotion.bprime.enabled`）：窗口持久化不可变 `decision_path`；已有窗口默认为 `LEGACY_BROKER`，新窗口默认 `REDIS_STREAM`，切换期间按窗口单选路径。开关关闭时保证金事务在冻结资金前返回暂停。开启后，保证金授权在低频 MySQL 事务内锁定/增加 `promotion_bid_escrow.authorized_amount` 并执行一次钱包 `hold(delta)`，同事务创建 Redis 投影对账任务；提交后同步初始化窗口状态、登记 active-streams、单调投影 authorizedAmount、写 campaign→window 路由，全部成功才报告授权可用。出价由有界提交线程池直接执行同槽 Lua；Lua 使用 Redis TIME，写前完成 key type 与 Stream/version 锁步校验，接受时原子更新 campaign bid、ranking、currentHold、decisionVersion，XADD `<version>-0` 决策并 PUBLISH；接受和拒绝均在同槽分钟桶紧凑保存幂等结果，默认保留 10 分钟，拒绝不推进版本。Redis 异常、类型错误、版本失配或提交池过载统一返回 `UNAVAILABLE/PROMOTION_AUCTION_PAUSED`，不得本地接受或回退 MySQL。高活动原生 WebSocket 维护房间到 SessionWriter 的直接索引；每连接由专用有界线程池执行单写泵，最终 ACK 使用有界 FIFO，公共状态只保留最新批次，关键反馈优先。慢连接关键队列满载时以 `4000/backpressure` 关闭并由客户端重连恢复。

**决策投影与实时通知**：单个 Stream worker 启动时从 MySQL 恢复 OPEN 或 checkpoint 未追平的 REDIS_STREAM 窗口到 active-streams，运行期间由 Pub/Sub 唤醒并每 2 秒遍历该集合兜底；按窗口从 MySQL checkpoint 之后分页读取，每页不超过 1000 条。窗口 SETTLED 且 checkpoint 与 Stream 追平后从集合移除并设置热状态 TTL。事件先经带版本去重的公共 fanout，再在一个 MySQL 事务内完成事件结构、Stream ID 与版本连续校验及投影，避免 checkpoint 成功后 fanout 瞬时失败导致广播永久跳过。接受 BID upsert `promotion_bid` 并更新 `promotion_bid_escrow.current_hold`，不再调用钱包 hold。`WINDOW_CLOSED` 到达时前序 BID 已按序投影，随后执行 GSP：winner 从已授权总额 capture clearingPrice 并 release 余量，loser release 全部授权额，再关闭 escrow、写 allocation、标记 SETTLED 与推进 checkpoint。最终私有出价结果由 Lua 调用返回路径直接发送，不经 fanout。公共增量按房间订阅数和待写槽占比在 100–250ms 合并；`eventVersion` 等于 Stream decisionVersion，客户端发现区间缺口或裁剪缺口时必须拉 snapshot。projection/fanout 失败不得推进 checkpoint；实时消息只作为可恢复通知层。

**读路径消费方**：`KnowPostFeedServiceImpl`（page=1 至多 1 条 feed_top_slot）、`HomeFeedMixingService`（混排首条推广）、`SearchServiceImpl`（首屏 search_top_slot）——均经 `PromotionAllocationService`（缓存优先 + DB 时间过滤回源）。

**快照/补偿**：`PromotionSnapshotService`；结算补偿见 7.12。

### 7.9 recommendation + feed

**推荐**：`RecommendationEngine.recommend(userId,count)` → `GorseRecommendationAdapter`（gorse `/api/recommend` 召回；`recommendation.gorse.enabled=false` 或 `RestClientException` → hot 兜底 `listFeedPublicIds`；organicScore 递减保序；`X-API-Key` 头；timeout 300ms）。Gorse 出站：`/api/item`(POST/GET)、`/api/feedback`(PUT)、`/api/user`(POST)。

**首页混排**（`HomeFeedMixingService`，`feed.home.mixed-enabled=true` 时）：TARGET_SIZE=20 硬编码；推广≤1 → 关注流 → 推荐候选 40 → hot 补位；LinkedHashSet 去重；商业推广不参与排序。

**关注流**（`follow feed`）：写侧 `TimelineDispatcher`（消费 `canal-outbox` content_published；`countFollowerActive ≥ push-pull-threshold(10000)` → 大 V 拉模式只写 `feed_author_feed`；普通作者分页 256 写全部粉丝 `feed_inbox`，异步 5s 超时；失败建 FOLLOW_INBOX 对账）。读侧 `FollowFeedServiceImpl`：inbox+大 V author_feed 头多路归并（`(publish_ts, content_id)` 降序游标）、Redis 两级缓存（`feed:timeline:*` 300s 仅默认页、`feed:author:*:head` 120s + singleflight `feed-author-head`）、可见性过滤（published 且 visible∈public/followers）。

**Gorse 反馈消费者**（5 个，enabled 门控 + 手动 ack + 失败对账）：content_published→item；comment-feedback(comment)→feedback "comment"；counter-events(like/fav delta>0)→feedback；canal-outbox FollowCreated/FollowCanceled→follow/unfollow；user_profile_updated→upsertUser（失败不 ack 无对账）。

### 7.10 search + storage

**搜索 API**：`GET /api/v1/search?q=&size=&tags=&after=`（search_after 游标，ES 异常降级返回仅商业位）、`GET /api/v1/search/suggest?prefix=&size=`（completion；异常返回空）。高亮 snippet 拼 `title+body`；like/fav 实时叠加计数。

**增量索引**：`CanalOutboxConsumerSearch`（group `search-index-consumer`）：content_published→`upsertKnowPostStrict`（失败建 ES_INDEX 对账）；`{entity:knowpost, op:delete}`→软删；元数据事件→upsert。

**存储 API**：`POST /api/v1/storage/presign`（scene 校验 + postId 归属）。`CassandraTextStorageService`：save（version+1 覆盖）/get（缺失回退 `fallbackContentUrl` HTTP 拉取）/delete；异常分层 `TextStorageException/TextReadException/TextWriteException`。

### 7.11 wallet

**API（只读）**：`GET /api/v1/wallet/me`（三态余额+status）、`GET /api/v1/wallet/me/ledger`（倒序分页，13 字段）、`GET /api/v1/content-reward/config`（enabled/postAmount/commentAmount）。

**账务核心**（`WalletService`，全部 `@Transactional(READ_COMMITTED)`）：写路径 = 行锁 `SELECT...FOR UPDATE` → `applyBalanceDeltas`（SQL 非负保护 WHERE）→ 先 `INSERT wallet_business_ref` claim（撞唯一键者回读 ledger 整组判等：8 字段 `LedgerIdentity` 全等才幂等返回，否则 `WALLET_DUPLICATE_BUSINESS_REF`）→ 写 ledger（`uk(owner,business_ref)`）。方法语义：`grant(+available)`、`hold(available→held)`、`releaseHold(held→available)`、`moveHoldToEscrow(held→escrowed)`、`captureHoldToPlatform(held 扣减, counterparty=平台哨兵 0)`、`releaseEscrowToPayee(双侧 ledger, 双账户升序锁)`、`releaseEscrowToAvailable`、`forfeitEscrowToPlatform`。

**托管状态机**（`WalletEscrowService`，每步 = 行锁 + `transitionBusinessRef` 幂等判等 + 条件迁移 `WHERE status=from`）：`createEscrow`→CREATED（hold）；`lockEscrow`→LOCKED（moveHoldToEscrow）；`releaseEscrow`→RELEASED（payer escrowed→payee available）；`refundEscrow`→REFUNDED（CREATED: releaseHold / LOCKED: releaseEscrowToAvailable）；`cancelEscrow`→CANCELLED（CREATED 限）；`forfeitEscrow`→FORFEITED（escrowed→平台哨兵）；`resolveExpiredEscrow`（expires_at<=now，RELEASE/REFUND 委托）。

**奖励/赠币**：`WalletRegistrationGrantService.createUserAndGrant`（建用户+建零账户+`REGISTRATION_GRANT` grant）；`ContentRewardService`（`REQUIRES_NEW` 子事务 + 方法内 catch 吞异常不阻塞主流程；businessRef `content-reward:post:{postId}` / `content-reward:comment:{commentId}`）。`WalletLedgerReason` 15 值、`WalletBusinessType` 5 值（REGISTRATION/PROMOTION/BOUNTY/CONTENT/SYSTEM）。

### 7.12 reconciliation

**API**：`GET /tasks`、`GET /tasks/{id}`、`POST /tasks/{id}/retry`（仅 dead→pending）、`POST /targets/{type}/{id}/rerun`（type→任务组映射：post→ES_INDEX+GORSE_ITEM_UPSERT+CASSANDRA_TEXT+COMMENT_COUNT+FOLLOW_INBOX；comment/user/promotion_auction_window（仅 SETTLED））。

**执行器**（`ReconciliationTaskExecutor`）：30s 轮询 `status='pending' AND next_execute_at<=now LIMIT 50` → Redisson `recon:lock:{id}` → CAS `pending→running` → Reconciler 执行 → `succeeded`；异常退避 `1<<retryCount` 分钟（1,2,4,8,16）→ `markPendingRetry`；≥5 次或 `NonRetryableReconciliationException` → `dead` + `reconciliation_error_log`（全文 stack）；`running` 卡死 10 分钟 → 复位 pending。未注册类型 → IllegalStateException（可重试，终 dead）。

**扫描**（`ReconciliationScanService`，8 路 × 300s + checkpoint 游标，空批回 0 重扫）：post_es（ES 缺失/计数漂移→ES_INDEX）、post_gorse（hasItem 缺失→GORSE_ITEM_UPSERT）、post_cassandra（正文缺失→CASSANDRA_TEXT）、comment_cassandra（正文缺失→**dead**，无持久化原文）、post_comment_count / comment_reply_count（无条件→COMMENT_COUNT）、user_follow_graph（无条件→FOLLOW_GRAPH）、promotion_settled_window（`settled-compensation-lookback-seconds` 604800 内 SETTLED 窗口补偿分析）。

**补偿分析**（`PromotionAuctionCompensationService`）：重放预期 GSP 计划对照实际——allocation 全缺 → `PROMOTION_ALLOCATION_REBUILD`；行数/顺序不符 → **dead**；`wallet_ledger.business_ref` 缺失 → `PROMOTION_WALLET_EFFECT_REPAIR`（12 字段 payload），存在但不符 → dead。

**10 个 Reconciler**：`CassandraTextReconciler`（回填+sha256）、`CommentCountReconciler`（MySQL 计数→SDS 覆盖）、`EsIndexReconciler`（事实计数→upsertKnowPostStrict）、`FollowGraphReconciler`（双向镜像+ZSet 2h）、`FollowInboxReconciler`（payload 反序列化→timelineExecutor.fanout）、`GorseFeedbackReconciler`、`GorseItemUpsertReconciler`（仅 published）、`PromotionAllocationRebuildReconciler`（全缺重放 settleWindow，已有行 NonRetryable）、`PromotionEscrowRedisProjectionReconciler`（重放已提交的保证金授权到 Redis）、`PromotionWalletEffectRepairReconciler`（CAPTURE→captureHoldToPlatform / RELEASE→releaseHold；`WALLET_DUPLICATE_BUSINESS_REF`→NonRetryable）。

**调度节奏**（`ReconciliationScheduler`）：执行 30s / 卡死 60s / 8 路扫描 300s（initialDelay 30–150s 错峰）/ 启动 10s 发布卡死恢复（`recoverStuckPublishingOnStartup`）。

### 7.13 common 基础设施

- **ID**：见 D4；Snowflake 位布局 `1+41+5+5+12`，EPOCH `1704067200000`；时钟回拨 ≤5ms 睡等重试、>5ms 抛 `ClockBackwardException`；segment 双缓冲 50% 预加载、等待超时 500ms。
- **Resilience**：`ResilienceGuard.execute(resourceName, op, fallback, classifier)` → `SentinelResilienceGuard`（SphU.entry；Block/异常→fallback；classifier 真时 `Tracer.trace`）；**代码内无 FlowRule 下发**（规则外部供给）；实际资源名：`publish:content-published`、`publish:user-counter`、`relation:outbox-publish`；消费方 `requireCriticalGuardSuccess` 拒绝降级。
- **SingleFlight**（`common/singleflight/`，`singleflight.enabled`）：分布式协调 Redis Lua 6 脚本（meta/result/owner-seq）+ Stream 通知（非 pub/sub）+ L1 LRU 回放缓存 + 心跳（1s，takeover 10s）+ 结果编码（Base64+gzip+SHA-256）；失败分类 TIMEOUT/OVERLOAD/PROVIDER/VALIDATION/UNEXPECTED；`MAX_ATTEMPTS=3`；mode DISABLED/LOCAL/DISTRIBUTED/HYBRID；实际 stage：`counter-sds`、`moderation-llm`（`user-counter`/`feed-author-head` 见配置与调用点）。
- **缓存/热键**：Caffeine L1 三 Bean（feedPublic 15s/1000、feedMine 10s/1000、detail 30s/5000）；`HotKeyDetector` 分段滑动窗口（6×10s；≥50 LOW/≥200 MEDIUM/≥500 HIGH）→ TTL 延长 +20/60/120s。
- **全局异常**：见 D3/§4.1。
- **OutboxMessageUtil**：Canal 行提取唯一解析器。

---

## 8. 技术栈

| 层次 | 选择（出处） |
|------|------|
| 语言/框架 | Java 21 + Spring Boot 3.5.10（`pom.xml`） |
| Web | spring-boot-starter-web（MVC）+ WebSocket/STOMP（推广实时） |
| 安全 | spring-security + oauth2-resource-server（JWT RS256，Nimbus） |
| 持久化 | MyBatis 3.0.3 + MySQL 8.4（`mysql-connector-j 9.5.0`）；spring-data-cassandra 4.1 |
| 缓存 | spring-data-redis + Redisson 3.52（锁/看门狗 30s）+ Caffeine 3.1.8 |
| 消息 | spring-kafka（manual ack，非推广域）+ Redis Stream（推广竞价）+ canal client 1.1.8 |
| 搜索 | elasticsearch-java/rest-client 8.12.2（容器镜像 9.2.1+IK 分词） |
| 对象存储 | MinIO 8.5.9（预签名） |
| AI | spring-ai-alibaba-starter-dashscope 1.1.2（审核 LLM，可关） |
| 流控 | sentinel-core 1.8.10（仅 SDK，规则外部下发） |
| 邮件 | spring-boot-starter-mail（声明未用）[INFERENCE：无消费代码] |
| 工程 | Lombok 1.18.46、Maven；测试：spring-boot-starter-test + Mockito + Testcontainers(Cassandra) |
| 部署 | 单 Dockerfile（多阶段 Maven 构建 → JRE21 精简镜像，非 root，`-Xmx2g`），compose 一键全栈 |

---

## 9. 配置清单（`src/main/resources/application.yml` + 各 `@ConfigurationProperties`）

| 前缀 | 关键键（默认值） | 绑定类 |
|------|------|--------|
| `server` | port 8080 | — |
| `spring.datasource` | MySQL `zhiguang`，hikari 10/2 | — |
| `spring.kafka` | manual ack、earliest、auto-create | — |
| `spring.data.cassandra` | `zhiguang` keyspace，schema-action none | — |
| `spring.elasticsearch` | uris localhost:9200 | `EsProperties` |
| `spring.ai.dashscope` | api-key 占位、model qwen-plus | — |
| `auth.*` | jwt ttl 15m/7d、issuer、kid、PEM；verification 6 位/5m/5 次/60s/10 次；password min 8/bcrypt 12 | `AuthProperties` |
| `id.snowflake` | worker/datacenter 1 | `SnowflakeProperties` |
| `id.segment` | wait-timeout 500ms、preload-threads 2 | `SegmentIdProperties` |
| `singleflight.*` | enabled/mode/defaults(13 项)/stages(4) | `SingleFlightProperties` |
| `comment.kafka.*` / `comment.outbox.*` | write/event/feedback topic；write 初始并发 4；dispatcher batch 500、claim 30s、in-flight 2、clean batch 1000、retention 24h | @Value |
| `wallet.*` | platform-user-id 0、registration-grant-amount 100 | `WalletProperties` |
| `content-reward.*` | enabled true、post 10、comment 2 | `ContentRewardProperties` |
| `promotion.slot-auction.*` | 槽位/保留价/60min 窗口/缓存 300s/批 50/30s | `PromotionProperties` |
| `promotion.bprime.*` | enabled false、热状态 24h、命令幂等 10m/分钟桶、active-streams sweep 2s、读取批次 1000、保留事件 100000、结算回看 7d、WebSocket 出站线程与有界队列、公共增量 100–250ms 自适应/调度线程/窗口上限 | `PromotionBPrimeProperties` |
| `storage.*` | MinIO endpoint/bucket/公开域名 | `StorageProperties` |
| `canal.*` | enabled false、host/port/destination/filter=`zhiguang.outbox`/100/1000ms | @Value |
| `counter.rebuild` | enabled false | — |
| `moderation.*` | llm enabled false/0.8/4000 字/3 次；platform-actor 0 | `ModerationProperties` |
| `recommendation.gorse.*` | enabled false、endpoint、timeout 300ms、api-key | `GorseProperties` |
| `feed.*` | home.mixed-enabled false、fanout 阈值 10000、cache TTL、inbox.ttl-days 30 | @Value |
| `cache.*` | L1 TTL/容量 + 热键阈值 | `CacheProperties` |
| `management` | health,info 暴露 | — |

---

## 10. 已登记事实缺口（代码声明未接线 / 死代码 / 注释与实现不符）

| # | 事实 | 证据 |
|---|------|------|
| G1 | `feed.inbox.ttl-days: 30` 在 Java 代码**无消费点**（时间线 TTL 实际由 init.cql `default_time_to_live=2592000` 承载） | `application.yml:196-197`；grep 无命中 |
| G3 | `PROMOTION_DECISION_PROJECTION` 任务类型**无创建点**（仅 Reconciler 与常量）；`FEED_CACHE_INVALIDATE` 类型无 Reconciler、无创建点（若被创建将重试至 dead） | `ReconciliationTaskType.java`；`ReconciliationTaskExecutor.java:79-81` |
| G4 | `PendingCommentMapper.updateStatusByCreatorAndClientRequestId` 全库**无调用方** | grep 无命中 |
| G5 | `CounterEventProducer` 发送**无 key**，与 `CounterServiceImpl` 注释「分区按实体维度保证顺序」不符（无实体维度分区有序性） | `CounterEventProducer.java`（send 无 key） |
| G6 | `wallet.registration-grant-amount`（100）配置类持有但**代码未读取**——赠币金额由 `AuthService` 调用方实参传入 | `WalletProperties.java`；`WalletRegistrationGrantService.java` |
| G7 | `WalletLedgerReason.PLATFORM_SUBSIDY / PROMOTION_BID_RELEASE` 等在钱包域内无构造点（供外部调用方使用）；`spring-boot-starter-mail` 声明但无消费代码 | `WalletLedgerReason.java`；`pom.xml` |
| G8 | ES 客户端（pom 8.12.2）与服务端镜像（9.2.1）版本不一致 | `pom.xml` vs `docker-compose.yml`/`Dockerfile.elasticsearch` |
| G9 | `moderation.llm.enabled=false` 时 `ModerationReviewConsumer`/重试 Job 不注册，pending 举报**无人推进**（`DisabledModerationLlmClient` 恒 `LLM_DISABLED`） | `ModerationReviewConsumer.java`、`ModerationReviewRetryJob.java` @ConditionalOnProperty |
| G10 | 验证码 `EXPIRED` 状态枚举声明但**永不显式返回**（TTL 过期表现为键消失→NOT_FOUND 合并映射） | `VerificationCodeStatus.java`；`RedisVerificationCodeStore.verify` |
| G11 | `VerificationScene`/`Expired` 相关：`PromotionBidResponse` DTO 当前**无 controller 使用**（预留/对账） | `PromotionBidResponse.java` |
| G12 | `resolveExpiredEscrow` 无 scheduler 驱动（javadoc「本期不要求 scheduler」） | `WalletEscrowService.java` javadoc |
| G13 | `comment_outbox` DDL 由 `db/schema.sql` 与启动 initializer 双重声明并由 contract test 锁定一致性；旧 `comment_write_outbox` 只允许 clean-cutover 排空检查，不允许迁移或生产读写 | `CommentOutboxSchemaInitializer.java`；`db/schema.sql`；`CommentOutboxSchemaContractTest.java` |
| G14 | `KnowPostMapper.publish(id, creatorId)`（XML `<update id="publish">` 无 status 守卫、`status='published'` 直改）接口已声明但**全仓无调用方**（死代码，勿用于新发布路径；实际发布走 `startPublishing→completePublish` 守卫链） | `KnowPostMapper.java:21`；`KnowPostMapper.xml:70-74` |

---

## 11. 代码锚点索引（快速导航）

- 全局：`ZhiGuangApplication.java`、`config/ThreadPoolConfig.java`、`config/RedissonConfig.java`、`config/ElasticsearchConfig.java`、`config/RestTemplateConfig.java`
- 契约常量：`common/exception/ErrorCode.java`（27 码）、`common/id/IdNamespace.java`（11 命名空间）、`relation/outbox/OutboxTopics.java`（`canal-outbox`）、`counter/event/CounterTopics.java`（`counter-events`）、`promotion/bprime/config/PromotionBPrimeProperties.java`（主题/组）
- 状态机 SQL：`resources/mapper/KnowPostMapper.xml`、`PublishAttemptMapper.xml`、`CommentOutboxMapper.xml`、`ModerationReportMapper.xml`、`ReconciliationTaskMapper.xml`、`WalletEscrowMapper.xml`
- 原子脚本：`resources/redis/lua/promotion-auction-decision.lua`（推广决策）、`common/singleflight/RedisSingleFlightCoordinatorRepository.java`（6 Lua）、`counter/service/impl/CounterServiceImpl.java`（TOGGLE_LUA 等）
- DDL：`db/schema.sql`（26 表）、`db/cassandra/init.cql`（4 表）
