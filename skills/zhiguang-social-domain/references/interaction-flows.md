# 社交互动流转

## 覆盖包

- `src/main/java/com/tongji/relation/**`
- `src/main/java/com/tongji/comment/**`
- `src/main/java/com/tongji/counter/**`
- `src/main/java/com/tongji/notification/**`

## 主要入口

- `src/main/java/com/tongji/relation/api/RelationController.java`
- `src/main/java/com/tongji/relation/manager/RelationManagerImpl.java`
- `src/main/java/com/tongji/relation/outbox/CanalOutboxConsumer.java`
- `src/main/java/com/tongji/comment/api/CommentController.java`
- `src/main/java/com/tongji/comment/service/impl/CommentServiceImpl.java`
- `src/main/java/com/tongji/comment/consumer/CommentWriteConsumer.java`
- `src/main/java/com/tongji/counter/api/ActionController.java`
- `src/main/java/com/tongji/counter/service/impl/CounterServiceImpl.java`
- `src/main/java/com/tongji/notification/api/NotificationController.java`
- `src/main/java/com/tongji/notification/consumer/CommentNotificationConsumer.java`

## 已确认事实

- 评论存在异步写链路：`CommentWriteProducer -> Kafka -> CommentWriteConsumer`
- 评论写入后会联动计数与反馈事件
- 关系链路包含 outbox / Canal / Kafka 异步传播
- 计数系统是独立聚合层，不等于互动主记录
- 通知消费 follow / comment / like 等副作用事件
