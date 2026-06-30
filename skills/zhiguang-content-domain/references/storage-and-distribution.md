# 存储、搜索与分发边界

## 覆盖包

- `src/main/java/com/tongji/storage/**`
- `src/main/java/com/tongji/search/**`
- `src/main/java/com/tongji/recommendation/**`
- `src/main/java/com/tongji/cache/**`

## 主要入口

- `src/main/java/com/tongji/storage/api/StorageController.java`
- `src/main/java/com/tongji/storage/MinioStorageService.java`
- `src/main/java/com/tongji/storage/text/CassandraTextStorageService.java`
- `src/main/java/com/tongji/search/api/SearchController.java`
- `src/main/java/com/tongji/search/service/impl/SearchServiceImpl.java`
- `src/main/java/com/tongji/recommendation/HomeFeedMixingService.java`
- `src/main/java/com/tongji/recommendation/feed/FollowFeedServiceImpl.java`
- `src/main/java/com/tongji/cache/hotkey/HotKeyDetector.java`

## 当前边界

- MinIO / S3：内容对象本体
- Cassandra：长文本 / feed 高吞吐存储
- Elasticsearch：搜索与联想视图
- Recommendation：推荐候选与分发，不是内容主记录
- Cache：热点保护和读侧加速，不是业务真相源
