# Domain Context

## 用户计数

用户维度的计数事实，包括：

- 关注数（followings）
- 粉丝数（followers）
- 发文数（posts）
- 获赞数（liked posts）
- 获收藏数（faved posts）

`counter` 模块拥有这些事实的存储、读取、增量维护、校验与重建。Redis SDS 字节布局属于模块实现，不是跨模块契约。

消费方拥有建立在计数事实之上的策略。例如“大 V”及其阈值属于关系或关注流策略；用户计数模块只提供粉丝数事实，不判断用户是否为“大 V”。不同消费场景可以有不同阈值。

用户计数有两种读取语义：

- 尽力读取：只读取现有计数；缺失时不校验、不重建。
- 校验读取：按既有采样策略校验计数；缺失、结构异常或采样不一致时协调重建。

## 跨模块事件总线

业务事务将领域事件写入共享 `outbox` 表。`outbox` 模块拥有持久化接口、Canal 连接生命周期、完整 outbox envelope、Kafka `canal-outbox` 主题以及 Canal 位点推进语义。

桥只有在当前 Canal 批次的全部相关 Kafka 发送得到 broker 成功结果后才推进位点；解析、序列化或发送失败会回滚当前批次。因此总线提供 at-least-once 投递，业务消费者必须保持幂等。

共享 envelope 包含 outbox 行的 `id`、聚合类型与 ID、事件类型、payload 和创建时间。具体 payload 的业务含义仍由生产和消费该领域事件的模块拥有；`outbox` 模块不依赖关系、推荐、通知、搜索或审核类型。

## 缓存回源单飞

应用只使用 `common/singleflight` 提供的单飞模块；业务模块不自行维护 `ConcurrentHashMap` 或 `synchronized` flight map。

公共知文 Feed 的 singleflight 只共享不含当前用户 liked/faved 的基础页。用户计数与状态在 singleflight 之外逐请求叠加，避免 owner 的用户态污染 follower。

知文详情回源可能包含访问权限判断，因此使用 viewer-scoped 的本地 flight；详情缓存命中仍必须重新判断“公开或本人”。不得在不同 viewer 之间回放授权结果。

## 审核流水线

审核流水线拥有举报目标内容加载、prompt 构建、模型响应解析、decision 归一、置信度校验与失败分类。提供者 adapter 只拥有具体 `ChatModel` 与模型名的选择，不复制审核语义。

`moderation.llm.provider` 选择 `dashscope` 或 `opencode`；默认 `dashscope`。启用审核时只允许装配一个 `ModerationLlmClient`。新增提供者必须复用同一审核流水线，而不是复制 implementation。
