# Publish/Relation Architecture Implementation Plan

> **For agentic workers:** Use `superpowers:subagent-driven-development` only when the user/environment has authorized subagents. Otherwise execute this single plan in the current session with `superpowers:executing-plans`. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move publish and relation write paths to Manager orchestration, make publish attempt-based and asynchronous at the API boundary, isolate executors, and add Sentinel-backed guard abstractions.

**Architecture:** Controllers call managers directly. Managers coordinate helpers, DAOs, publishers, clients, idempotency, state transitions, and fallback choices. Publish returns `202 Accepted + publishAttemptId`; relation keeps natural idempotency.

**Tech Stack:** Java 21, Spring Boot 3.2.4, MyBatis, MySQL, Redis, Kafka, Sentinel, JUnit 5, Maven.

---

## Required Context

Read before editing:

- `openspec/11pdf-integration-matrix.md`
- `openspec/changes/execution-order.md`
- `openspec/changes/add-leaf-id-service/design.md`
- `openspec/changes/add-leaf-id-service/specs/id-service/spec.md`
- `openspec/changes/add-leaf-id-service/tasks.md`
- `openspec/changes/align-publish-relation-architecture/proposal.md`
- `openspec/changes/align-publish-relation-architecture/design.md`
- `openspec/changes/align-publish-relation-architecture/specs/publish-relation-architecture/spec.md`
- `openspec/changes/align-publish-relation-architecture/tasks.md`
- `src/main/java/com/tongji/knowpost/service/impl/KnowPostServiceImpl.java`
- `src/main/java/com/tongji/knowpost/api/KnowPostController.java`
- `src/main/java/com/tongji/relation/service/impl/RelationServiceImpl.java`
- `src/main/java/com/tongji/relation/api/RelationController.java`
- `src/main/java/com/tongji/config/ThreadPoolConfig.java`

Prerequisite: `add-leaf-id-service` should be complete before this plan starts. The active Leaf OpenSpec accepts `RELATION(IdMode.SNOWFLAKE)` for relation row IDs such as `following.id`, so relation manager work can depend on `IdNamespace.RELATION` normally.
ID imports must use `com.tongji.common.id.IdService` and `com.tongji.common.id.IdNamespace`.

## Command Setup

Run Maven commands from the repo root. The current workspace root is `/Volumes/lexar/revive/zhiguang_be`; on other machines, use that machine's repo root.

```bash
mvn -version
```

Windows PowerShell optional equivalent: `mvn.cmd -version` if `mvn.cmd` is on `PATH`.

## Files

- Modify: `pom.xml`
- Modify: `db/schema.sql`
- Modify: `src/main/resources/application.yml`
- Modify: `src/main/java/com/tongji/config/ThreadPoolConfig.java`
- Create: `src/main/java/com/tongji/common/resilience/*`
- Create: `src/main/java/com/tongji/knowpost/manager/*`
- Create: `src/main/java/com/tongji/knowpost/publish/*`
- Create: `src/main/java/com/tongji/relation/manager/*`
- Modify: `src/main/java/com/tongji/knowpost/api/KnowPostController.java`
- Modify: `src/main/java/com/tongji/relation/api/RelationController.java`
- Modify: `src/main/java/com/tongji/knowpost/mapper/KnowPostMapper.java`
- Modify: `src/main/resources/mapper/KnowPostMapper.xml`
- Modify: `src/main/java/com/tongji/relation/service/RelationService.java`
- Modify: `src/main/java/com/tongji/knowpost/service/KnowPostService.java`
- Test: `src/test/java/com/tongji/knowpost/manager/*`
- Test: `src/test/java/com/tongji/relation/manager/*`
- Test: `src/test/java/com/tongji/common/resilience/*`

## Task 1: Schema and API Models

**Files:**
- Modify: `db/schema.sql`
- Create: `src/main/java/com/tongji/knowpost/publish/PublishAttempt.java`
- Create: `src/main/java/com/tongji/knowpost/publish/PublishAttemptMapper.java`
- Create: `src/main/resources/mapper/PublishAttemptMapper.xml`
- Create: `src/main/java/com/tongji/knowpost/api/dto/PublishRequest.java`
- Create: `src/main/java/com/tongji/knowpost/api/dto/PublishAcceptedResponse.java`
- Create: `src/main/java/com/tongji/knowpost/api/dto/PublishStatusResponse.java`

- [ ] Add `publish_attempt` table with `attempt_id`, `post_id`, `creator_id`, `idempotent_key`, `status`, `failed_step`, `error_message`, `retry_count`, timestamps, and unique key `(creator_id, post_id, idempotent_key)`. Do not add a status-history table for v1.
- [ ] Extend `know_posts.status` to include `publishing`, `publish_failed`, and `rejected`.
- [ ] Add `publish_attempt_id` and `publish_failed_reason` to `know_posts`.
- [ ] Create mapper methods for insert, find by idempotency key, find by id, succeed, fail, and status lookup.
- [ ] Create request/response DTOs using string IDs in API responses where current API already serializes IDs as strings.
- [ ] Run `mvn -DskipTests compile`.

## Task 2: Executor Isolation

**Files:**
- Modify: `src/main/java/com/tongji/config/ThreadPoolConfig.java`
- Modify: `src/main/java/com/tongji/relation/outbox/CanalKafkaBridge.java`
- Inspect: relation processors and consumers

- [ ] Add named executor beans: `publishExecutor`, `relationEventExecutor`, `canalOutboxExecutor`, `reconciliationExecutor`.
- [ ] Keep `taskExecutor` temporarily only for unmigrated code.
- [ ] Give each executor explicit thread prefix, pool size, queue size, rejection policy, and shutdown settings.
- [ ] Rewire `CanalKafkaBridge` from `@Qualifier("taskExecutor")` to `@Qualifier("canalOutboxExecutor")`.
- [ ] Add a context test that asserts all named executors exist.
- [ ] Run `mvn -Dtest=*ThreadPool* test`.

## Task 3: Resilience Guard Abstraction

**Files:**
- Modify: `pom.xml`
- Create: `src/main/java/com/tongji/common/resilience/ResilienceGuard.java`
- Create: `src/main/java/com/tongji/common/resilience/GuardedOperation.java`
- Create: `src/main/java/com/tongji/common/resilience/GuardResult.java`
- Create: `src/main/java/com/tongji/common/resilience/SentinelResilienceGuard.java`
- Test: `src/test/java/com/tongji/common/resilience/SentinelResilienceGuardTest.java`

- [ ] Add the fixed Sentinel dependency `com.alibaba.csp:sentinel-core:1.8.10`; do not introduce Spring Cloud Alibaba starter dependencies in this change.
- [ ] Define a local guard interface that accepts a resource name, operation supplier, fallback supplier, and failure classifier.
- [ ] Implement Sentinel adapter behind the interface.
- [ ] Add classifier behavior so `BusinessException` is not counted as system degradation.
- [ ] Add tests for success, fallback, and business-exception classification.
- [ ] Run `mvn -Dtest=SentinelResilienceGuardTest test`.

## Task 4: Publish Manager

**Files:**
- Create: `src/main/java/com/tongji/knowpost/manager/PublishManager.java`
- Create: `src/main/java/com/tongji/knowpost/manager/PublishManagerImpl.java`
- Create: `src/main/java/com/tongji/knowpost/manager/PublishValidationHelper.java`
- Create: `src/main/java/com/tongji/knowpost/manager/PublishAttemptService.java`
- Create: `src/main/java/com/tongji/knowpost/publish/ContentPublishedEvent.java`
- Create: `src/main/java/com/tongji/knowpost/publish/ContentPublishedPublisher.java`
- Modify: `src/main/java/com/tongji/knowpost/mapper/KnowPostMapper.java`
- Modify: `src/main/resources/mapper/KnowPostMapper.xml`

- [ ] Add mapper CAS methods: `startPublishing`, `completePublish`, `failPublish`, `findPublishStatus`, and retry transition from `publish_failed` to `publishing`.
- [ ] Add stuck-publishing recovery: scheduled work should surface posts stuck in `publishing` for more than 5 minutes as `publish_failed` or equivalent retry-visible attempt failure; it must not mark them `published`.
- [ ] Implement `PublishManager.acceptPublish(authorId, postId, idempotentKey)` for lightweight validation, idempotency lookup, original attempt creation/reuse, and `draft -> publishing` CAS.
- [ ] Implement retry by reusing the original attempt row, incrementing `retry_count`, clearing retryable failure fields as needed, and moving the same attempt back to `publishing`; v1 does not create a new attempt for the same idempotent key and does not use `retry_of_attempt_id`.
- [ ] Submit critical publish work to `publishExecutor` after acceptance.
- [ ] In v1, keep critical flow limited to current facts available in this change: permission/state checks, durable attempt/post transitions, and durable `content_published` publication. Treat user post counters as derived counter work unless the active OpenSpec explicitly says counters block publish success.
- [ ] Do not implement Cassandra write here; leave a seam for `TextStorageService` integration in `add-cassandra-text-storage`.
- [ ] Ensure derived failures call `ReconciliationService.createTaskIfAbsent(...)` when that service exists. If the service does not exist yet, persist or emit a durable outbox-compatible failure record containing task type, target type, target ID, failure reason, and next retry hint so `add-data-reconciliation` can consume it later. Log-only handling is not completion evidence, and OpenSpec task 3.6 must not be marked done for log-only handling. Never revert `published` for derived failures.
- [ ] Add manager tests for duplicate idempotency key, missing key rejection, accepted-before-final-publication, critical failure, retry eligibility, derived failure not rolling back `published`, derived failure durable retry/reconciliation recording, and stuck-publishing recovery.
- [ ] Run `mvn -Dtest=*PublishManager* test`.

## Task 5: Publish Controller Migration

**Files:**
- Modify: `src/main/java/com/tongji/knowpost/api/KnowPostController.java`
- Modify: `src/main/java/com/tongji/knowpost/service/KnowPostService.java`
- Modify: `src/main/java/com/tongji/knowpost/service/impl/KnowPostServiceImpl.java`

- [ ] Change `POST /api/v1/knowposts/{id}/publish` to accept `PublishRequest` and return `202 Accepted` with `PublishAcceptedResponse`.
- [ ] Add `GET /api/v1/knowposts/{id}/publish/status?attemptId=...`.
- [ ] Add `POST /api/v1/knowposts/{id}/publish/{attemptId}/retry`; it returns the same `publishAttemptId` after incrementing `retry_count`.
- [ ] Remove publish orchestration from `KnowPostServiceImpl`; either remove the service publish method or make it non-orchestration only if required by current wiring.
- [ ] Add controller tests for `202`, missing idempotency key, status, and retry.
- [ ] Run `mvn -Dtest=*KnowPostController* test`.

## Task 6: Relation Manager

**Files:**
- Create: `src/main/java/com/tongji/relation/manager/RelationManager.java`
- Create: `src/main/java/com/tongji/relation/manager/RelationManagerImpl.java`
- Create: `src/main/java/com/tongji/relation/manager/RelationWriteResult.java`
- Create: `src/main/java/com/tongji/relation/manager/RelationPublisher.java`
- Modify: `src/main/java/com/tongji/relation/api/RelationController.java`
- Modify: `src/main/java/com/tongji/relation/service/RelationService.java`
- Modify: `src/main/java/com/tongji/relation/service/impl/RelationServiceImpl.java`

- [ ] Move follow/unfollow orchestration into `RelationManager`.
- [ ] Verify active Leaf implementation and accepted OpenSpec include the accepted relation row namespace:

```bash
rg -n "RELATION|relation row" openspec/changes/add-leaf-id-service/design.md openspec/changes/add-leaf-id-service/specs/id-service/spec.md openspec/changes/add-leaf-id-service/tasks.md src/main/java/com/tongji/common/id/IdNamespace.java
```

Expected: both OpenSpec and implementation show `RELATION` as the accepted Snowflake namespace for relation row IDs.

- [ ] Use `IdService.nextId(IdNamespace.RELATION)` for `following.id`.
- [ ] Use `IdService.nextId(IdNamespace.OUTBOX_EVENT)` for relation outbox IDs.
- [ ] Preserve token-bucket rate limiting and natural idempotency from `(fromUserId, toUserId, action, current state)`.
- [ ] Prevent duplicate side effects for repeated follow/unfollow.
- [ ] Keep read methods such as lists/profiles in `RelationService` unless moved in a later cleanup.
- [ ] Update `RelationController` follow/unfollow/status endpoints to call the manager directly.
- [ ] Add tests for duplicate follow, duplicate unfollow, outbox id generation, and controller wiring.
- [ ] Run `mvn -Dtest=*RelationManager* test`.

## Task 7: Final Verification and OpenSpec Closure

**Files:**
- Modify: `openspec/changes/align-publish-relation-architecture/tasks.md`

- [ ] Run `mvn test`.
- [ ] Run the relation namespace check from Task 6 and verify relation writes use `IdNamespace.RELATION`.
- [ ] Run `rg -n "KnowPostService|RelationService" src/main/java/com/tongji/knowpost/api src/main/java/com/tongji/relation/api` and verify migrated use cases do not depend on old service orchestration.
- [ ] Run `rg -n "ThreadLocalRandom\\.current\\(\\)\\.nextLong|SnowflakeIdGenerator|idGen" src/main/java/com/tongji/knowpost src/main/java/com/tongji/relation` and verify no migrated ID generation remains.
- [ ] Run `openspec status --change "align-publish-relation-architecture" --json`.
- [ ] Run `openspec validate align-publish-relation-architecture --strict` if supported.
- [ ] Mark completed checkboxes in `openspec/changes/align-publish-relation-architecture/tasks.md` only after evidence exists.
