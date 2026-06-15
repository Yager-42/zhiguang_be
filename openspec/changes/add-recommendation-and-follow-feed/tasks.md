# Tasks

## 1. Recommendation Adapter

- [ ] 1.1 定义 `RecommendationEngine` 接口。
- [ ] 1.2 定义候选结果模型：`contentId`、`score`、`reason`、`source`。
- [ ] 1.3 实现 `GorseRecommendationAdapter`。
- [ ] 1.4 增加 Gorse endpoint、API key、timeout、fallback 配置。
- [ ] 1.5 实现 Gorse 不可用时的热点兜底。

## 2. Gorse Event Integration

- [ ] 2.1 消费 `content_published` 事件并异步 upsert Gorse item。
- [ ] 2.2 用户点赞、收藏、评论、关注时投递 feedback。
- [ ] 2.3 用户资料变化时同步用户画像字段。
- [ ] 2.4 增加推荐事件失败补偿任务。
- [ ] 2.5 确保只有成功 `publishing -> published` 的帖子触发 Gorse item 和关注流 fanout。

## 3. Follow Feed

- [ ] 3.1 实现 `feed:inbox:{userId}` 关注流收件箱。
- [ ] 3.2 实现 `feed:author:posts:{authorId}` 作者最近发布集合。
- [ ] 3.3 实现普通作者 fanout push。
- [ ] 3.4 实现大 V fanout pull。
- [ ] 3.5 实现超级大 V 不 push 到粉丝 inbox，但写入 `feed:author:posts:{authorId}` 或等价 pull 索引，并保持热点/推荐可见。
- [ ] 3.6 实现最近 30 天活跃粉丝优先 push。

## 4. Feed Mixing

- [ ] 4.1 实现候选 Hydration，补齐帖子、作者、计数、liked/faved。
- [ ] 4.2 实现可见性、删除状态和重复内容过滤。
- [ ] 4.3 实现关注流、推荐候选、热点内容混排。
- [ ] 4.4 暴露首页推荐 Feed 接口和关注流接口。

## 5. Verification

- [ ] 5.1 增加 Adapter 单元测试。
- [ ] 5.2 增加 Gorse fallback 测试。
- [ ] 5.3 增加普通作者 push、大 V pull、超级大 V 跳过 push 测试。
- [ ] 5.4 增加混排去重和可见性过滤测试。
