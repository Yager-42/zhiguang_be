# Leaf ID Service Plan 2 - Segment Buffer Implementation Plan

> **For agentic workers:** REQUIRED: Use `superpowers:subagent-driven-development` (if subagents available) or `superpowers:executing-plans` to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement Leaf Segment mode with double buffering so low-frequency operational namespaces can generate IDs through `IdService`.

**Architecture:** `DefaultIdService` routes Segment namespaces to `SegmentIdGenerator`. `SegmentIdGenerator` keeps one `SegmentBuffer` per biz tag. Each buffer loads ranges from `leaf_alloc`, preloads the next range at 50% consumption, switches on exhaustion, and fails fast when a new range cannot be loaded within timeout.

**Tech Stack:** Java 21, Spring Boot 3.2.4, MyBatis, MySQL 8 schema SQL, JUnit 5, Mockito, Maven.

---

## Command Setup

Run this once before executing Maven commands in this plan:

```powershell
$mvn = Join-Path $env:USERPROFILE 'Desktop\文档\.codex\tools\apache-maven-3.9.6\bin\mvn.cmd'
Test-Path $mvn
```

Expected: `True`.

## Subagent Context

You are implementing only Plan 2 of OpenSpec change `add-leaf-id-service`.

Plan 1 should already provide:

- `src/main/java/com/tongji/common/id/IdService.java`
- `src/main/java/com/tongji/common/id/IdNamespace.java`
- `src/main/java/com/tongji/common/id/IdMode.java`
- `src/main/java/com/tongji/common/id/DefaultIdService.java`
- Snowflake implementation and tests

This plan covers OpenSpec tasks `1.1`, `1.2`, `2.3`, Segment portions of `2.4` and `2.5`, and `4.2`.

Do not migrate business services, do not delete old Snowflake code, and do not mark OpenSpec tasks complete.

## Files

- Modify: `db/schema.sql`
- Modify: `src/main/resources/application.yml`
- Modify: `src/main/java/com/tongji/common/id/DefaultIdService.java`
- Create: `src/main/java/com/tongji/common/id/segment/LeafAlloc.java`
- Create: `src/main/java/com/tongji/common/id/segment/LeafAllocMapper.java`
- Create: `src/main/java/com/tongji/common/id/segment/SegmentAllocator.java`
- Create: `src/main/resources/mapper/LeafAllocMapper.xml`
- Create: `src/main/java/com/tongji/common/id/segment/SegmentRange.java`
- Create: `src/main/java/com/tongji/common/id/segment/SegmentLoader.java`
- Create: `src/main/java/com/tongji/common/id/segment/SegmentBuffer.java`
- Create: `src/main/java/com/tongji/common/id/segment/SegmentIdGenerator.java`
- Create: `src/main/java/com/tongji/common/id/segment/SegmentIdProperties.java`
- Create: `src/main/java/com/tongji/common/id/segment/SegmentLoadException.java`
- Create: `src/test/java/com/tongji/common/id/segment/SegmentBufferTest.java`
- Create: `src/test/java/com/tongji/common/id/segment/SegmentIdGeneratorTest.java`
- Create: `src/test/java/com/tongji/common/id/IdServiceSegmentRoutingTest.java`

## Task 1: Add `leaf_alloc` Schema

**Files:**
- Modify: `db/schema.sql`

- [ ] **Step 1: Add table**

Append:

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

- [ ] **Step 2: Add initialization rows**

Use an idempotent insert that does not reset `max_id`:

```sql
INSERT INTO leaf_alloc (biz_tag, max_id, step, description) VALUES
    ('reconciliation_task', 1, 1000, 'Reconciliation task ID'),
    ('admin_operation', 1, 1000, 'Admin operation ID'),
    ('audit_log', 1, 1000, 'Audit log ID')
ON DUPLICATE KEY UPDATE
    step = VALUES(step),
    description = VALUES(description);
```

- [ ] **Step 3: Verify**

```powershell
Select-String -Path 'db/schema.sql' -Pattern 'leaf_alloc','reconciliation_task','admin_operation','audit_log'
```

Expected: all patterns found.

## Task 2: Add Mapper and Range Model

**Files:**
- Create: `src/main/java/com/tongji/common/id/segment/LeafAlloc.java`
- Create: `src/main/java/com/tongji/common/id/segment/LeafAllocMapper.java`
- Create: `src/main/java/com/tongji/common/id/segment/SegmentAllocator.java`
- Create: `src/main/resources/mapper/LeafAllocMapper.xml`
- Create: `src/main/java/com/tongji/common/id/segment/SegmentRange.java`
- Create: `src/main/java/com/tongji/common/id/segment/SegmentLoader.java`

- [ ] **Step 1: Create `LeafAlloc`**

Fields: `String bizTag`, `Long maxId`, `Integer step`, with getters/setters.

- [ ] **Step 2: Create mapper interface**

```java
@Mapper
public interface LeafAllocMapper {
    int updateMaxId(@Param("bizTag") String bizTag);
    LeafAlloc selectByBizTag(@Param("bizTag") String bizTag);
}
```

Also create `SegmentAllocator` as an independent Spring service/collaborator, for example with `@Service`, and inject it into `SegmentIdGenerator`. Put `SegmentRange allocateSegment(String bizTag)` on that collaborator and mark it `@Transactional`, so `updateMaxId` and `selectByBizTag` run through the Spring proxy in one transaction. Do not put this transactional method only inside `SegmentIdGenerator` and call it by self-invocation; that would bypass Spring transaction proxying and can allocate overlapping ranges across app instances.

- [ ] **Step 3: Create mapper XML**

Namespace: `com.tongji.common.id.segment.LeafAllocMapper`.

`updateMaxId`:

```sql
UPDATE leaf_alloc SET max_id = max_id + step WHERE biz_tag = #{bizTag}
```

`selectByBizTag` must alias snake_case columns to Java fields:

```sql
SELECT biz_tag AS bizTag, max_id AS maxId, step AS step
FROM leaf_alloc
WHERE biz_tag = #{bizTag}
```

- [ ] **Step 4: Create `SegmentRange` and `SegmentLoader`**

`SegmentRange` should reject `startInclusive > endInclusive`. `SegmentLoader` is a functional interface: `SegmentRange load(String bizTag)`.

- [ ] **Step 5: Compile**

```powershell
& $mvn test
```

Expected: `BUILD SUCCESS`.

## Task 3: Test and Implement `SegmentBuffer`

**Files:**
- Create: `src/main/java/com/tongji/common/id/segment/SegmentBuffer.java`
- Create: `src/main/java/com/tongji/common/id/segment/SegmentLoadException.java`
- Create: `src/test/java/com/tongji/common/id/segment/SegmentBufferTest.java`

- [ ] **Step 1: Write failing switch test**

Fake loader returns `[1,4]`, then `[5,8]`. Five `nextId()` calls should return `1,2,3,4,5`.

Run:

```powershell
& $mvn -Dtest=SegmentBufferTest test
```

Expected: FAIL before implementation.

- [ ] **Step 2: Write failing preload test**

Assert consuming 50% of `[1,4]` triggers exactly one async preload.

- [ ] **Step 3: Implement initial load, 50% preload, and switch**

Use a small synchronized critical section or atomic state. Treat `SegmentRange` as a closed interval `[startInclusive, endInclusive]`. Keep tests deterministic with fake executor or single-thread executor.

- [ ] **Step 4: Add failure and timeout tests**

Cover:

- preload failure does not corrupt current range
- preload can retry
- exhaustion waits at most `waitTimeout`
- timeout throws `SegmentLoadException`

`SegmentLoadException` must extend `RuntimeException` so allocator failures propagate cleanly and Spring transaction rollback semantics remain the default.

- [ ] **Step 5: Complete implementation**

Run:

```powershell
& $mvn -Dtest=SegmentBufferTest test
```

Expected: `BUILD SUCCESS`.

## Task 4: Implement Segment Generator

**Files:**
- Create: `src/main/java/com/tongji/common/id/segment/SegmentIdProperties.java`
- Create: `src/main/java/com/tongji/common/id/segment/SegmentIdGenerator.java`
- Create: `src/test/java/com/tongji/common/id/segment/SegmentIdGeneratorTest.java`
- Modify: `src/main/resources/application.yml`

- [ ] **Step 1: Add config**

Merge into existing `id:` block:

```yaml
id:
  segment:
    wait-timeout-ms: ${ID_SEGMENT_WAIT_TIMEOUT_MS:500}
    preload-threads: ${ID_SEGMENT_PRELOAD_THREADS:2}
```

- [ ] **Step 2: Create `SegmentIdProperties`**

Fields: `waitTimeoutMs = 500`, `preloadThreads = 2`. Register it for Spring binding with `@Component` and `@ConfigurationProperties(prefix = "id.segment")`, or use equivalent explicit configuration.

- [ ] **Step 3: Write mapper allocation test**

Mock the transactional allocator path: after `updateMaxId("audit_log")`, `selectByBizTag("audit_log")` returns `maxId=1001`, `step=1000`; first range should be `[2,1001]`.

- [ ] **Step 4: Implement `SegmentIdGenerator`**

Use a `ConcurrentHashMap<String, SegmentBuffer>` and create each buffer with `computeIfAbsent` or an equivalent atomic operation, so concurrent first requests for the same biz tag cannot create multiple buffers and allocate duplicate initial segments. The mapper loader must call the injected `SegmentAllocator`. Allocation algorithm inside `SegmentAllocator.allocateSegment`:

```java
int updated = mapper.updateMaxId(bizTag);
if (updated != 1) throw new SegmentLoadException(...);
LeafAlloc alloc = mapper.selectByBizTag(bizTag);
long start = alloc.getMaxId() - alloc.getStep() + 1;
long end = alloc.getMaxId();
```

If `SegmentIdGenerator` owns an `ExecutorService` for async preloading, make its lifecycle explicit: either inject a Spring-managed executor bean or shut down the private executor in `@PreDestroy`. Tests must not leave non-daemon preload threads running.

- [ ] **Step 5: Test unknown biz tag and mapper failures**

`updateMaxId != 1`, null select result, and mapper exceptions must become `SegmentLoadException`.

- [ ] **Step 6: Run targeted tests**

```powershell
& $mvn -Dtest=SegmentIdGeneratorTest test
```

Expected: `BUILD SUCCESS`.

## Task 5: Route Segment Namespaces Through `IdService`

**Files:**
- Modify: `src/main/java/com/tongji/common/id/DefaultIdService.java`
- Create: `src/test/java/com/tongji/common/id/IdServiceSegmentRoutingTest.java`

- [ ] **Step 1: Write routing test**

Mock `SegmentIdGenerator`. Assert:

- `RECONCILIATION_TASK` maps to `reconciliation_task`
- `ADMIN_OPERATION` maps to `admin_operation`
- `AUDIT_LOG` maps to `audit_log`

- [ ] **Step 2: Inject `SegmentIdGenerator` into `DefaultIdService`**

Keep Snowflake behavior unchanged. Add a private `toBizTag(IdNamespace namespace)` method.

- [ ] **Step 3: Run routing test**

```powershell
& $mvn -Dtest=IdServiceSegmentRoutingTest test
```

Expected: `BUILD SUCCESS`.

## Task 6: Plan 2 Verification

**Files:**
- All Plan 2 files

- [ ] **Step 1: Run segment tests**

```powershell
& $mvn -Dtest=SegmentBufferTest,SegmentIdGeneratorTest,IdServiceSegmentRoutingTest test
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 2: Run full tests**

```powershell
& $mvn test
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 3: Verify write scope**

```powershell
git diff --name-only
```

Expected: schema/config/common-id/segment/test files only; no business service migration.

If Git refuses with `detected dubious ownership`, do not change global Git configuration unless the user explicitly approves it. Use this fallback to inspect touched areas by timestamp:

```powershell
Get-ChildItem -Recurse src\main\java\com\tongji\common\id,src\test\java\com\tongji\common\id,db,src\main\resources |
  Select-Object FullName,LastWriteTime
```

## Handoff

Return:

- files changed
- segment tests run and outcomes
- whether `DefaultIdService` now supports Segment
- any implementation notes about timeout/preload behavior

