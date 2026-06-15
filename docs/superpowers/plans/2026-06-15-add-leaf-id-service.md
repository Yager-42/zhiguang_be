# Leaf ID Service Implementation Plan

> **For agentic workers:** REQUIRED: Use `superpowers:subagent-driven-development` if subagents are available, or `superpowers:executing-plans` in the current session. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement the unified Leaf-style `IdService`, migrate current ID call sites, and close `add-leaf-id-service` only after verification evidence exists.

**Architecture:** Add `com.tongji.common.id` as the shared ID package. Snowflake handles high-frequency business IDs, Segment handles low-frequency operational IDs through `leaf_alloc`, and current services depend on `IdService` instead of concrete generators or random IDs.

**Tech Stack:** Java 21, Spring Boot 3.2.4, MyBatis, MySQL 8 schema SQL, JUnit 5, Mockito, Maven, OpenSpec.

---

## Required Context

Read before editing:

- `openspec/changes/add-leaf-id-service/proposal.md`
- `openspec/changes/add-leaf-id-service/design.md`
- `openspec/changes/add-leaf-id-service/specs/id-service/spec.md`
- `openspec/changes/add-leaf-id-service/tasks.md`
- `openspec/11pdf-integration-matrix.md`
- `src/main/java/com/tongji/knowpost/id/SnowflakeIdGenerator.java`
- `src/main/java/com/tongji/knowpost/service/impl/KnowPostServiceImpl.java`
- `src/main/java/com/tongji/relation/service/impl/RelationServiceImpl.java`
- `src/main/resources/application.yml`
- `db/schema.sql`

## Command Setup

Run before Maven commands:

```powershell
$mvn = Join-Path $env:USERPROFILE 'Desktop\文档\.codex\tools\apache-maven-3.9.6\bin\mvn.cmd'
Test-Path $mvn
```

Expected: `True`.

## Files

- Create: `src/main/java/com/tongji/common/id/IdService.java`
- Create: `src/main/java/com/tongji/common/id/IdMode.java`
- Create: `src/main/java/com/tongji/common/id/IdNamespace.java`
- Create: `src/main/java/com/tongji/common/id/ClockBackwardException.java`
- Create: `src/main/java/com/tongji/common/id/SnowflakeProperties.java`
- Create: `src/main/java/com/tongji/common/id/SnowflakeIdGenerator.java`
- Create: `src/main/java/com/tongji/common/id/DefaultIdService.java`
- Create: `src/main/java/com/tongji/common/id/segment/*`
- Create: `src/main/resources/mapper/LeafAllocMapper.xml`
- Modify: `src/main/resources/application.yml`
- Modify: `db/schema.sql`
- Modify: `src/main/java/com/tongji/knowpost/service/impl/KnowPostServiceImpl.java`
- Modify: `src/main/java/com/tongji/relation/service/impl/RelationServiceImpl.java`
- Delete: `src/main/java/com/tongji/knowpost/id/SnowflakeIdGenerator.java`
- Test: `src/test/java/com/tongji/common/id/*`
- Test: `src/test/java/com/tongji/common/id/segment/*`
- Modify only after verification: `openspec/changes/add-leaf-id-service/tasks.md`

## Namespace Set

`IdNamespace` must include the namespaces already accepted by the active `add-leaf-id-service` OpenSpec:

```java
POST(IdMode.SNOWFLAKE),
COMMENT(IdMode.SNOWFLAKE),
PENDING_COMMENT(IdMode.SNOWFLAKE),
PUBLISH_ATTEMPT(IdMode.SNOWFLAKE),
RELATION(IdMode.SNOWFLAKE),
OUTBOX_EVENT(IdMode.SNOWFLAKE),
RECONCILIATION_TASK(IdMode.SEGMENT),
ADMIN_OPERATION(IdMode.SEGMENT),
AUDIT_LOG(IdMode.SEGMENT);
```

Current `RelationServiceImpl` also generates `following.id`; relation row IDs must migrate through `IdService.nextId(IdNamespace.RELATION)`. Relation outbox IDs must use `IdService.nextId(IdNamespace.OUTBOX_EVENT)`, and `OUTBOX_EVENT` must not be reused for relation row IDs. Keep `users.id` as MySQL auto-increment.

## Task 1: Snowflake Configuration and Public API

**Files:**
- Modify: `src/main/resources/application.yml`
- Create: `src/main/java/com/tongji/common/id/IdService.java`
- Create: `src/main/java/com/tongji/common/id/IdMode.java`
- Create: `src/main/java/com/tongji/common/id/IdNamespace.java`

- [ ] Add Snowflake config under a single top-level `id:` block:

```yaml
id:
  snowflake:
    worker-id: ${SNOWFLAKE_WORKER_ID:1}
    datacenter-id: ${SNOWFLAKE_DATACENTER_ID:1}
```

- [ ] Create `IdMode`:

```java
package com.tongji.common.id;

public enum IdMode {
    SNOWFLAKE,
    SEGMENT
}
```

- [ ] Create `IdNamespace` with the full namespace set above and a consistent `getMode()` or `mode()` getter.
- [ ] Create `IdService`:

```java
package com.tongji.common.id;

public interface IdService {
    long nextId(IdNamespace namespace);
}
```

- [ ] Do not edit OpenSpec design/spec/tasks from this task. OpenSpec task closure is limited to checkbox updates in Task 7 after evidence exists.
- [ ] Run `& $mvn test`.
Expected: `BUILD SUCCESS`, or no YAML/config binding error if unrelated existing tests fail.

## Task 2: Common Snowflake Implementation

**Files:**
- Create: `src/main/java/com/tongji/common/id/SnowflakeProperties.java`
- Create: `src/main/java/com/tongji/common/id/ClockBackwardException.java`
- Create: `src/main/java/com/tongji/common/id/SnowflakeIdGenerator.java`
- Create: `src/main/java/com/tongji/common/id/DefaultIdService.java`
- Create: `src/test/java/com/tongji/common/id/SnowflakeIdGeneratorTest.java`

- [ ] Create `SnowflakeProperties` with `@Component` and `@ConfigurationProperties(prefix = "id.snowflake")`; default `workerId` and `datacenterId` to `1`.
- [ ] Create `ClockBackwardException` as a runtime exception with `(String message)` and `(String message, Throwable cause)` constructors.
- [ ] Port the existing algorithm from `com.tongji.knowpost.id.SnowflakeIdGenerator` into `com.tongji.common.id.SnowflakeIdGenerator`.
- [ ] Use an explicit bean name such as `@Component("commonSnowflakeIdGenerator")` while the old knowpost generator still exists.
- [ ] Preserve epoch `1704067200000L`, 5 worker bits, 5 datacenter bits, 12 sequence bits, synchronized `nextId()`, and sequence-overflow wait.
- [ ] Validate worker/datacenter are `0..31`; rollback `<= 5ms` waits, rollback `> 5ms` throws `ClockBackwardException`.
- [ ] Implement `DefaultIdService`: Snowflake namespaces call the common generator; Segment namespaces temporarily throw `UnsupportedOperationException`.
- [ ] Test concurrent uniqueness with `16 * 1000` generated IDs.
- [ ] Test Snowflake namespace routing for `POST`, `COMMENT`, `PENDING_COMMENT`, `PUBLISH_ATTEMPT`, `RELATION`, and `OUTBOX_EVENT`.
- [ ] Test Segment namespaces throw before Segment is connected.
- [ ] Test default config values and invalid worker/datacenter IDs.
- [ ] Run `& $mvn -Dtest=SnowflakeIdGeneratorTest test`.
Expected: `BUILD SUCCESS`.

## Task 3: Segment Schema, Mapper, and Range Loader

**Files:**
- Modify: `db/schema.sql`
- Modify: `src/main/resources/application.yml`
- Create: `src/main/java/com/tongji/common/id/segment/LeafAlloc.java`
- Create: `src/main/java/com/tongji/common/id/segment/LeafAllocMapper.java`
- Create: `src/main/java/com/tongji/common/id/segment/SegmentAllocator.java`
- Create: `src/main/resources/mapper/LeafAllocMapper.xml`
- Create: `src/main/java/com/tongji/common/id/segment/SegmentRange.java`
- Create: `src/main/java/com/tongji/common/id/segment/SegmentLoader.java`
- Create: `src/main/java/com/tongji/common/id/segment/SegmentLoadException.java`

- [ ] Add `leaf_alloc` table:

```sql
CREATE TABLE IF NOT EXISTS leaf_alloc (
    biz_tag VARCHAR(128) NOT NULL,
    max_id BIGINT NOT NULL DEFAULT 1,
    step INT NOT NULL DEFAULT 1000,
    description VARCHAR(256) NULL,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (biz_tag)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

- [ ] Add idempotent initialization rows:

```sql
INSERT INTO leaf_alloc (biz_tag, max_id, step, description) VALUES
    ('reconciliation_task', 1, 1000, 'Reconciliation task ID'),
    ('admin_operation', 1, 1000, 'Admin operation ID'),
    ('audit_log', 1, 1000, 'Audit log ID')
ON DUPLICATE KEY UPDATE
    step = VALUES(step),
    description = VALUES(description);
```

- [ ] Add Segment config under the existing `id:` block:

```yaml
id:
  segment:
    wait-timeout-ms: ${ID_SEGMENT_WAIT_TIMEOUT_MS:500}
    preload-threads: ${ID_SEGMENT_PRELOAD_THREADS:2}
```

- [ ] Create `LeafAlloc` with `bizTag`, `maxId`, and `step`.
- [ ] Create `LeafAllocMapper`:

```java
@Mapper
public interface LeafAllocMapper {
    int updateMaxId(@Param("bizTag") String bizTag);
    LeafAlloc selectByBizTag(@Param("bizTag") String bizTag);
}
```

- [ ] Create mapper XML with namespace `com.tongji.common.id.segment.LeafAllocMapper`.
- [ ] `updateMaxId` SQL: `UPDATE leaf_alloc SET max_id = max_id + step WHERE biz_tag = #{bizTag}`.
- [ ] `selectByBizTag` must alias `biz_tag AS bizTag`, `max_id AS maxId`, and `step AS step`.
- [ ] Create `SegmentAllocator` as an independent Spring service with `@Transactional SegmentRange allocateSegment(String bizTag)`. Do not rely on self-invocation inside `SegmentIdGenerator`.
- [ ] Create `SegmentRange`, rejecting `startInclusive > endInclusive`.
- [ ] Create `SegmentLoader` as a functional interface: `SegmentRange load(String bizTag)`.
- [ ] Create `SegmentLoadException extends RuntimeException`.
- [ ] Run `& $mvn test`.
Expected: `BUILD SUCCESS`.

## Task 4: Segment Buffer and Generator

**Files:**
- Create: `src/main/java/com/tongji/common/id/segment/SegmentBuffer.java`
- Create: `src/main/java/com/tongji/common/id/segment/SegmentIdProperties.java`
- Create: `src/main/java/com/tongji/common/id/segment/SegmentIdGenerator.java`
- Modify: `src/main/java/com/tongji/common/id/DefaultIdService.java`
- Create: `src/test/java/com/tongji/common/id/segment/SegmentBufferTest.java`
- Create: `src/test/java/com/tongji/common/id/segment/SegmentIdGeneratorTest.java`
- Create: `src/test/java/com/tongji/common/id/IdServiceSegmentRoutingTest.java`

- [ ] Write `SegmentBufferTest` first: fake loader returns `[1,4]`, then `[5,8]`; five `nextId()` calls return `1,2,3,4,5`.
- [ ] Add preload test: consuming 50% of `[1,4]` triggers exactly one async preload.
- [ ] Implement `SegmentBuffer` with closed intervals, initial load, 50% preload, current-to-next switch, and deterministic tests with a fake or single-thread executor.
- [ ] Add failure tests: preload failure does not corrupt current range, preload can retry, exhaustion waits at most `waitTimeout`, timeout throws `SegmentLoadException`.
- [ ] Create `SegmentIdProperties` with `waitTimeoutMs = 500` and `preloadThreads = 2`, bound to `id.segment`.
- [ ] Implement `SegmentIdGenerator` with a `ConcurrentHashMap<String, SegmentBuffer>` and atomic `computeIfAbsent`.
- [ ] The generator loader must call injected `SegmentAllocator`.
- [ ] Allocation algorithm: after `updateMaxId`, read `LeafAlloc`; range is `[maxId - step + 1, maxId]`.
- [ ] If a private executor is owned by `SegmentIdGenerator`, shut it down in `@PreDestroy`, or inject a Spring-managed executor.
- [ ] Test unknown biz tag, `updateMaxId != 1`, null select result, and mapper exceptions as `SegmentLoadException`.
- [ ] Route Segment namespaces in `DefaultIdService`: `RECONCILIATION_TASK -> reconciliation_task`, `ADMIN_OPERATION -> admin_operation`, `AUDIT_LOG -> audit_log`.
- [ ] Run `& $mvn -Dtest=SegmentBufferTest,SegmentIdGeneratorTest,IdServiceSegmentRoutingTest test`.
Expected: `BUILD SUCCESS`.

## Task 5: Migrate Current Business Call Sites

**Files:**
- Modify: `src/main/java/com/tongji/knowpost/service/impl/KnowPostServiceImpl.java`
- Modify: `src/main/java/com/tongji/relation/service/impl/RelationServiceImpl.java`
- Delete: `src/main/java/com/tongji/knowpost/id/SnowflakeIdGenerator.java`
- Inspect: `db/schema.sql`

- [ ] Confirm ID API exists:

```powershell
rg -n "interface IdService|enum IdNamespace|POST|RELATION|OUTBOX_EVENT|RECONCILIATION_TASK|ADMIN_OPERATION|AUDIT_LOG" src\main\java\com\tongji\common\id
```

- [ ] Confirm `users.id` remains `AUTO_INCREMENT`:

```powershell
rg -n "CREATE TABLE IF NOT EXISTS users|AUTO_INCREMENT" db\schema.sql
```

- [ ] In `KnowPostServiceImpl`, replace `com.tongji.knowpost.id.SnowflakeIdGenerator` with imports from `com.tongji.common.id.IdService` and `com.tongji.common.id.IdNamespace`.
- [ ] Replace post ID generation with `idService.nextId(IdNamespace.POST)`.
- [ ] Replace knowpost outbox ID generation with `idService.nextId(IdNamespace.OUTBOX_EVENT)`.
- [ ] Verify the active Leaf OpenSpec accepts the relation row namespace:

```powershell
rg -n "RELATION|relation row" openspec\changes\add-leaf-id-service\design.md openspec\changes\add-leaf-id-service\specs\id-service\spec.md openspec\changes\add-leaf-id-service\tasks.md
```

Expected: the active OpenSpec shows accepted relation row namespace requirements for `RELATION`.

- [ ] Inject `IdService` in `RelationServiceImpl`.
- [ ] Replace `following.id` generation with `idService.nextId(IdNamespace.RELATION)`.
- [ ] Replace `FollowCreated` and `FollowCanceled` outbox IDs with `idService.nextId(IdNamespace.OUTBOX_EVENT)`.
- [ ] Remove `ThreadLocalRandom` usage from relation write IDs.
- [ ] Delete `src/main/java/com/tongji/knowpost/id/SnowflakeIdGenerator.java`; do not keep a wrapper.
- [ ] Verify old references are gone:

```powershell
Test-Path 'src\main\java\com\tongji\knowpost\id\SnowflakeIdGenerator.java'
rg -n "com\.tongji\.knowpost\.id\.SnowflakeIdGenerator|import com\.tongji\.knowpost\.id" src
rg -n "SnowflakeIdGenerator|ThreadLocalRandom\.current\(\)\.nextLong|idGen" src\main\java\com\tongji\knowpost src\main\java\com\tongji\relation
```

Expected: first command returns `False`; both `rg` commands have no matches. The new `com.tongji.common.id.SnowflakeIdGenerator` is allowed.

- [ ] Verify user module does not use `IdService`:

```powershell
rg -n "IdService|IdNamespace|nextId\(" src\main\java\com\tongji\user
```

Expected: no user-module ID service calls.

- [ ] Run `& $mvn test`.
Expected: `BUILD SUCCESS`.

## Task 6: Smoke, Throughput, and Final Verification

**Files:**
- Create: `src/test/java/com/tongji/common/id/IdServiceSmokeTest.java`
- Optional Create: `src/test/java/com/tongji/common/id/IdServiceThroughputTest.java`
- Inspect: `target/surefire-reports`

- [ ] Create smoke coverage using direct construction with controlled fakes where possible, so the test does not require external MySQL.
- [ ] Snowflake path should generate at least `50_000` positive unique IDs through a Snowflake namespace such as `POST`.
- [ ] Segment path should generate at least `10_000` positive unique IDs through `RECONCILIATION_TASK`.
- [ ] Use 5-10 second timeouts. If local environment is slow, reduce counts to `10_000` and `5_000`, and document the adjustment.
- [ ] Run `& $mvn -Dtest=IdServiceSmokeTest test`.
Expected: `BUILD SUCCESS`.

- [ ] Run full verification:

```powershell
& $mvn test
rg -n "com\.tongji\.knowpost\.id\.SnowflakeIdGenerator|import com\.tongji\.knowpost\.id" src db
rg -n "SnowflakeIdGenerator|ThreadLocalRandom\.current\(\)\.nextLong|idGen" src\main\java\com\tongji\knowpost src\main\java\com\tongji\relation
rg -n "POST|COMMENT|PENDING_COMMENT|PUBLISH_ATTEMPT|RELATION|OUTBOX_EVENT|RECONCILIATION_TASK|ADMIN_OPERATION|AUDIT_LOG" src\main\java\com\tongji\common\id\IdNamespace.java
openspec status --change "add-leaf-id-service" --json
openspec validate add-leaf-id-service --strict
```

Expected: Maven success, no residual old-package/random-ID search output, all accepted namespaces including `RELATION` defined, OpenSpec status available, strict validation success. If this OpenSpec CLI does not support `validate --strict`, record the unsupported command and the reason.

## Task 7: OpenSpec Task Closure

**Files:**
- Modify checkboxes only: `openspec/changes/add-leaf-id-service/tasks.md`

- [ ] Only proceed if Task 6 verification passed.
- [ ] Do not change OpenSpec design/spec semantics in this closure task.
- [ ] Mark a checkbox `[x]` only when evidence exists:

| Task | Evidence |
|---|---|
| 1.1 | `leaf_alloc` table exists |
| 1.2 | three Segment biz tags initialized |
| 1.3 | Snowflake worker/datacenter config exists |
| 2.1 | `IdService` exists |
| 2.2 | Snowflake implementation and tests exist |
| 2.3 | Segment double buffer exists |
| 2.4 | namespace-to-mode routing exists |
| 2.5 | Segment failure and Snowflake clock rollback handling exist |
| 3.1 | current Snowflake IDs, including relation row IDs through `RELATION` and relation outbox IDs through `OUTBOX_EVENT`, route through `IdService`; future namespaces defined |
| 3.2 | Segment namespaces route through Segment |
| 3.3 | user ID remains auto-increment and user module does not call `IdService` |
| 4.1 | Snowflake concurrency test passes |
| 4.2 | Segment buffer switch test passes |
| 4.3 | smoke/throughput test passes |

- [ ] Validate markdown:

```powershell
Get-Content -Raw openspec\changes\add-leaf-id-service\tasks.md
```

Expected: checkbox format intact.

- [ ] Rerun final evidence commands:

```powershell
& $mvn test
rg -n "com\.tongji\.knowpost\.id\.SnowflakeIdGenerator|import com\.tongji\.knowpost\.id" src db
rg -n "SnowflakeIdGenerator|ThreadLocalRandom\.current\(\)\.nextLong|idGen" src\main\java\com\tongji\knowpost src\main\java\com\tongji\relation
openspec status --change "add-leaf-id-service" --json
openspec validate add-leaf-id-service --strict
```

Expected: same success/no-output evidence as Task 6.

## Final Handoff

Return:

- files changed/deleted
- Maven commands run and outcomes
- smoke/throughput counts and outcome
- residual search commands and outcome
- confirmation that relation row ID migration used accepted `IdNamespace.RELATION` and did not reuse `OUTBOX_EVENT`
- confirmation that user ID strategy stayed auto-increment
- whether `openspec/changes/add-leaf-id-service/tasks.md` was updated
- OpenSpec status and strict validation result

Do not archive the change in this plan.
