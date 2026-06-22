# B' Position Auctions Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace paid boost and direct MySQL promotion bidding with bytedance-style B' fixed-position auctions for feed/search commercial slots.

**Architecture:** Keep zhiguang as one Spring Boot module. First delete paid boost surface area, then add B' path in thin slices: RocketMQ ordered command, Redis Lua hot decision, Kafka decision log, MySQL projection, wallet settlement, allocation read path. Do not build WebSocket first; snapshot API is enough to prove recovery.

**Tech Stack:** Java 21, Spring Boot 3.2.4, MyBatis, MySQL 8, Redis Lua, Spring Kafka, Apache RocketMQ Spring Boot starter 2.3.5, JUnit 5, Mockito, AssertJ, Docker Compose.

---

## Frozen Junior Artifact

Under review: `openspec/changes/add-bprime-position-auctions/tasks.md`.

Key tasks there:

- Remove active `add-paid-boost-promotions`.
- Add RocketMQ, config, B' command tables.
- Implement HTTP bid command -> RocketMQ -> Redis Lua -> Kafka decision log -> MySQL projection.
- Integrate wallet hold/capture/release.
- Add snapshot/realtime/read path/reconciliation/tests.

## Senior Review

**Altitude diagnosis:** mixed. Correct product direction, but original tasks fog hardest interfaces and overbuild realtime/reconciliation before first useful B' slice.

### Blockers

- [B1] Paid boost code still exists even after OpenSpec deletion. Evidence: `src/main/java/com/tongji/promotion/api/PaidBoostController.java`, `src/main/java/com/tongji/promotion/service/PaidBoostCampaignService.java`, `db/schema.sql:376`. Fix: first milestone deletes paid boost code/schema/tests and removes feed ranking hooks.
- [B2] Current bid API mutates wallet and MySQL directly. Evidence: `PromotionCampaignService.submitBid` holds wallet and inserts bid in one transaction at `src/main/java/com/tongji/promotion/service/PromotionCampaignService.java:81`. Fix: replace with command submission API before adding Redis/Kafka; old direct path must become internal test-only or removed.
- [B3] Original tasks say "RocketMQ ordered command" but not exact implementation. Evidence: no RocketMQ dependency in `pom.xml:160`, no RocketMQ service in `docker-compose.yml`; bytedance uses `RocketMQTemplate.syncSendOrderly(...)` in `/Volumes/lexar/revive/bytedance/backend/live-auction-infrastructure/src/main/java/cn/revive/liveauction/infrastructure/adapter/port/RocketMqAuctionCommandMessagePort.java:34`. Fix: use `rocketmq-spring-boot-starter:2.3.5`, `syncSendOrderly(topic, command, auctionWindowId)`, `@RocketMQMessageListener(... consumeMode = ORDERLY)`.
- [B4] Decision confirmation boundary unclear. Spring Kafka docs for 4.1.0 show `KafkaTemplate.send(topic, key, data)` returns `CompletableFuture<SendResult<K,V>>` and support timed `get()` for blocking confirmation (official docs, accessed 2026-06-22: https://docs.spring.io/spring-kafka/reference/kafka/sending-messages.html). Fix: command processor must append decision to Kafka and fail command confirmation if append fails.

### Major

- [M1] Realtime/WebSocket is premature. Spec says snapshot restores state; WebSocket optional. Fix: implement snapshot API first, defer WebSocket/SSE until after projection works.
- [M2] Full reconciliation too early. Existing `reconciliation_task` supports repair tasks at `db/schema.sql:144`, but B' first needs deterministic projection/checkpoint. Fix: first add projection idempotency and one repair scan for decision-without-projection; drift rebuild later.
- [M3] Plan must reuse bytedance shapes, not copy product/live-room terms. Evidence: bytedance files: `/Volumes/lexar/revive/bytedance/backend/live-auction-domain/src/main/java/cn/revive/liveauction/domain/highconcurrency/AuctionCommand.java`, `AuctionDecision.java`, `WalletEffect.java`; zhiguang terms live in `PromotionAuctionWindow`. Fix: create zhiguang DTOs named `PromotionAuctionCommand`, `PromotionAuctionDecision`, `PromotionWalletEffect`.
- [M4] Existing Kafka convention is string JSON, not object payload. Evidence: `src/main/java/com/tongji/counter/config/CounterConfig.java:28` provides `KafkaTemplate<String,String>` and `src/main/java/com/tongji/counter/event/CounterEventProducer.java:23` serializes with `ObjectMapper`. Fix: B' Kafka port sends JSON string with key `auctionWindowId`.
- [M5] RocketMQ local compose must use a broker config file. Evidence: Apache RocketMQ 5.3.2 quickstart starts broker with `mqbroker -n localhost:9876` (official docs, accessed 2026-06-22: https://rocketmq.apache.org/docs/quickStart/01quickstart/); bytedance E2E writes `/tmp/broker.conf` then runs `mqbroker -c /tmp/broker.conf` in `/Volumes/lexar/revive/bytedance/backend/live-auction-app/src/test/java/cn/revive/liveauction/AuctionBPrimeDockerE2ETest.java:137`. Fix: compose mounts broker config and uses `-c`, not positional `autoCreateTopicEnable=true`.

### What junior got right

- Correctly kills paid boost.
- Correctly preserves fixed `slotCount` + GSP allocation.
- Correctly separates hot decision from feed/search read path.
- Correctly names RocketMQ commands and Kafka decisions as separate logs.

## Promoted Plan (v2)

### Product Goal and Non-Goals

Creators bid virtual currency for fixed feed/search commercial positions. System processes bid commands through B' chain and produces durable slot allocations. Feed/search consume allocations only.

Out of scope:

- paid boost / paid ranking weight
- product/live-room/account/auth_session from bytedance
- WebSocket as required MVP path
- query targeting for search slots
- multi-cluster SLA

### Load-Bearing Decisions

1. **RocketMQ starter:** use `org.apache.rocketmq:rocketmq-spring-boot-starter:2.3.5`.  
Evidence: bytedance uses same version in `/Volumes/lexar/revive/bytedance/backend/pom.xml:29`; repo currently has no RocketMQ in `pom.xml:160`. Bytedance command producer uses `syncSendOrderly` with an ordering key; mirror that API.

2. **Kafka decision log:** use existing Spring Kafka dependency.  
Evidence: repo already has `spring-kafka` in `pom.xml:160`; Spring Kafka docs show `KafkaTemplate.send(topic, key, data)` returns future. Existing repo convention is `KafkaTemplate<String,String>` plus `ObjectMapper`; use key = `auctionWindowId`, value = decision JSON.

3. **MVP confirmation boundary:** HTTP confirms only command durability/submission; processor confirms decision durability only after Redis decision + Kafka append succeeds.  
Evidence: bytedance `KafkaAuctionDecisionLogPort` waits on send future and throws on timeout. Do not return auction win/loss from HTTP submit.

4. **Projection first, WebSocket later:** snapshot API proves recovery; WebSocket can consume same decision log later.  
Evidence: OpenSpec says snapshot restores state; no current WebSocket dependency in repo.

5. **Schema evolution:** append B' columns/tables; do not drop old promotion auction tables until B' projection tests pass. Delete paid boost tables because user explicitly removed product path.

6. **Startup safety:** keep `promotion.bprime.enabled=false` by default and register RocketMQ producer/listeners with `@ConditionalOnProperty(name = "promotion.bprime.enabled", havingValue = "true")`. RocketMQ connection config may exist locally, but no B' bean may require a live broker until enabled.

7. **Wallet effect timing:** accepted bid `HOLD` is executed in the command processor after Redis accept and before Kafka append. If Kafka append fails after hold succeeds, processor immediately releases the hold with `promotion-bprime:{commandId}:hold-release-after-log-fail` and marks command `LOG_FAILED`. Projection only performs close-window `CAPTURE`/`RELEASE`, not initial hold.

## File Map

### Delete paid boost files

- Delete: `src/main/java/com/tongji/promotion/api/PaidBoostController.java`
- Delete: `src/main/java/com/tongji/promotion/api/dto/CreatePaidBoostCampaignRequest.java`
- Delete: `src/main/java/com/tongji/promotion/api/dto/PaidBoostCampaignResponse.java`
- Delete: `src/main/java/com/tongji/promotion/api/dto/PaidBoostDeliverySummaryResponse.java`
- Delete: `src/main/java/com/tongji/promotion/config/PaidBoostProperties.java`
- Delete: `src/main/java/com/tongji/promotion/mapper/PaidBoostCampaignMapper.java`
- Delete: `src/main/java/com/tongji/promotion/mapper/PaidBoostDeliveryMapper.java`
- Delete: `src/main/java/com/tongji/promotion/model/PaidBoost*.java`
- Delete: `src/main/java/com/tongji/promotion/schedule/PaidBoostScheduler.java`
- Delete: `src/main/java/com/tongji/promotion/service/PaidBoost*.java`
- Delete: `src/main/resources/mapper/PaidBoostCampaignMapper.xml`
- Delete: `src/main/resources/mapper/PaidBoostDeliveryMapper.xml`
- Delete: `src/test/java/com/tongji/promotion/PaidBoostMysqlIntegrationTest.java`
- Delete: `src/test/java/com/tongji/promotion/api/PaidBoostControllerTest.java`
- Delete: `src/test/java/com/tongji/promotion/schedule/PaidBoostSchedulerTest.java`
- Delete: `src/test/java/com/tongji/promotion/service/PaidBoostCacheServiceTest.java`
- Delete: `src/test/java/com/tongji/promotion/service/PaidBoostCampaignServiceTest.java`
- Delete: `src/test/java/com/tongji/promotion/service/PaidBoostDeliveryServiceTest.java`
- Delete: `src/test/java/com/tongji/promotion/service/PaidBoostRankingServiceTest.java`
- Delete: `src/test/java/com/tongji/promotion/service/PaidBoostSettlementServiceTest.java`
- Modify: `src/test/java/com/tongji/recommendation/HomeFeedMixingServiceTest.java`
- Modify: `src/test/java/com/tongji/knowpost/api/KnowPostControllerPublishTest.java`

### Modify current promotion path

- Modify: `db/schema.sql`
- Modify: `pom.xml`
- Modify: `docker-compose.yml`
- Modify: `src/main/resources/application.yml`
- Modify: `src/main/java/com/tongji/promotion/api/PromotionController.java`
- Modify: `src/main/java/com/tongji/promotion/service/PromotionCampaignService.java`
- Modify: `src/main/java/com/tongji/promotion/service/PromotionAuctionService.java`
- Modify: `src/main/java/com/tongji/wallet/model/WalletLedgerReason.java`
- Modify: `src/main/java/com/tongji/wallet/service/WalletService.java`
- Modify: `src/main/java/com/tongji/recommendation/HomeFeedMixingService.java`
- Modify: `src/main/java/com/tongji/search/service/impl/SearchServiceImpl.java`

### Create B' files

- Create: `src/main/java/com/tongji/promotion/bprime/model/PromotionAuctionCommand.java`
- Create: `src/main/java/com/tongji/promotion/bprime/model/PromotionAuctionDecision.java`
- Create: `src/main/java/com/tongji/promotion/bprime/model/PromotionRankingItem.java`
- Create: `src/main/java/com/tongji/promotion/bprime/model/PromotionDecisionType.java`
- Create: `src/main/java/com/tongji/promotion/bprime/model/PromotionWalletEffect.java`
- Create: `src/main/java/com/tongji/promotion/bprime/model/PromotionAuctionSnapshot.java`
- Create: `src/main/java/com/tongji/promotion/bprime/service/PromotionCommandSubmissionService.java`
- Create: `src/main/java/com/tongji/promotion/bprime/service/PromotionCommandProcessingService.java`
- Create: `src/main/java/com/tongji/promotion/bprime/service/PromotionDecisionProjectionService.java`
- Create: `src/main/java/com/tongji/promotion/bprime/service/PromotionSnapshotService.java`
- Create: `src/main/java/com/tongji/promotion/bprime/mq/PromotionCommandMessagePort.java`
- Create: `src/main/java/com/tongji/promotion/bprime/mq/RocketMqPromotionCommandMessagePort.java`
- Create: `src/main/java/com/tongji/promotion/bprime/mq/PromotionCommandRocketMqListener.java`
- Create: `src/main/java/com/tongji/promotion/bprime/kafka/PromotionDecisionLogPort.java`
- Create: `src/main/java/com/tongji/promotion/bprime/kafka/KafkaPromotionDecisionLogPort.java`
- Create: `src/main/java/com/tongji/promotion/bprime/kafka/PromotionDecisionProjectionKafkaListener.java`
- Create: `src/main/java/com/tongji/promotion/bprime/redis/PromotionRedisDecisionAdapter.java`
- Create: `src/main/java/com/tongji/promotion/bprime/redis/PromotionAuctionRedisKeys.java`
- Create: `src/main/resources/redis/lua/promotion-auction-decision.lua`
- Create: mapper/model files for `promotion_auction_command`, `promotion_auction_decision`, `promotion_projection_checkpoint`.

## Data Shapes

```java
public record PromotionAuctionCommand(
        String commandId,
        String idempotencyKey,
        String requestHash,
        long auctionWindowId,
        long campaignId,
        long bidderUserId,
        long postId,
        String resourceType,
        long bidAmount,
        String type,
        Instant submittedAt
) {}
```

```java
public record PromotionAuctionDecision(
        String decisionId,
        String commandId,
        String requestHash,
        long auctionWindowId,
        long campaignId,
        long bidderUserId,
        long postId,
        String resourceType,
        String decisionType,
        boolean accepted,
        String rejectionReason,
        long bidAmount,
        List<PromotionRankingItem> ranking,
        List<PromotionWalletEffect> walletEffects,
        Instant decidedAt
) {}
```

```java
public record PromotionWalletEffect(
        long ownerUserId,
        long amount,
        String effectType, // HOLD, CAPTURE, RELEASE
        String businessRef
) {}
```

```java
public record PromotionRankingItem(
        long campaignId,
        long bidderUserId,
        long postId,
        long bidAmount,
        int rank
) {}
```

Redis keys:

```text
promotion:auction:{auctionWindowId}:state
promotion:auction:{auctionWindowId}:commands
promotion:auction:{auctionWindowId}:ranking
promotion:auction:{auctionWindowId}:bidder:{userId}
```

Kafka/RocketMQ:

```text
RocketMQ topic: zhiguang_promotion_auction_commands_v2
RocketMQ orderly key: auctionWindowId
Kafka topic: zhiguang.promotion.auction.decisions.v2
Kafka key: auctionWindowId
```

## Milestones

### Milestone 1: Delete Paid Boost Surface

Success: app compiles, OpenSpec active list has no `add-paid-boost-promotions`, no `PaidBoost` classes remain.

#### Task 1.1: Remove paid boost code and schema

**Files:**
- Delete paid boost files listed above
- Modify: `db/schema.sql`
- Modify: `src/main/resources/application.yml`
- Test: `src/test/java/com/tongji/promotion/PromotionSchemaContractTest.java`

- [ ] **Step 1: Delete paid boost Java/XML/test files**

Run:

```bash
rm -f src/main/java/com/tongji/promotion/api/PaidBoostController.java
rm -f src/main/java/com/tongji/promotion/api/dto/CreatePaidBoostCampaignRequest.java
rm -f src/main/java/com/tongji/promotion/api/dto/PaidBoostCampaignResponse.java
rm -f src/main/java/com/tongji/promotion/api/dto/PaidBoostDeliverySummaryResponse.java
rm -f src/main/java/com/tongji/promotion/config/PaidBoostProperties.java
rm -f src/main/java/com/tongji/promotion/mapper/PaidBoostCampaignMapper.java
rm -f src/main/java/com/tongji/promotion/mapper/PaidBoostDeliveryMapper.java
rm -f src/main/java/com/tongji/promotion/model/PaidBoost*.java
rm -f src/main/java/com/tongji/promotion/schedule/PaidBoostScheduler.java
rm -f src/main/java/com/tongji/promotion/service/PaidBoost*.java
rm -f src/main/resources/mapper/PaidBoostCampaignMapper.xml
rm -f src/main/resources/mapper/PaidBoostDeliveryMapper.xml
rm -f src/test/java/com/tongji/promotion/PaidBoostMysqlIntegrationTest.java
rm -f src/test/java/com/tongji/promotion/api/PaidBoostControllerTest.java
rm -f src/test/java/com/tongji/promotion/schedule/PaidBoostSchedulerTest.java
rm -f src/test/java/com/tongji/promotion/service/PaidBoostCacheServiceTest.java
rm -f src/test/java/com/tongji/promotion/service/PaidBoostCampaignServiceTest.java
rm -f src/test/java/com/tongji/promotion/service/PaidBoostDeliveryServiceTest.java
rm -f src/test/java/com/tongji/promotion/service/PaidBoostRankingServiceTest.java
rm -f src/test/java/com/tongji/promotion/service/PaidBoostSettlementServiceTest.java
```

Expected: files removed.

- [ ] **Step 2: Remove paid boost tables**

Edit `db/schema.sql`: delete `promotion_boost_campaign` and `promotion_boost_delivery` blocks.

Expected: `rg "promotion_boost|paid_boost|PaidBoost" db src/main` returns no hits except docs/plans.

- [ ] **Step 3: Remove config**

Edit `src/main/resources/application.yml`: remove `promotion.paid-boost` block if present.

- [ ] **Step 4: Run compile**

Run:

```bash
mvn -DskipTests compile
```

Expected: PASS after removing all `PaidBoost*` imports, constructor arguments, mocks, and assertions from feed/search tests.

- [ ] **Step 4.1: Remove paid boost constructor/test wiring**

Edit `HomeFeedMixingService` to remove:

- `PaidBoostCacheService`
- `PaidBoostRankingService`
- `PaidBoostDeliveryService`
- `homeBoostsByPostId`
- `markBoosted`
- `recordHomeDeliveries`
- `home_recommendation_boost`

Edit `HomeFeedMixingServiceTest` and `KnowPostControllerPublishTest` so constructors no longer pass paid boost mocks and assertions only cover fixed slot promotion markers.

- [ ] **Step 5: Commit**

```bash
git add db/schema.sql src/main src/test src/main/resources/application.yml
git commit -m "refactor: remove paid boost promotion path"
```

### Milestone 2: Add B' Infrastructure Skeleton

Success: RocketMQ starts in Docker; app starts with B' config disabled/enabled; no auction behavior changed yet.

#### Task 2.1: Add RocketMQ dependency/config

**Files:**
- Modify: `pom.xml`
- Modify: `docker-compose.yml`
- Modify: `src/main/resources/application.yml`
- Create: `src/main/java/com/tongji/promotion/bprime/config/PromotionBPrimeProperties.java`

- [ ] **Step 1: Add Maven dependency**

Add to `pom.xml`:

```xml
<dependency>
    <groupId>org.apache.rocketmq</groupId>
    <artifactId>rocketmq-spring-boot-starter</artifactId>
    <version>2.3.5</version>
</dependency>
```

- [ ] **Step 2: Add Docker Compose RocketMQ**

Add `rocketmq-namesrv` and `rocketmq-broker` services. Minimal local compose only.

```yaml
  # Add this file beside docker-compose.yml:
  # ./infra/rocketmq/broker.conf
  #
  # brokerClusterName = DefaultCluster
  # brokerName = broker-a
  # brokerId = 0
  # namesrvAddr = rocketmq-namesrv:9876
  # brokerIP1 = 127.0.0.1
  # listenPort = 10911
  # autoCreateTopicEnable = true
  # autoCreateSubscriptionGroup = true
  # brokerRole = ASYNC_MASTER
  # flushDiskType = ASYNC_FLUSH
  # storePathRootDir = /tmp/rocketmq/store
  # storePathCommitLog = /tmp/rocketmq/store/commitlog

  rocketmq-namesrv:
    image: apache/rocketmq:5.3.2
    container_name: zhiguang-rocketmq-namesrv
    command: ["sh", "mqnamesrv"]
    ports:
      - "9876:9876"
    healthcheck:
      test: ["CMD-SHELL", "sh mqadmin clusterList -n localhost:9876 >/dev/null 2>&1"]
      interval: 10s
      timeout: 10s
      retries: 20

  rocketmq-broker:
    image: apache/rocketmq:5.3.2
    container_name: zhiguang-rocketmq-broker
    depends_on:
      rocketmq-namesrv:
        condition: service_healthy
    command: ["sh", "mqbroker", "-c", "/tmp/broker.conf"]
    volumes:
      - ./infra/rocketmq/broker.conf:/tmp/broker.conf:ro
    ports:
      - "10911:10911"
```

- [ ] **Step 3: Add application config**

Add:

```yaml
rocketmq:
  name-server: localhost:9876
  producer:
    group: zhiguang-promotion-command-producer

promotion:
  bprime:
    enabled: false
    command-topic: zhiguang_promotion_auction_commands_v2
    command-consumer-group: zhiguang-promotion-command-consumer
    decision-topic: zhiguang.promotion.auction.decisions.v2
    projection-consumer-group: zhiguang-promotion-projection-consumer
    kafka-send-timeout-ms: 10000
```

- [ ] **Step 4: Add properties class**

Create `PromotionBPrimeProperties`:

```java
package com.tongji.promotion.bprime.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "promotion.bprime")
public class PromotionBPrimeProperties {
    private boolean enabled;
    private String commandTopic;
    private String commandConsumerGroup;
    private String decisionTopic;
    private String projectionConsumerGroup;
    private long kafkaSendTimeoutMs = 10000;
}
```

- [ ] **Step 5: Compile**

Run:

```bash
mvn -DskipTests compile
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add pom.xml docker-compose.yml src/main/resources/application.yml src/main/java/com/tongji/promotion/bprime/config
git commit -m "chore: add promotion bprime infrastructure config"
```

### Milestone 3: Command API Without Hot Decision

Success: HTTP bid endpoint returns command id; command row exists; no direct wallet hold/MySQL bid insert from controller; auction result is visible only through decision projection or snapshot.

#### Task 3.1: Add command tables and model

**Files:**
- Modify: `db/schema.sql`
- Create: `src/main/java/com/tongji/promotion/bprime/model/PromotionAuctionCommand.java`
- Create mapper/model for command table
- Test: `src/test/java/com/tongji/promotion/PromotionSchemaContractTest.java`

- [ ] **Step 1: Extend schema**

Add:

```sql
CREATE TABLE IF NOT EXISTS promotion_auction_command (
    id BIGINT PRIMARY KEY,
    command_id VARCHAR(64) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash VARCHAR(128) NOT NULL,
    auction_window_id BIGINT NOT NULL,
    campaign_id BIGINT NOT NULL,
    bidder_user_id BIGINT NOT NULL,
    post_id BIGINT NOT NULL,
    resource_type VARCHAR(64) NOT NULL,
    bid_amount BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    UNIQUE KEY uk_promotion_command_id (command_id),
    UNIQUE KEY uk_promotion_command_idempotency (auction_window_id, bidder_user_id, idempotency_key),
    KEY idx_promotion_command_window_status (auction_window_id, status, created_at)
);
```

- [ ] **Step 2: Add schema contract test**

Add assertions:

```java
assertThat(schema).contains("CREATE TABLE IF NOT EXISTS promotion_auction_command");
assertThat(schema).contains("uk_promotion_command_id");
assertThat(schema).contains("uk_promotion_command_idempotency");
```

- [ ] **Step 3: Add command record**

Create the Java record from the Data Shapes section, including `idempotencyKey` between `commandId` and `requestHash`.

- [ ] **Step 4: Run schema test**

```bash
mvn -Dtest=PromotionSchemaContractTest test
```

Expected: PASS.

#### Task 3.2: Replace submit bid with command submission

**Files:**
- Modify: `PromotionController`
- Modify: `PromotionCampaignService`
- Create: `PromotionCommandSubmissionService`
- Create: `SubmitPromotionBidCommandResponse`
- Test: `src/test/java/com/tongji/promotion/api/PromotionControllerTest.java`

- [ ] **Step 1: Write failing controller test**

Test expectation:

```java
mockMvc.perform(post("/api/v1/promotions/campaigns/100/bids")
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"bidAmount\":120,\"idempotencyKey\":\"idem-1\"}"))
    .andExpect(status().isOk())
    .andExpect(jsonPath("$.commandId").exists())
    .andExpect(jsonPath("$.status").value("SUBMITTED"));
verify(campaignService, never()).submitBid(anyLong(), anyLong(), anyLong(), any());
```

- [ ] **Step 2: Implement minimal command service**

`submit(...)`:

1. validate campaign ownership via existing campaign lookup
2. find open window
3. create command id
4. compute `requestHash = sha256(campaignId + bidderUserId + auctionWindowId + bidAmount + idempotencyKey)`
5. insert command row with status `SUBMITTED`
6. publish RocketMQ command only if `promotion.bprime.enabled=true`
7. mark command `PUBLISHED` after `syncSendOrderly` succeeds; keep `SUBMITTED` when B' disabled for local tests

Idempotency behavior:

- same `(auctionWindowId, bidderUserId, idempotencyKey)` and same `requestHash`: return the existing `commandId`
- same `(auctionWindowId, bidderUserId, idempotencyKey)` and different `requestHash`: throw `BAD_REQUEST` with message `idempotency key reused with different request`

- [ ] **Step 3: Update controller response**

Return:

```json
{
  "commandId": "...",
  "auctionWindowId": 123,
  "status": "SUBMITTED",
  "resultAvailable": false
}
```

- [ ] **Step 4: Run tests**

```bash
mvn -Dtest=PromotionControllerTest,PromotionCampaignServiceTest test
```

Expected: PASS after old bid expectations updated.

- [ ] **Step 5: Commit**

```bash
git add db/schema.sql src/main/java/com/tongji/promotion src/main/resources/mapper src/test/java/com/tongji/promotion
git commit -m "feat: submit promotion bids as bprime commands"
```

### Milestone 4: RocketMQ Ordered Command

Success: command producer uses `auctionWindowId` ordering key; listener calls processor; unit tests cover send failure.

#### Task 4.1: Add RocketMQ port and listener

**Files:**
- Create: `RocketMqPromotionCommandMessagePort`
- Create: `PromotionCommandRocketMqListener`
- Create: `PromotionCommandProcessingService`
- Test: `RocketMqPromotionCommandMessagePortTest`

- [ ] **Step 1: Implement producer**

Use bytedance shape:

```java
@Component
@ConditionalOnProperty(name = "promotion.bprime.enabled", havingValue = "true")
public class RocketMqPromotionCommandMessagePort implements PromotionCommandMessagePort {
    private final RocketMQTemplate rocketMQTemplate;
    private final PromotionBPrimeProperties properties;

    public void send(PromotionAuctionCommand command) {
        SendResult result = rocketMQTemplate.syncSendOrderly(
                properties.getCommandTopic(), command, String.valueOf(command.auctionWindowId()));
    }
}
```

If `SendResult` null or status not `SEND_OK`, throw `IllegalStateException`.

- [ ] **Step 2: Implement listener**

```java
@Component
@ConditionalOnProperty(name = "promotion.bprime.enabled", havingValue = "true")
@RocketMQMessageListener(
    topic = "${promotion.bprime.command-topic:zhiguang_promotion_auction_commands_v2}",
    consumerGroup = "${promotion.bprime.command-consumer-group:zhiguang-promotion-command-consumer}",
    consumeMode = ConsumeMode.ORDERLY)
public class PromotionCommandRocketMqListener implements RocketMQListener<PromotionAuctionCommand> {
    public void onMessage(PromotionAuctionCommand command) {
        processingService.process(command);
    }
}
```

- [ ] **Step 3: Run tests**

```bash
mvn -Dtest=RocketMqPromotionCommandMessagePortTest test
```

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/tongji/promotion/bprime src/test/java/com/tongji/promotion/bprime
git commit -m "feat: route promotion commands through rocketmq"
```

### Milestone 5: Redis Lua Hot Decision

Success: processor calls Lua; replay/higher-bid/reject tests pass without Kafka yet.

#### Task 5.1: Port minimal Lua decision script

**Files:**
- Create: `src/main/resources/redis/lua/promotion-auction-decision.lua`
- Create: `PromotionRedisDecisionAdapter`
- Create: `PromotionAuctionRedisKeys`
- Test: `PromotionRedisDecisionAdapterTest`
- Test: `PromotionRedisDecisionAdapterRedisIntegrationTest`

- [ ] **Step 1: Implement minimal script behavior**

Script inputs:

```text
KEYS[1] state hash
KEYS[2] commands hash
KEYS[3] ranking zset
KEYS[4] bidder hash
ARGV[1] commandId
ARGV[2] requestHash
ARGV[3] bidderUserId
ARGV[4] bidAmount
ARGV[5] reservePrice
ARGV[6] nowEpochMs
ARGV[7] windowStatus
```

Rules:

- same `commandId` + same `requestHash` returns stored decision
- same `commandId` + different `requestHash` returns rejected `IDEMPOTENCY_CONFLICT`
- closed window returns rejected `WINDOW_CLOSED`
- bid below reserve returns rejected `BELOW_RESERVE`
- accepted bid writes bidder amount and ranking score
- accepted bid creates one wallet effect: `HOLD` with `businessRef = promotion-bprime:{commandId}:hold`

- [ ] **Step 2: Add adapter**

Adapter loads script via `DefaultRedisScript<String>` and parses JSON decision.

- [ ] **Step 3: Unit test with mocked RedisTemplate**

Test adapter sends exact keys and args.

- [ ] **Step 3.1: Add Redis integration test**

Follow the existing reachable-Redis pattern in `PromotionAllocationRedisIntegrationTest`: enable the test only when `127.0.0.1:6379` is reachable.

Required assertions:

- first accepted command writes ranking
- replay with same `commandId` + same `requestHash` returns the stored decision
- replay with same `commandId` + different `requestHash` returns `IDEMPOTENCY_CONFLICT`
- closed window returns `WINDOW_CLOSED`
- below reserve returns `BELOW_RESERVE`
- ranking order is deterministic for equal bids: higher bid first, tie by earlier `nowEpochMs`, then `commandId`

- [ ] **Step 3.2: Run tests**

```bash
mvn -Dtest=PromotionRedisDecisionAdapterTest,PromotionRedisDecisionAdapterRedisIntegrationTest test
```

- [ ] **Step 4: Commit**

```bash
git add src/main/resources/redis/lua src/main/java/com/tongji/promotion/bprime/redis src/test/java/com/tongji/promotion/bprime/redis
git commit -m "feat: add promotion redis decision script"
```

### Milestone 6: Kafka Decision Log

Success: accepted/rejected decision append waits for Kafka future; processor fails if Kafka append fails.

#### Task 6.1: Add decision model and Kafka port

**Files:**
- Create: `PromotionAuctionDecision`
- Create: `PromotionDecisionLogPort`
- Create: `KafkaPromotionDecisionLogPort`
- Test: `KafkaPromotionDecisionLogPortTest`

- [ ] **Step 1: Add decision record**

Use Data Shapes above. Include `decisionId`, `commandId`, `requestHash`, `auctionWindowId`, `accepted`, `rejectionReason`, `ranking`, `walletEffects`.

- [ ] **Step 2: Add Kafka port**

Use:

```java
String payload = objectMapper.writeValueAsString(decision);
kafkaTemplate.send(topic, String.valueOf(decision.auctionWindowId()), payload)
        .get(timeoutMs, TimeUnit.MILLISECONDS);
```

Use `KafkaTemplate<String, String>` to match repo convention. On serialization, timeout, or send exception: throw `IllegalStateException("Failed to append promotion decision to Kafka", e)`.

- [ ] **Step 3: Wire processor**

`PromotionCommandProcessingService.process(command)`:

1. call Redis adapter
2. map to decision
3. for accepted decisions, apply `HOLD` wallet effect with `WalletLedgerReason.PROMOTION_BPRIME_HOLD`
4. append decision JSON to Kafka
5. mark command `DECIDED`
6. if Kafka append fails after hold succeeds, release the same amount with `WalletLedgerReason.PROMOTION_BPRIME_RELEASE`, business ref `promotion-bprime:{commandId}:hold-release-after-log-fail`, mark command `LOG_FAILED`, then rethrow

Rejected decisions have no wallet effect and still append to Kafka.

- [ ] **Step 4: Run tests**

```bash
mvn -Dtest=KafkaPromotionDecisionLogPortTest,PromotionCommandProcessingServiceTest test
```

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/tongji/promotion/bprime src/test/java/com/tongji/promotion/bprime
git commit -m "feat: append promotion auction decisions to kafka"
```

### Milestone 7: Projection and GSP Allocation

Success: Kafka listener projects decisions idempotently; close window produces allocation and capture/release wallet effects.

#### Task 7.1: Add decision/projection schema

**Files:**
- Modify: `db/schema.sql`
- Create decision/checkpoint models/mappers

- [ ] **Step 1: Add tables**

```sql
CREATE TABLE IF NOT EXISTS promotion_auction_decision (
    id BIGINT PRIMARY KEY,
    decision_id VARCHAR(64) NOT NULL,
    command_id VARCHAR(64) NOT NULL,
    auction_window_id BIGINT NOT NULL,
    decision_type VARCHAR(32) NOT NULL,
    accepted TINYINT(1) NOT NULL,
    rejection_reason VARCHAR(64) NULL,
    payload_json JSON NOT NULL,
    created_at DATETIME(3) NOT NULL,
    UNIQUE KEY uk_promotion_decision_id (decision_id),
    KEY idx_promotion_decision_window (auction_window_id, created_at)
);

CREATE TABLE IF NOT EXISTS promotion_projection_checkpoint (
    auction_window_id BIGINT PRIMARY KEY,
    last_decision_id VARCHAR(64) NOT NULL,
    updated_at DATETIME(3) NOT NULL
);
```

- [ ] **Step 2: Add mapper tests**

Use existing MyBatis test style. Verify duplicate `decision_id` is ignored/rejected deterministically.

#### Task 7.2: Implement projection service

**Files:**
- Create: `PromotionDecisionProjectionService`
- Create: `PromotionDecisionProjectionKafkaListener`
- Modify: `PromotionAuctionService`
- Test: `PromotionDecisionProjectionServiceTest`

- [ ] **Step 1: Project accepted/rejected decisions**

Accepted decision inserts/updates `promotion_bid` fact. Rejected decision stores decision only.

- [ ] **Step 2: Project close decision**

Compute:

```java
clearingPrice = Math.max(nextBidAmountOrReserve, reservePrice);
```

Top `slotCount` become allocations. Losers marked lost.

- [ ] **Step 3: Use wallet idempotency for close-window effects**

Business refs:

```text
promotion-bprime:{commandId}:hold
promotion-bprime:{auctionWindowId}:{campaignId}:capture
promotion-bprime:{auctionWindowId}:{campaignId}:release
```

Do not create a second hold in projection. Initial hold is owned by `PromotionCommandProcessingService`.

- [ ] **Step 4: Run tests**

```bash
mvn -Dtest=PromotionDecisionProjectionServiceTest,PromotionAuctionServiceTest test
```

- [ ] **Step 5: Commit**

```bash
git add db/schema.sql src/main/java/com/tongji/promotion src/main/resources/mapper src/test/java/com/tongji/promotion
git commit -m "feat: project promotion decisions into allocations"
```

### Milestone 8: Wallet Effects

Success: duplicate decision replay cannot double hold/capture/release.

#### Task 8.1: Extend wallet reasons and tests

**Files:**
- Modify: `WalletLedgerReason`
- Modify: `WalletService`
- Test: `WalletServiceTest`

- [ ] **Step 1: Add reasons**

Add:

```java
PROMOTION_BPRIME_HOLD,
PROMOTION_BPRIME_CAPTURE,
PROMOTION_BPRIME_RELEASE
```

- [ ] **Step 2: Reuse existing methods where possible**

Lazy path:

- hold: existing `hold(...)` with new reason
- capture: existing `captureHoldToPlatform(...)` overload
- release: existing `releaseHold(...)`

Do not create wallet reservation table.

- [ ] **Step 3: Add processor wallet tests**

In `PromotionCommandProcessingServiceTest`:

- accepted decision calls `walletService.hold(...)` before Kafka append
- rejected decision does not call wallet
- Kafka append failure after hold calls `walletService.releaseHold(...)` with ref `promotion-bprime:{commandId}:hold-release-after-log-fail`

- [ ] **Step 4: Add wallet replay tests**

Duplicate same businessRef returns existing ledger. Same ref with different amount rejects.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/tongji/wallet src/test/java/com/tongji/wallet
git commit -m "feat: support bprime promotion wallet effects"
```

### Milestone 9: Snapshot and Read Path

Success: snapshot endpoint returns current ranking; feed/search still consume allocation only.

#### Task 9.1: Add snapshot API

**Files:**
- Create: `PromotionSnapshotService`
- Modify: `PromotionController`
- Test: `PromotionControllerTest`

- [ ] **Step 1: Add endpoint**

```text
GET /api/v1/promotions/windows/{auctionWindowId}/snapshot
```

Response fields:

```json
{
  "auctionWindowId": 1,
  "status": "OPEN",
  "ranking": [],
  "serverTime": "2026-06-22T00:00:00Z"
}
```

- [ ] **Step 2: Source from Redis, fallback to projection**

If Redis missing, return projected bid facts. No WebSocket.

- [ ] **Step 3: Run tests**

```bash
mvn -Dtest=PromotionControllerTest test
```

#### Task 9.2: Remove paid boost markers from feed/search

**Files:**
- Modify: `HomeFeedMixingService`
- Modify: `SearchServiceImpl`
- Modify: `FeedItemResponse`
- Test: feed/search promotion tests

- [ ] **Step 1: Assert no boost placement**

Tests must assert placement type is `feed_top_slot` or `search_top_slot`, never `home_recommendation_boost`.

- [ ] **Step 2: Run tests**

```bash
mvn -Dtest=HomeFeedMixingServiceTest,PromotionSearchEsIntegrationTest test
```

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/tongji/recommendation src/main/java/com/tongji/search src/main/java/com/tongji/knowpost src/test/java
git commit -m "feat: serve commercial content from position allocations"
```

### Milestone 10: Minimal Reconciliation

Success: can detect Kafka decision persisted in DB but not projected into allocation.

#### Task 10.1: Add two repair task types

**Files:**
- Modify: reconciliation model/executor
- Test: reconciliation tests

- [ ] **Step 1: Add task types**

```text
promotion_decision_projection
promotion_allocation_rebuild
```

- [ ] **Step 2: Implement decision-without-projection repair**

Input: `decisionId`. Action: call projection service idempotently.

- [ ] **Step 3: Implement closed-window allocation rebuild**

Input: `auctionWindowId`. Action: replay accepted decisions and rebuild allocation if missing.

- [ ] **Step 4: Run tests**

```bash
mvn -Dtest=ReconciliationTaskExecutorTest,PromotionDecisionProjectionServiceTest test
```

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/tongji/reconciliation src/test/java/com/tongji/reconciliation
git commit -m "feat: reconcile bprime promotion projection"
```

### Milestone 11: End-to-End Verification

Success: one runnable command path proves the chain.

- [ ] **Step 1: Start infra**

```bash
docker compose up -d mysql redis kafka rocketmq-namesrv rocketmq-broker
```

Expected: all healthy.

- [ ] **Step 2: Run focused tests**

```bash
mvn -Dtest='Promotion*Test,WalletServiceTest,HomeFeedMixingServiceTest' test
```

Expected: PASS.

- [ ] **Step 3: Validate OpenSpec**

```bash
openspec validate add-bprime-position-auctions --strict
```

Expected: `Change 'add-bprime-position-auctions' is valid`.

- [ ] **Step 4: Update tasks**

Mark completed implementation tasks in `openspec/changes/add-bprime-position-auctions/tasks.md`.

- [ ] **Step 5: Commit**

```bash
git add openspec/changes/add-bprime-position-auctions/tasks.md
git commit -m "docs: update bprime position auction progress"
```

## Rollback

- If RocketMQ blocks startup: set `promotion.bprime.enabled=false`; command endpoint returns `SUBMITTED` only in tests, production route disabled.
- If projection corrupts allocation: stop projection consumer group, truncate new `promotion_auction_decision` / checkpoint rows for test window, rebuild old allocation path from existing tables.
- If paid boost deletion breaks compile: revert Task 1 commit only; no schema migration has run yet.

## Open Questions

1. WebSocket/SSE needed for first implementation, or snapshot API enough?
2. Same creator same window: allow rebid? Plan assumes yes, higher bid replaces ranking amount.
3. Search slot targeting: all search pages same allocation, or later keyword targeting? Plan assumes no targeting.

## Delta Summary

- Cut WebSocket from MVP.
- Made paid boost deletion first, because code exists.
- Named RocketMQ/Kafka versions and APIs.
- Added concrete data shapes and failure boundaries.
- Reduced reconciliation to two repairs, not full drift system.
