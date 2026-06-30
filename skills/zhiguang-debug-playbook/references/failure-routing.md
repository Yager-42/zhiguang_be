# 故障现象分流

## 现象 -> 第一批入口

- 发帖成功但 feed / 搜索 / 推荐没看到
  - `PublishManagerImpl` `KnowPostFeedServiceImpl` `SearchServiceImpl` `HomeFeedMixingService`
- 评论状态卡住 / 评论写入不一致 / 重复评论
  - `CommentServiceImpl` `CommentWriteProducer` `CommentWriteConsumer`
- 关注成功但列表 / 计数 / 通知不一致
  - `RelationManagerImpl` `CanalOutboxConsumer` `CounterServiceImpl` `FollowNotificationConsumer`
- promotion / wallet / escrow / reconciliation 出错
  - `PromotionCommandProcessingService` `WalletService` `ReconciliationServiceImpl`
- 异常响应格式、错误码、ID、共享配置异常
  - `GlobalExceptionHandler` `ErrorCode` `DefaultIdService` `ThreadPoolConfig`

## 排障顺序

1. 真相源是什么
2. 当前坏的是主写、异步消费、派生结果，还是缓存
3. 哪一层最先出现与真相源不一致
4. 修复后是否需要重建 / rerun / retry
