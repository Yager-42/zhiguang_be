---
doc_type: issue-analysis
issue: 2026-07-06-relation-counter-not-refreshing
status: wontfix
root_cause_type: config
related: [2026-07-06-relation-counter-not-refreshing.md]
tags: [counter, relation, follow, sds, canal]
---

# 关注/取关后用户计数不刷新 根因分析

## 1. 问题定位

| 关键位置 | 说明 |
|---|---|
| `RelationController.counter:119-175` | 读路径：从 Redis `ucnt:{userId}`（SDS 二进制快照）读 5 段计数 |
| `RelationEventProcessor.process:50-51,62-63` | 写路径：调 `userCounterService.incrementFollowings/Followers` 更新 ucnt |
| `CanalOutboxConsumer:49` | 写路径触发器：消费 Canal outbox → 调 `RelationEventProcessor.process` |
| `RelationManagerImpl.follow:89` / `unfollow:114` | 关注/取关写 MySQL `following` 表 + 发 outbox（`relationPublisher.publishFollowCreated/Canceled`） |
| `application.yml` `canal.enabled: false` | Canal 默认关闭——outbox 消息进不了 Kafka |

## 2. 失败路径还原

**正常路径（Canal 开启时）**：
用户取关 → `RelationManagerImpl.unfollow` → 写 MySQL `following` 表（rel_status=0）+ 发 outbox → Canal 桥接 outbox 到 Kafka → `CanalOutboxConsumer` 消费 → `RelationEventProcessor.process` → `incrementFollowings(fromUser, -1)` + `incrementFollowers(toUser, -1)` 更新 ucnt → counter 接口读 ucnt 返回新值 ✓

**失败路径（Canal 关闭，本地现状）**：
用户取关 → `RelationManagerImpl.unfollow` → 写 MySQL `following` 表（rel_status=0）+ 发 outbox → **outbox 进不了 Kafka（Canal 关）** → `CanalOutboxConsumer` 收不到 → `RelationEventProcessor.process` 不执行 → ucnt 不更新（仍旧值）→ counter 接口读 ucnt 返回旧值 ✗

**分叉点**：Canal 是否启用——outbox 能否进 Kafka。

**兜底**：counter 接口每 300s 采样校验（`ucnt:chk:{userId}` 锁 300s TTL），对比 SDS vs `countFollowingActive` DB 值，不一致调 `rebuildAllCounters` 重建 ucnt。所以 300s 后自愈。

## 3. 根因

**根因类型**：config（Canal 默认关闭导致异步写链路断）

**根因描述**：用户计数（ucnt）的读路径（counter 接口读 SDS）与写路径（关注/取关经 Canal outbox 异步更新 SDS）脱节。本地 Canal `enabled=false`，outbox 消息进不了 Kafka，`RelationEventProcessor` 不触发，ucnt 不实时更新。counter 接口读旧值，靠 300s 采样校验兜底自愈。

**是否有多个根因**：否，单一根因（Canal 关闭 + 读写路径脱节）。

**现场证据**（2026-07-06 15:28）：
- DB `following` 表：user14 取关 id=8（rel_status=0），有效关注 2 人
- Redis `ucnt:14` followings 段 = `0000 0003` = 3（旧值，未更新）
- counter 接口返回 followings=3（错，应为 2）
- `ucnt:chk:14` TTL=176s（300s 内刚校验过，那时 DB 还是 3，所以 ucnt 被校准成 3——校验时 DB 也旧的话校验无效）

## 4. 影响面

- **影响范围**：仅关注/取关的 `followings`/`followers` 计数延迟 300s。`posts`/`likedPosts`/`favedPosts` 不受影响（写路径不经 Canal：`PublishManagerImpl:175` 同步 incrementPosts；`FeedCacheInvalidationListener:63,66` Kafka 监听器直接 incrementLikesReceived/FavsReceived）。
- **潜在受害模块**：无。只有 `RelationEventProcessor` 经 Canal outbox。
- **数据完整性风险**：无。MySQL `following` 表落库正常，ucnt 是缓存层快照，300s 后必自愈。非数据损坏，仅显示延迟。
- **严重程度复核**：维持 P2。核心功能（关注/取关）DB 正常，仅计数显示延迟 300s 自愈。本地开发体验差，生产环境 Canal 开启则无此问题。

## 5. 修复方案

### 方案 A：counter 接口 followings/followers 段改实时读 DB（推荐）

- **做什么**：`RelationController.counter` 读 followings/followers 时不读 ucnt 对应段，改调 `relationMapper.countFollowingActive` / `countFollowerActive` 实时查 DB。posts/likedPosts/favedPosts 仍读 ucnt（它们写路径不经 Canal，正常）。
- **优点**：最小改动（只改 counter 接口读路径 2 段）；立即生效（不依赖 Canal）；与评论 likeCount 修法一致（读路径对齐写路径事实源）；DB count 方法已存在（`countFollowingActive`/`countFollowerActive`）。
- **缺点/风险**：每次调 counter 多 2 次 DB count 查询（之前读 ucnt 是 1 次 Redis GET）。但 counter 是低频接口（用户进主页才调），可接受。
- **影响面**：改 `RelationController.counter` 一个方法。

### 方案 B：开启 Canal（canal.enabled=true）

- **做什么**：`application.yml` 改 `canal.enabled: true`，起 Canal 容器。
- **优点**：正本清源——异步写链路恢复，ucnt 实时更新，counter 读 ucnt 正常。
- **缺点/风险**：需起 Canal 容器（额外基础设施，本地开发重）；attention.md 明确"Canal 默认 enabled=false 本地不需要"；只解决本地，生产本来就没这问题。
- **影响面**：改配置 + 起 Canal 容器。

### 方案 C：follow/unfollow 同步更新 ucnt

- **做什么**：`RelationManagerImpl.follow/unfollow` 写 MySQL 后，同步调 `userCounterService.incrementFollowings/Followers`（不等 outbox/Canal）。
- **优点**：绕开 Canal，ucnt 实时更新；改动小。
- **缺点/风险**：破坏 outbox 一致性设计（同步写 ucnt 失败怎么办？outbox 已发，ucnt 没更新，反而引入新不一致）；与架构设计相悖；关注操作变重（写 MySQL + 写 Redis + 发 outbox 三件事同步）。
- **影响面**：改 `RelationManagerImpl.follow/unfollow`。

### 推荐方案

**推荐方案 A**，理由：根因最直接（读路径对齐 DB 事实源，不依赖异步链路）；改动最小（只改 counter 接口 2 段读路径）；与评论 likeCount 修法一致（compound `counter-likecount-read-sds-not-mysql.md` 已立先例）；不引入新基础设施（Canal）也不破坏架构（同步写）。方案 B 治标但成本高且只解决本地。方案 C 破坏 outbox 设计引入新风险。

posts/likedPosts/favedPosts 不改（写路径正常，读 ucnt 没问题）。仅 followings/followers 改读 DB。

## 6. 归档决定（wontfix）

**owner 决定：不修，归档。**

**理由**：
- 此 bug **只在本地开发环境存在**（本地 `canal.enabled=false` 是有意取舍，attention.md 记录"Canal 默认 enabled=false 本地不需要"）。
- 生产环境 Canal 开启，异步写链路正常，ucnt 实时更新，counter 接口无此问题。
- 本地 300s 采样校验兜底自愈，非数据损坏，仅显示延迟。开发环境可接受。
- 方案 A（改读 DB）虽能修，但会让生产环境也退化为读 DB（放弃 ucnt 性能），与 ucnt 缓存层设计相悖；方案 B（开 Canal）需起 Canal 容器 + 配 binlog，本地开发成本高。
- 与评论 likeCount bug 不同：那个是写路径完全没实现（MySQL 死字段），必须修；这个是写路径实现了但依赖外部组件 Canal，本地关 Canal 是有意取舍。

**若日后本地开发需实时计数**：优先考虑方案 B（开 Canal，与生产一致），而非方案 A（改读路径破坏缓存设计）。

**复现/排查参考**：本地关注/取关后计数延迟 300s 属此 issue 已知行为，非新 bug。
