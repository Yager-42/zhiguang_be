# 内容生命周期

## 覆盖包

- `src/main/java/com/tongji/knowpost/**`

## 主要入口

- `src/main/java/com/tongji/knowpost/api/KnowPostController.java`
- `src/main/java/com/tongji/knowpost/service/impl/KnowPostServiceImpl.java`
- `src/main/java/com/tongji/knowpost/manager/PublishManagerImpl.java`
- `src/main/java/com/tongji/knowpost/listener/FeedCacheInvalidationListener.java`

## 已确认语义

- `status`: `draft | publishing | published | publish_failed | deleted`
- `visible`: `public | followers | school | private | unlisted`
- follow feed 只包含 `published`
- `normal author` 走 push fanout 到 `inbox`
- `large author` 只写 `author_feed`，读时 pull
