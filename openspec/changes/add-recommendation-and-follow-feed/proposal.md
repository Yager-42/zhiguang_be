# add-recommendation-and-follow-feed

## Why

当前 Feed 主要是公开列表和我的发布，缺少个性化推荐、热点、相似内容和关注流。内容社区需要结合强关系关注流和推荐候选，提升内容发现能力。

## What

- 首版接入 Gorse，但通过 `RecommendationEngine` Adapter 隔离外部服务。
- Adapter 只返回候选内容 ID、source，不返回完整 Feed 卡片。
- 关注流本地实现，不走 Gorse。
- 普通作者（< 阈值）push inbox，大 V（≥ 阈值）pull author_feed；两档。
- 不做活跃粉丝子集 push（读放大由缓存吸收，非 deferred）。
- 首页由本地混排关注流、Gorse 推荐候选和热点兜底。
- 发布、点赞、收藏、评论、关注事件投递给推荐和关注流。

## Impact

- 新增 Gorse 配置、客户端、Adapter 和失败降级策略。
- 新增关注流 Cassandra `feed_inbox`/`feed_author_feed`（TWCS、30 天 TTL）+ Redis cache-aside。
- 发布 pipeline 需要在帖子成功进入 `published` 后投递 `content_published`。
- 评论/点赞/收藏/关注需要投递用户反馈事件。
- 数据对齐服务需要补投 Gorse 物品、用户和反馈数据。

## Non-goals

- 不引入 TFRS、RecBole、EasyRec 等训练型框架。
- 不让 Gorse 负责关注流。
- 不让推荐 Adapter 直接返回完整 Feed 展示模型。
