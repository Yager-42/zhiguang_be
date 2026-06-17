# Tasks

## 1. Recommendation Adapter

- [ ] 1.1 定义 `RecommendationEngine` 接口。
- [ ] 1.2 定义候选结果模型：`contentId`、`source`（score/reason 优先级填充混排不用，已删）。
- [ ] 1.3 实现 `GorseRecommendationAdapter`。
- [ ] 1.4 增加 Gorse endpoint、API key、timeout、fallback 配置。
- [ ] 1.5 实现 Gorse 不可用时的热点兜底。

## 2. Gorse Event Integration

- [ ] 2.1 消费 `content_published` 事件并异步 upsert Gorse item。
- [ ] 2.2 用户点赞、收藏、评论、关注时投递 feedback。
- [ ] 2.3 从资料更新和头像更新路径投递 `user_profile_updated` 事件，并消费该事件同步用户画像字段。
- [ ] 2.4 失败处理：不引入 `reconciliation_task`（`ReconciliationService` 尚不存在）；依赖 `canal-outbox` 重投 + 幂等 upsert 覆盖瞬时失败。
- [ ] 2.5 确保只有成功 `publishing -> published` 的帖子触发 Gorse item 和关注流 fanout。

## 3. Follow Feed

- [ ] 3.1 Cassandra `feed_inbox` / `feed_author_feed` 建表（TWCS、30 天 TTL、`gc_grace_seconds=0`），见 `db/cassandra/init.cql`。
- [ ] 3.2 `TimelineDispatcher` 消费 `canal-outbox` 过滤 `content_published`，按粉丝数软分级（两档）。
- [ ] 3.3 `TimelineExecutor`（`CqlSession.executeAsync`，inflight ≤256，每消息截止超时 + 任意失败 no-ack）：普通作者 keyset 分页 push `feed_inbox`（不写 author_feed）；大 V 仅写 `feed_author_feed`；`publish_ts` 事件捕获、幂等重放。
- [ ] 3.4 读路径：`feed:timeline:{userId}` cache-aside（无 single-flight — per-user key）+ inbox/author_feed 切片（每路 `LIMIT 20`）多路归并 + 读时修复（`status='published'` AND `visible∈{public,followers}`，school 不进关注流）+ 游标 `(publish_ts, content_id)`。
- [ ] 3.5 粉丝分页由 `LIMIT/OFFSET` 改 keyset `(created_at, from_user_id)`。
- [ ] 3.6 明确不做：活跃子集 push、关注历史回填、feed 表主动 DELETE、大V聚合池（均非必需，见 design.md 决策 3/4 与 spec.md）。

## 4. Feed Mixing

- [ ] 4.1 实现候选 Hydration，补齐帖子、作者、计数、liked/faved。
- [ ] 4.2 实现可见性、删除状态和重复内容过滤。
- [ ] 4.3 实现关注流、推荐候选、热点内容混排。
- [ ] 4.4 暴露首页推荐 Feed 接口和关注流接口。

## 5. Verification

- [ ] 5.1 增加 Adapter 单元测试。
- [ ] 5.2 增加 Gorse fallback 测试。
- [ ] 5.3 增加普通作者 inbox-only push、大 V author_feed-only pull 测试（两档，无超级大 V）。
- [ ] 5.4 增加混排去重和可见性过滤测试。
