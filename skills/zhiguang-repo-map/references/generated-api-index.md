# API 索引（自动生成）

该文档从 `src/main/java/com/tongji/**/*Controller.java` 自动提取，用于快速定位接口入口、路由前缀和显式鉴权点。改 controller 后请重新刷新生成资产。

```yaml
asset_metadata:
  asset_id: repo-map.api-index
  skill: zhiguang-repo-map
  generated_at: '2026-06-30T06:04:20Z'
  generator: python scripts/skills/refresh_generated_knowledge.py --asset repo-map.api-index
  source_inputs:
  - src/main/java/com/tongji/**/*Controller.java
  matched_files_count: 14
  source_hash: 66afa8469880c8d780e6266be1b4c9026f93385e1e2fe5ca638a2d2864ce1967
  refresh_trigger:
  - controller file change
  - route annotation change
  manual_boundary: only controller, route, and explicit authentication facts
```

| 模块 | Skill | 文件 | Controller | 映射 | 方法 | 显式鉴权 | 备注 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `auth` | `zhiguang-auth-user` | `src/main/java/com/tongji/auth/api/AuthController.java` | `AuthController` | `GET /api/v1/auth/me` | `me` | 需要登录 |  |
| `auth` | `zhiguang-auth-user` | `src/main/java/com/tongji/auth/api/AuthController.java` | `AuthController` | `POST /api/v1/auth/login` | `login` | 未显式声明 |  |
| `auth` | `zhiguang-auth-user` | `src/main/java/com/tongji/auth/api/AuthController.java` | `AuthController` | `POST /api/v1/auth/logout` | `logout` | 未显式声明 |  |
| `auth` | `zhiguang-auth-user` | `src/main/java/com/tongji/auth/api/AuthController.java` | `AuthController` | `POST /api/v1/auth/password/reset` | `resetPassword` | 未显式声明 |  |
| `auth` | `zhiguang-auth-user` | `src/main/java/com/tongji/auth/api/AuthController.java` | `AuthController` | `POST /api/v1/auth/register` | `register` | 未显式声明 |  |
| `auth` | `zhiguang-auth-user` | `src/main/java/com/tongji/auth/api/AuthController.java` | `AuthController` | `POST /api/v1/auth/send-code` | `sendCode` | 未显式声明 |  |
| `auth` | `zhiguang-auth-user` | `src/main/java/com/tongji/auth/api/AuthController.java` | `AuthController` | `POST /api/v1/auth/token/refresh` | `refresh` | 未显式声明 |  |
| `comment` | `zhiguang-social-domain` | `src/main/java/com/tongji/comment/api/CommentController.java` | `CommentController` | `DELETE /api/v1/comments/{commentId}` | `delete` | 未显式声明 |  |
| `comment` | `zhiguang-social-domain` | `src/main/java/com/tongji/comment/api/CommentController.java` | `CommentController` | `DELETE /api/v1/comments/{commentId}/like` | `unlike` | 未显式声明 |  |
| `comment` | `zhiguang-social-domain` | `src/main/java/com/tongji/comment/api/CommentController.java` | `CommentController` | `GET /api/v1/comments/{commentId}/replies` | `replies` | 未显式声明 |  |
| `comment` | `zhiguang-social-domain` | `src/main/java/com/tongji/comment/api/CommentController.java` | `CommentController` | `GET /api/v1/comments/{pendingCommentId}/status` | `status` | 未显式声明 |  |
| `comment` | `zhiguang-social-domain` | `src/main/java/com/tongji/comment/api/CommentController.java` | `CommentController` | `GET /api/v1/posts/{postId}/comments` | `comments` | 未显式声明 |  |
| `comment` | `zhiguang-social-domain` | `src/main/java/com/tongji/comment/api/CommentController.java` | `CommentController` | `POST /api/v1/comments/{commentId}/like` | `like` | 未显式声明 |  |
| `comment` | `zhiguang-social-domain` | `src/main/java/com/tongji/comment/api/CommentController.java` | `CommentController` | `POST /api/v1/posts/{postId}/comments` | `submit` | 未显式声明 |  |
| `counter` | `zhiguang-social-domain` | `src/main/java/com/tongji/counter/api/ActionController.java` | `ActionController` | `POST /api/v1/action/fav` | `fav` | 未显式声明 |  |
| `counter` | `zhiguang-social-domain` | `src/main/java/com/tongji/counter/api/ActionController.java` | `ActionController` | `POST /api/v1/action/like` | `like` | 未显式声明 |  |
| `counter` | `zhiguang-social-domain` | `src/main/java/com/tongji/counter/api/ActionController.java` | `ActionController` | `POST /api/v1/action/unfav` | `unfav` | 未显式声明 |  |
| `counter` | `zhiguang-social-domain` | `src/main/java/com/tongji/counter/api/ActionController.java` | `ActionController` | `POST /api/v1/action/unlike` | `unlike` | 未显式声明 |  |
| `counter` | `zhiguang-social-domain` | `src/main/java/com/tongji/counter/api/CounterController.java` | `CounterController` | `GET /api/v1/counter/{etype}/{eid}` | `getCounts` | 未显式声明 |  |
| `knowpost` | `zhiguang-content-domain` | `src/main/java/com/tongji/knowpost/api/KnowPostController.java` | `KnowPostController` | `DELETE /api/v1/knowposts/{id}` | `delete` | 未显式声明 |  |
| `knowpost` | `zhiguang-content-domain` | `src/main/java/com/tongji/knowpost/api/KnowPostController.java` | `KnowPostController` | `GET /api/v1/knowposts/detail/{id}` | `detail` | 未显式声明 |  |
| `knowpost` | `zhiguang-content-domain` | `src/main/java/com/tongji/knowpost/api/KnowPostController.java` | `KnowPostController` | `GET /api/v1/knowposts/feed` | `feed` | 未显式声明 |  |
| `knowpost` | `zhiguang-content-domain` | `src/main/java/com/tongji/knowpost/api/KnowPostController.java` | `KnowPostController` | `GET /api/v1/knowposts/feed/follow` | `followFeed` | 未显式声明 |  |
| `knowpost` | `zhiguang-content-domain` | `src/main/java/com/tongji/knowpost/api/KnowPostController.java` | `KnowPostController` | `GET /api/v1/knowposts/mine` | `mine` | 未显式声明 |  |
| `knowpost` | `zhiguang-content-domain` | `src/main/java/com/tongji/knowpost/api/KnowPostController.java` | `KnowPostController` | `GET /api/v1/knowposts/{id}/publish/status` | `publishStatus` | 未显式声明 | accepted |
| `knowpost` | `zhiguang-content-domain` | `src/main/java/com/tongji/knowpost/api/KnowPostController.java` | `KnowPostController` | `PATCH /api/v1/knowposts/{id}` | `patchMetadata` | 未显式声明 |  |
| `knowpost` | `zhiguang-content-domain` | `src/main/java/com/tongji/knowpost/api/KnowPostController.java` | `KnowPostController` | `PATCH /api/v1/knowposts/{id}/top` | `patchTop` | 未显式声明 |  |
| `knowpost` | `zhiguang-content-domain` | `src/main/java/com/tongji/knowpost/api/KnowPostController.java` | `KnowPostController` | `PATCH /api/v1/knowposts/{id}/visibility` | `patchVisibility` | 未显式声明 |  |
| `knowpost` | `zhiguang-content-domain` | `src/main/java/com/tongji/knowpost/api/KnowPostController.java` | `KnowPostController` | `POST /api/v1/knowposts/drafts` | `createDraft` | 需要登录 |  |
| `knowpost` | `zhiguang-content-domain` | `src/main/java/com/tongji/knowpost/api/KnowPostController.java` | `KnowPostController` | `POST /api/v1/knowposts/{id}/content/confirm` | `confirmContent` | 未显式声明 |  |
| `knowpost` | `zhiguang-content-domain` | `src/main/java/com/tongji/knowpost/api/KnowPostController.java` | `KnowPostController` | `POST /api/v1/knowposts/{id}/publish` | `publish` | 未显式声明 |  |
| `knowpost` | `zhiguang-content-domain` | `src/main/java/com/tongji/knowpost/api/KnowPostController.java` | `KnowPostController` | `POST /api/v1/knowposts/{id}/publish/{attemptId}/retry` | `retryPublish` | 未显式声明 |  |
| `moderation` | `zhiguang-platform-domain` | `src/main/java/com/tongji/moderation/api/ModerationReportController.java` | `ModerationReportController` | `POST /api/v1/moderation/reports` | `report` | 未显式声明 | accepted |
| `notification` | `zhiguang-social-domain` | `src/main/java/com/tongji/notification/api/NotificationController.java` | `NotificationController` | `GET /api/v1/notifications` | `list` | 需要登录 |  |
| `notification` | `zhiguang-social-domain` | `src/main/java/com/tongji/notification/api/NotificationController.java` | `NotificationController` | `GET /api/v1/notifications/unread-count` | `unreadCount` | 需要登录 |  |
| `notification` | `zhiguang-social-domain` | `src/main/java/com/tongji/notification/api/NotificationController.java` | `NotificationController` | `POST /api/v1/notifications/read-all` | `markAllRead` | 需要登录 |  |
| `notification` | `zhiguang-social-domain` | `src/main/java/com/tongji/notification/api/NotificationController.java` | `NotificationController` | `POST /api/v1/notifications/{notificationId}/read` | `markRead` | 需要登录 |  |
| `profile` | `zhiguang-auth-user` | `src/main/java/com/tongji/profile/api/ProfileController.java` | `ProfileController` | `PATCH /api/v1/profile` | `patch` | 需要登录 |  |
| `profile` | `zhiguang-auth-user` | `src/main/java/com/tongji/profile/api/ProfileController.java` | `ProfileController` | `POST /api/v1/profile/avatar` | `uploadAvatar` | 需要登录 |  |
| `promotion` | `zhiguang-platform-domain` | `src/main/java/com/tongji/promotion/api/PromotionController.java` | `PromotionController` | `GET /api/v1/promotions/allocations/active` | `getActiveAllocations` | 未显式声明 |  |
| `promotion` | `zhiguang-platform-domain` | `src/main/java/com/tongji/promotion/api/PromotionController.java` | `PromotionController` | `GET /api/v1/promotions/campaigns/{campaignId}` | `getCampaign` | 未显式声明 |  |
| `promotion` | `zhiguang-platform-domain` | `src/main/java/com/tongji/promotion/api/PromotionController.java` | `PromotionController` | `GET /api/v1/promotions/windows/{auctionWindowId}/snapshot` | `snapshot` | 未显式声明 |  |
| `promotion` | `zhiguang-platform-domain` | `src/main/java/com/tongji/promotion/api/PromotionController.java` | `PromotionController` | `POST /api/v1/promotions/campaigns` | `createCampaign` | 未显式声明 |  |
| `promotion` | `zhiguang-platform-domain` | `src/main/java/com/tongji/promotion/api/PromotionController.java` | `PromotionController` | `POST /api/v1/promotions/campaigns/{campaignId}/bids` | `submitBid` | 未显式声明 |  |
| `reconciliation` | `zhiguang-platform-domain` | `src/main/java/com/tongji/reconciliation/api/ReconciliationController.java` | `ReconciliationController` | `GET /api/v1/reconciliation/tasks` | `list` | 未显式声明 |  |
| `reconciliation` | `zhiguang-platform-domain` | `src/main/java/com/tongji/reconciliation/api/ReconciliationController.java` | `ReconciliationController` | `GET /api/v1/reconciliation/tasks/{id}` | `detail` | 未显式声明 |  |
| `reconciliation` | `zhiguang-platform-domain` | `src/main/java/com/tongji/reconciliation/api/ReconciliationController.java` | `ReconciliationController` | `POST /api/v1/reconciliation/targets/{type}/{id}/rerun` | `rerun` | 未显式声明 |  |
| `reconciliation` | `zhiguang-platform-domain` | `src/main/java/com/tongji/reconciliation/api/ReconciliationController.java` | `ReconciliationController` | `POST /api/v1/reconciliation/tasks/{id}/retry` | `retry` | 未显式声明 |  |
| `relation` | `zhiguang-social-domain` | `src/main/java/com/tongji/relation/api/RelationController.java` | `RelationController` | `GET /api/v1/relation/counter` | `counter` | 未显式声明 |  |
| `relation` | `zhiguang-social-domain` | `src/main/java/com/tongji/relation/api/RelationController.java` | `RelationController` | `GET /api/v1/relation/followers` | `followers` | 未显式声明 |  |
| `relation` | `zhiguang-social-domain` | `src/main/java/com/tongji/relation/api/RelationController.java` | `RelationController` | `GET /api/v1/relation/following` | `following` | 未显式声明 |  |
| `relation` | `zhiguang-social-domain` | `src/main/java/com/tongji/relation/api/RelationController.java` | `RelationController` | `GET /api/v1/relation/status` | `status` | 需要登录 |  |
| `relation` | `zhiguang-social-domain` | `src/main/java/com/tongji/relation/api/RelationController.java` | `RelationController` | `POST /api/v1/relation/follow` | `follow` | 需要登录 |  |
| `relation` | `zhiguang-social-domain` | `src/main/java/com/tongji/relation/api/RelationController.java` | `RelationController` | `POST /api/v1/relation/unfollow` | `unfollow` | 需要登录 |  |
| `search` | `zhiguang-content-domain` | `src/main/java/com/tongji/search/api/SearchController.java` | `SearchController` | `GET /api/v1/search` | `search` | 未显式声明 |  |
| `search` | `zhiguang-content-domain` | `src/main/java/com/tongji/search/api/SearchController.java` | `SearchController` | `GET /api/v1/search/suggest` | `suggest` | 未显式声明 |  |
| `storage` | `zhiguang-content-domain` | `src/main/java/com/tongji/storage/api/StorageController.java` | `StorageController` | `POST /api/v1/storage/presign` | `presign` | 未显式声明 |  |
| `wallet` | `zhiguang-platform-domain` | `src/main/java/com/tongji/wallet/api/WalletController.java` | `WalletController` | `GET /api/v1/wallet/me` | `me` | 需要登录 |  |
| `wallet` | `zhiguang-platform-domain` | `src/main/java/com/tongji/wallet/api/WalletController.java` | `WalletController` | `GET /api/v1/wallet/me/ledger` | `ledger` | 需要登录 |  |
