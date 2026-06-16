# add-leaf-id-service

## Why

当前项目已有本地 Snowflake ID 生成器，但评论、发布尝试、Outbox、对账任务和推荐事件会引入更多 ID 类型。需要统一 ID 服务能力，避免各模块自行生成 ID，并支持高频业务实体和低频后台任务的不同模式。

## What

- 引入 Leaf 风格 ID 能力，支持 Snowflake 和 Segment 两种模式。
- 高频业务实体使用 Snowflake。
- 低频后台任务、审计、管理操作使用 Segment。
- 保留当前用户 ID 自增策略，本轮不改用户 ID。
- 提供统一 `IdService`，业务模块不直接依赖具体算法。

## Impact

- 新增 Leaf Segment 数据表和初始化数据。
- 当前 `SnowflakeIdGenerator` 将被统一封装或替换为 `IdService`。
- 评论、发布 pipeline、Outbox、对账任务都通过 `IdService` 获取 ID。
- 需要增加 workerId/datacenterId 配置、时钟回拨处理和基础压测。

## Non-goals

- 不拆成独立微服务进程。
- 不改造现有用户 ID。
- 不实现跨语言 SDK。
