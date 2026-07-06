---
doc_type: issue-report
issue: 2026-07-06-relation-counter-not-refreshing
status: wontfix
severity: P2
reported: 2026-07-06
tags: [counter, relation, follow, sds, canal]
---

# 关注/取关后用户计数不刷新 问题报告

## 1. 现象描述

用户关注或取关后，"我的"页的关注/粉丝计数不实时刷新，要等约 300 秒才自愈。

具体场景：user14 取关了用户 8（DB 已 rel_status=0），但"我的"页关注计数仍显示 3（应为 2）。

## 2. 复现步骤

1. user14 登录（手机 13982992595）
2. 进任意帖子详情页，关注/取关作者
3. 立即回"我的"页看关注计数
4. 计数显示旧值（关注/取关操作前的值），不实时反映新状态
5. 等约 300 秒后计数自愈成正确值

## 3. 期望行为

关注/取关后，"我的"页关注/粉丝计数应实时反映新状态（用户操作的即时反馈，不应等 300s）。

## 4. 已排查证据

### 现场数据（2026-07-06 15:28）

- **DB `following` 表**：user14 取关了 id=8（`rel_status=0`），有效关注 6、7 → 真实计数 = **2**
- **Redis `ucnt:14`**：followings 段（前 4 字节大端）= `0000 0003` = **3**（旧值）
- **`ucnt:chk:14` 采样锁**：TTL=176s（300s 内刚校验过，校验时 DB 还是 3，所以 ucnt 被校准成 3）
- **counter 接口返回**：`followings: 3`（读 ucnt 旧值）

### 链路事实（已读源码）

- **读路径**：`RelationController.counter`（:119-175）从 Redis `ucnt:{userId}`（SDS 二进制快照）读计数
- **写路径**：`RelationManager.follow/unfollow` → 写 MySQL `following` 表 → 发 outbox → **Canal 桥接** → Kafka outbox topic → `CanalOutboxConsumer` → `RelationEventProcessor.process` → `userCounterService.incrementFollowings/Followers` 更新 ucnt
- **配置**：`application.yml` `canal.enabled: false`（Canal 默认关闭，attention.md 记录"Canal/Gorse 默认 enabled=false 本地不需要"）
- **兜底**：counter 接口每 300s 采样校验（`ucnt:chk:{userId}` 锁）对比 SDS vs DB，不一致 rebuild——所以 300s 后自愈

### 与历史 bug 同类

- `.codestable/compound/counter-likecount-read-sds-not-mysql.md` 记录的评论 likeCount bug 是同一模式：写路径异步更新 SDS，读路径读 SDS，异步链路断→读旧值
- 评论 likeCount 修法：读路径改读 counter SDS（那会写路径正常）；本 issue 是 ucnt 写路径断（Canal 关）

## 5. 影响面

- **影响范围**：所有关注/取关操作的用户计数（关注数 followings + 粉丝数 followers）显示延迟 300s
- **潜在受害**：`posts`/`likedPosts`/`favedPosts` 等其它 ucnt 段若也走 Canal 异步更新，同样受影响（待 analyze 确认）
- **严重程度**：P2。核心功能（关注/取关）DB 落库正常，仅计数显示延迟，300s 自愈。非数据损坏，但用户操作的即时反馈缺失，体验差。

## 6. 根因方向（待 analyze 确认）

**主根因**：用户计数读路径（counter 接口读 `ucnt` SDS）与写路径（关注/取关经 Canal 异步更新 ucnt）脱节。本地 Canal 关闭 → 异步写链路断 → ucnt 不实时更新 → counter 读旧值。300s 采样校验兜底自愈。

**候选修复方案**（analyze 阶段定）：
1. 开启 Canal（`canal.enabled=true`）——正本清源但需起 Canal 容器
2. follow/unfollow 同步更新 ucnt——绕开 Canal，简单但破坏 outbox 一致性设计
3. counter 接口改实时读 DB——对齐评论 likeCount 修法，最小改动，放弃 SDS 性能
