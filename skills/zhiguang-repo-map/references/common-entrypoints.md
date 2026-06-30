# 常见入口

## Controller

- `src/main/java/com/tongji/auth/api/AuthController.java`
- `src/main/java/com/tongji/profile/api/ProfileController.java`
- `src/main/java/com/tongji/knowpost/api/KnowPostController.java`
- `src/main/java/com/tongji/search/api/SearchController.java`
- `src/main/java/com/tongji/storage/api/StorageController.java`
- `src/main/java/com/tongji/relation/api/RelationController.java`
- `src/main/java/com/tongji/comment/api/CommentController.java`
- `src/main/java/com/tongji/counter/api/ActionController.java`
- `src/main/java/com/tongji/counter/api/CounterController.java`
- `src/main/java/com/tongji/notification/api/NotificationController.java`
- `src/main/java/com/tongji/promotion/api/PromotionController.java`
- `src/main/java/com/tongji/wallet/api/WalletController.java`
- `src/main/java/com/tongji/moderation/api/ModerationReportController.java`
- `src/main/java/com/tongji/reconciliation/api/ReconciliationController.java`

## 关键 Service / Manager

- `src/main/java/com/tongji/knowpost/manager/PublishManagerImpl.java`
- `src/main/java/com/tongji/knowpost/service/impl/KnowPostServiceImpl.java`
- `src/main/java/com/tongji/recommendation/HomeFeedMixingService.java`
- `src/main/java/com/tongji/recommendation/feed/FollowFeedServiceImpl.java`
- `src/main/java/com/tongji/relation/manager/RelationManagerImpl.java`
- `src/main/java/com/tongji/comment/service/impl/CommentServiceImpl.java`
- `src/main/java/com/tongji/notification/service/impl/NotificationServiceImpl.java`
- `src/main/java/com/tongji/promotion/bprime/service/PromotionCommandProcessingService.java`
- `src/main/java/com/tongji/wallet/service/WalletService.java`
- `src/main/java/com/tongji/moderation/service/impl/ModerationReportServiceImpl.java`
- `src/main/java/com/tongji/reconciliation/service/impl/ReconciliationServiceImpl.java`

## 共享入口

- `src/main/java/com/tongji/common/web/GlobalExceptionHandler.java`
- `src/main/java/com/tongji/common/exception/ErrorCode.java`
- `src/main/java/com/tongji/common/id/DefaultIdService.java`
- `src/main/java/com/tongji/config/ThreadPoolConfig.java`
- `src/main/java/com/tongji/config/RedissonConfig.java`
- `src/main/java/com/tongji/config/ElasticsearchConfig.java`
