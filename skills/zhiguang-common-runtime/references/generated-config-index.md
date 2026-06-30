# 配置索引（自动生成）

该文档从 `src/main/resources/application.yml` 和 `@ConfigurationProperties` 绑定类自动提取，用于确认真实配置键、默认值和 owner skill。改配置后请重新刷新生成资产。

```yaml
asset_metadata:
  asset_id: common-runtime.config-index
  skill: zhiguang-common-runtime
  generated_at: '2026-06-30T06:04:20Z'
  generator: python scripts/skills/refresh_generated_knowledge.py --asset common-runtime.config-index
  source_inputs:
  - src/main/resources/application.yml
  - src/main/java/com/tongji/**/*Properties.java
  matched_files_count: 12
  source_hash: ba4e6c11cbb6cd8125ed6017b65dfe4572cec870546ba78da886aca26920e8c0
  refresh_trigger:
  - application.yml change
  - '@ConfigurationProperties class change'
  manual_boundary: only config keys, defaults, owners, and binding classes
```

## 配置绑定类

| owner skill | prefix | class | file |
| --- | --- | --- | --- |
| `zhiguang-auth-user` | `auth` | `AuthProperties` | `src/main/java/com/tongji/auth/config/AuthProperties.java` |
| `zhiguang-common-runtime` | `id.segment` | `SegmentIdProperties` | `src/main/java/com/tongji/common/id/segment/SegmentIdProperties.java` |
| `zhiguang-common-runtime` | `id.snowflake` | `SnowflakeProperties` | `src/main/java/com/tongji/common/id/SnowflakeProperties.java` |
| `zhiguang-common-runtime` | `spring.elasticsearch` | `EsProperties` | `src/main/java/com/tongji/config/EsProperties.java` |
| `zhiguang-content-domain` | `cache` | `CacheProperties` | `src/main/java/com/tongji/cache/config/CacheProperties.java` |
| `zhiguang-content-domain` | `recommendation.gorse` | `GorseProperties` | `src/main/java/com/tongji/recommendation/gorse/GorseProperties.java` |
| `zhiguang-content-domain` | `storage` | `StorageProperties` | `src/main/java/com/tongji/storage/config/StorageProperties.java` |
| `zhiguang-platform-domain` | `moderation` | `ModerationProperties` | `src/main/java/com/tongji/moderation/config/ModerationProperties.java` |
| `zhiguang-platform-domain` | `promotion.bprime` | `PromotionBPrimeProperties` | `src/main/java/com/tongji/promotion/bprime/config/PromotionBPrimeProperties.java` |
| `zhiguang-platform-domain` | `promotion.slot-auction` | `PromotionProperties` | `src/main/java/com/tongji/promotion/config/PromotionProperties.java` |
| `zhiguang-platform-domain` | `wallet` | `WalletProperties` | `src/main/java/com/tongji/wallet/config/WalletProperties.java` |

## application.yml 关键配置

| key | value | owner skill |
| --- | --- | --- |
| `auth.jwt.private-key` | `classpath:keys/private.pem` | `zhiguang-auth-user` |
| `auth.jwt.public-key` | `classpath:keys/public.pem` | `zhiguang-auth-user` |
| `canal.batchSize` | `100` | `zhiguang-common-runtime` |
| `canal.destination` | `example` | `zhiguang-common-runtime` |
| `canal.enabled` | `False` | `zhiguang-common-runtime` |
| `canal.filter` | `zhiguang.outbox` | `zhiguang-common-runtime` |
| `canal.host` | `localhost` | `zhiguang-common-runtime` |
| `canal.intervalMs` | `1000` | `zhiguang-common-runtime` |
| `canal.password` | `` | `zhiguang-common-runtime` |
| `canal.port` | `11111` | `zhiguang-common-runtime` |
| `canal.username` | `` | `zhiguang-common-runtime` |
| `comment.kafka.feedback-topic` | `comment-feedback` | `zhiguang-social-domain` |
| `comment.kafka.write-topic` | `comment-write` | `zhiguang-social-domain` |
| `counter.rebuild.enabled` | `False` | `zhiguang-social-domain` |
| `feed.cache.author-head-ttl-seconds` | `120` | `zhiguang-content-domain` |
| `feed.cache.timeline-ttl-seconds` | `300` | `zhiguang-content-domain` |
| `feed.fanout.push-pull-threshold` | `10000` | `zhiguang-content-domain` |
| `feed.home.mixed-enabled` | `False` | `zhiguang-content-domain` |
| `feed.inbox.ttl-days` | `30` | `zhiguang-content-domain` |
| `id.snowflake.datacenter-id` | `${SNOWFLAKE_DATACENTER_ID:1}` | `zhiguang-common-runtime` |
| `id.snowflake.worker-id` | `${SNOWFLAKE_WORKER_ID:1}` | `zhiguang-common-runtime` |
| `management.endpoints.web.exposure.include` | `health,info` | `zhiguang-common-runtime` |
| `moderation.llm.enabled` | `${MODERATION_LLM_ENABLED:false}` | `zhiguang-platform-domain` |
| `moderation.llm.max-content-chars` | `${MODERATION_LLM_MAX_CONTENT_CHARS:4000}` | `zhiguang-platform-domain` |
| `moderation.llm.max-retries` | `${MODERATION_LLM_MAX_RETRIES:3}` | `zhiguang-platform-domain` |
| `moderation.llm.min-confidence` | `${MODERATION_LLM_MIN_CONFIDENCE:0.8000}` | `zhiguang-platform-domain` |
| `moderation.notification.platform-actor-user-id` | `${MODERATION_PLATFORM_ACTOR_USER_ID:0}` | `zhiguang-platform-domain` |
| `mybatis.configuration.map-underscore-to-camel-case` | `True` | `zhiguang-common-runtime` |
| `mybatis.mapper-locations` | `classpath*:mapper/**/*.xml` | `zhiguang-common-runtime` |
| `promotion.bprime.command-consumer-group` | `${PROMOTION_BPRIME_COMMAND_CONSUMER_GROUP:zhiguang-promotion-command-consumer}` | `zhiguang-platform-domain` |
| `promotion.bprime.command-topic` | `${PROMOTION_BPRIME_COMMAND_TOPIC:zhiguang_promotion_auction_commands_v2}` | `zhiguang-platform-domain` |
| `promotion.bprime.decision-topic` | `${PROMOTION_BPRIME_DECISION_TOPIC:zhiguang.promotion.auction.decisions.v2}` | `zhiguang-platform-domain` |
| `promotion.bprime.enabled` | `${PROMOTION_BPRIME_ENABLED:false}` | `zhiguang-platform-domain` |
| `promotion.bprime.fanout-consumer-group` | `${PROMOTION_BPRIME_FANOUT_CONSUMER_GROUP:zhiguang-promotion-fanout-consumer}` | `zhiguang-platform-domain` |
| `promotion.bprime.feed-reserve-price` | `${PROMOTION_FEED_RESERVE_PRICE:1}` | `zhiguang-platform-domain` |
| `promotion.bprime.feed-slot-count` | `${PROMOTION_FEED_TOP_SLOT_COUNT:1}` | `zhiguang-platform-domain` |
| `promotion.bprime.hot-state-ttl-seconds` | `${PROMOTION_BPRIME_HOT_STATE_TTL_SECONDS:86400}` | `zhiguang-platform-domain` |
| `promotion.bprime.kafka-send-timeout-ms` | `${PROMOTION_BPRIME_KAFKA_SEND_TIMEOUT_MS:10000}` | `zhiguang-platform-domain` |
| `promotion.bprime.projection-consumer-group` | `${PROMOTION_BPRIME_PROJECTION_CONSUMER_GROUP:zhiguang-promotion-projection-consumer}` | `zhiguang-platform-domain` |
| `promotion.bprime.search-reserve-price` | `${PROMOTION_SEARCH_RESERVE_PRICE:1}` | `zhiguang-platform-domain` |
| `promotion.bprime.search-slot-count` | `${PROMOTION_SEARCH_TOP_SLOT_COUNT:1}` | `zhiguang-platform-domain` |
| `promotion.bprime.settled-compensation-lookback-seconds` | `${PROMOTION_BPRIME_SETTLED_COMPENSATION_LOOKBACK_SECONDS:604800}` | `zhiguang-platform-domain` |
| `promotion.slot-auction.cache-ttl-seconds` | `${PROMOTION_CACHE_TTL_SECONDS:300}` | `zhiguang-platform-domain` |
| `promotion.slot-auction.close-window-delay-ms` | `${PROMOTION_CLOSE_WINDOW_DELAY_MS:30000}` | `zhiguang-platform-domain` |
| `promotion.slot-auction.feed-reserve-price` | `${PROMOTION_FEED_RESERVE_PRICE:1}` | `zhiguang-platform-domain` |
| `promotion.slot-auction.feed-top-slot-count` | `${PROMOTION_FEED_TOP_SLOT_COUNT:1}` | `zhiguang-platform-domain` |
| `promotion.slot-auction.search-reserve-price` | `${PROMOTION_SEARCH_RESERVE_PRICE:1}` | `zhiguang-platform-domain` |
| `promotion.slot-auction.search-top-slot-count` | `${PROMOTION_SEARCH_TOP_SLOT_COUNT:1}` | `zhiguang-platform-domain` |
| `promotion.slot-auction.settle-batch-size` | `${PROMOTION_SETTLE_BATCH_SIZE:50}` | `zhiguang-platform-domain` |
| `promotion.slot-auction.window-minutes` | `${PROMOTION_WINDOW_MINUTES:60}` | `zhiguang-platform-domain` |
| `recommendation.gorse.api-key` | `${GORSE_API_KEY:}` | `zhiguang-content-domain` |
| `recommendation.gorse.enabled` | `${GORSE_ENABLED:false}` | `zhiguang-content-domain` |
| `recommendation.gorse.endpoint` | `${GORSE_ENDPOINT:http://localhost:8087}` | `zhiguang-content-domain` |
| `recommendation.gorse.timeout-ms` | `${GORSE_TIMEOUT_MS:300}` | `zhiguang-content-domain` |
| `rocketmq.name-server` | `localhost:9876` | `zhiguang-platform-domain` |
| `rocketmq.producer.group` | `zhiguang-promotion-command-producer` | `zhiguang-platform-domain` |
| `server.port` | `8080` | `zhiguang-common-runtime` |
| `spring.ai.dashscope.api-key` | `${AI_DASHSCOPE_API_KEY:}` | `zhiguang-common-runtime` |
| `spring.ai.dashscope.chat.options.model` | `${AI_DASHSCOPE_MODEL:qwen-plus}` | `zhiguang-common-runtime` |
| `spring.application.name` | `zhiguang` | `zhiguang-common-runtime` |
| `spring.cassandra.connection.connect-timeout` | `10s` | `zhiguang-common-runtime` |
| `spring.cassandra.connection.init-query-timeout` | `10s` | `zhiguang-common-runtime` |
| `spring.cassandra.contact-points` | `localhost` | `zhiguang-common-runtime` |
| `spring.cassandra.keyspace-name` | `zhiguang` | `zhiguang-common-runtime` |
| `spring.cassandra.local-datacenter` | `datacenter1` | `zhiguang-common-runtime` |
| `spring.cassandra.port` | `9042` | `zhiguang-common-runtime` |
| `spring.cassandra.request.timeout` | `10s` | `zhiguang-common-runtime` |
| `spring.cassandra.schema-action` | `none` | `zhiguang-common-runtime` |
| `spring.data.redis.database` | `0` | `zhiguang-common-runtime` |
| `spring.data.redis.host` | `localhost` | `zhiguang-common-runtime` |
| `spring.data.redis.port` | `6379` | `zhiguang-common-runtime` |
| `spring.datasource.driver-class-name` | `com.mysql.cj.jdbc.Driver` | `zhiguang-common-runtime` |
| `spring.datasource.hikari.connection-timeout` | `30000` | `zhiguang-common-runtime` |
| `spring.datasource.hikari.maximum-pool-size` | `10` | `zhiguang-common-runtime` |
| `spring.datasource.hikari.minimum-idle` | `2` | `zhiguang-common-runtime` |
| `spring.datasource.password` | `zhiguang123456` | `zhiguang-common-runtime` |
| `spring.datasource.url` | `jdbc:mysql://localhost:3306/zhiguang?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false` | `zhiguang-common-runtime` |
| `spring.datasource.username` | `zhiguang` | `zhiguang-common-runtime` |
| `spring.elasticsearch.uris` | `http://localhost:9200` | `zhiguang-common-runtime` |
| `spring.kafka.admin.auto-create` | `True` | `zhiguang-common-runtime` |
| `spring.kafka.bootstrap-servers` | `localhost:9092` | `zhiguang-common-runtime` |
| `spring.kafka.consumer.auto-offset-reset` | `earliest` | `zhiguang-common-runtime` |
| `spring.kafka.consumer.enable-auto-commit` | `False` | `zhiguang-common-runtime` |
| `spring.kafka.consumer.key-deserializer` | `org.apache.kafka.common.serialization.StringDeserializer` | `zhiguang-common-runtime` |
| `spring.kafka.consumer.value-deserializer` | `org.apache.kafka.common.serialization.StringDeserializer` | `zhiguang-common-runtime` |
| `spring.kafka.listener.ack-mode` | `manual` | `zhiguang-common-runtime` |
| `spring.kafka.producer.key-serializer` | `org.apache.kafka.common.serialization.StringSerializer` | `zhiguang-common-runtime` |
| `spring.kafka.producer.value-serializer` | `org.apache.kafka.common.serialization.StringSerializer` | `zhiguang-common-runtime` |
| `storage.access-key` | `${MINIO_ACCESS_KEY:minioadmin}` | `zhiguang-content-domain` |
| `storage.bucket` | `${MINIO_BUCKET:zhiguang}` | `zhiguang-content-domain` |
| `storage.endpoint` | `${MINIO_ENDPOINT:http://localhost:9000}` | `zhiguang-content-domain` |
| `storage.public-domain` | `${MINIO_PUBLIC_DOMAIN:}` | `zhiguang-content-domain` |
| `storage.public-endpoint` | `${MINIO_PUBLIC_ENDPOINT:http://localhost:9000}` | `zhiguang-content-domain` |
| `storage.region` | `${MINIO_REGION:us-east-1}` | `zhiguang-content-domain` |
| `storage.secret-key` | `${MINIO_SECRET_KEY:minioadmin}` | `zhiguang-content-domain` |
| `wallet.platform-user-id` | `${WALLET_PLATFORM_USER_ID:0}` | `zhiguang-platform-domain` |
| `wallet.registration-grant-amount` | `${WALLET_REGISTRATION_GRANT_AMOUNT:100}` | `zhiguang-platform-domain` |
