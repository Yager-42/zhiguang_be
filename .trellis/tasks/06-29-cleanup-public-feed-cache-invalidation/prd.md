# 清理公共 Feed 缓存失效旧模型

## 背景

公共 Feed 写路径已经切换为 Redis 片段缓存模型：`feed:public:ids:*` 保存页面 ID 列表，`feed:item:{id}` 保存卡片基础信息，计数和用户态在读取时实时叠加。现有 `FeedCacheInvalidationListener` 仍保留读取并写回 Redis 整页 `FeedPageResponse` JSON 的旧逻辑，和当前写路径不一致。

## 目标

- 移除公共 Feed 计数事件监听中的 Redis 整页 JSON 旧模型依赖。
- 保留当前仍有效的本地 Caffeine 页面计数旁路更新。
- 避免因为 Redis page JSON 不存在而误删仍可用于本地缓存定位的反向索引。
- 用回归测试覆盖“反向索引指向 Caffeine 页面但 Redis page JSON 不存在”的场景。

## 非目标

- 不重构公共 Feed 缓存整体架构。
- 不改变 `KnowPostFeedServiceImpl` 的公共 Feed 读写策略。
- 不引入新的 Redis 整页缓存。
- 不改动推荐/关注 Feed 的 Cassandra timeline 逻辑。

## 验收标准

- `FeedCacheInvalidationListener` 不再读取或写回 Redis 整页 `FeedPageResponse` JSON。
- 计数事件仍能更新命中的 `feedPublicCache` 页面快照。
- 当 Redis 不存在 page JSON 时，不会仅因此删除 `feed:public:index:{postId}:{hour}` 中的 page key。
- 相关单元测试通过。
