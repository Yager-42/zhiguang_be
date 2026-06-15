# Leaf ID Service Plan 1 - ID API and Snowflake Implementation Plan

> **For agentic workers:** REQUIRED: Use `superpowers:subagent-driven-development` (if subagents available) or `superpowers:executing-plans` to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Create the unified ID API and Snowflake path so high-throughput namespaces can generate IDs through `IdService`.

**Architecture:** Add `com.tongji.common.id` as the shared ID package. `IdService` routes by `IdNamespace`; Snowflake is implemented in the common package and configured from `application.yml`. Segment namespaces are declared but may throw until Plan 2 connects Segment.

**Tech Stack:** Java 21, Spring Boot 3.2.4, Maven, JUnit 5.

---

## Command Setup

Run this once before executing Maven commands in this plan:

```powershell
$mvn = Join-Path $env:USERPROFILE 'Desktop\文档\.codex\tools\apache-maven-3.9.6\bin\mvn.cmd'
Test-Path $mvn
```

Expected: `True`.

## Subagent Context

You are implementing only Plan 1 of OpenSpec change `add-leaf-id-service`.

Read before editing:

- `openspec/changes/add-leaf-id-service/proposal.md`
- `openspec/changes/add-leaf-id-service/design.md`
- `openspec/changes/add-leaf-id-service/specs/id-service/spec.md`
- `openspec/changes/add-leaf-id-service/tasks.md`
- `src/main/java/com/tongji/knowpost/id/SnowflakeIdGenerator.java`
- `src/main/resources/application.yml`
- `src/main/java/com/tongji/ZhiGuangApplication.java`

This plan covers OpenSpec tasks `1.3`, `2.1`, `2.2`, Snowflake portions of `2.4` and `2.5`, and `4.1`.

Do not implement Segment, do not create `leaf_alloc`, do not migrate business services, and do not delete the old `knowpost/id/SnowflakeIdGenerator.java`.

## Files

- Create: `src/main/java/com/tongji/common/id/IdService.java`
- Create: `src/main/java/com/tongji/common/id/IdMode.java`
- Create: `src/main/java/com/tongji/common/id/IdNamespace.java`
- Create: `src/main/java/com/tongji/common/id/ClockBackwardException.java`
- Create: `src/main/java/com/tongji/common/id/SnowflakeProperties.java`
- Create: `src/main/java/com/tongji/common/id/SnowflakeIdGenerator.java`
- Create: `src/main/java/com/tongji/common/id/DefaultIdService.java`
- Create: `src/test/java/com/tongji/common/id/SnowflakeIdGeneratorTest.java`
- Modify: `src/main/resources/application.yml`
- Modify: `openspec/changes/add-leaf-id-service/design.md`
- Modify: `openspec/changes/add-leaf-id-service/specs/id-service/spec.md`
- Modify: `openspec/changes/add-leaf-id-service/tasks.md`

## Required Namespace Set

`IdNamespace` must include:

```java
POST(IdMode.SNOWFLAKE),
COMMENT(IdMode.SNOWFLAKE),
PENDING_COMMENT(IdMode.SNOWFLAKE),
PUBLISH_ATTEMPT(IdMode.SNOWFLAKE),
OUTBOX_EVENT(IdMode.SNOWFLAKE),
RELATION(IdMode.SNOWFLAKE),
RECONCILIATION_TASK(IdMode.SEGMENT),
ADMIN_OPERATION(IdMode.SEGMENT),
AUDIT_LOG(IdMode.SEGMENT);
```

Update the OpenSpec design/spec/tasks so `RELATION` is documented as the Snowflake namespace for existing relation row IDs. This avoids Plan 3 having to invent it later.

## Task 1: Add Snowflake Configuration

**Files:**
- Modify: `src/main/resources/application.yml`

- [ ] **Step 1: Add config**

Add a top-level block:

```yaml
id:
  snowflake:
    worker-id: ${SNOWFLAKE_WORKER_ID:1}
    datacenter-id: ${SNOWFLAKE_DATACENTER_ID:1}
```

- [ ] **Step 2: Verify YAML loads**

Run:

```powershell
& $mvn test
```

Expected: `BUILD SUCCESS`, or no YAML/config binding error if unrelated existing tests fail.

## Task 2: Define ID API and Namespace Routing

**Files:**
- Create: `src/main/java/com/tongji/common/id/IdService.java`
- Create: `src/main/java/com/tongji/common/id/IdMode.java`
- Create: `src/main/java/com/tongji/common/id/IdNamespace.java`
- Modify: `openspec/changes/add-leaf-id-service/design.md`
- Modify: `openspec/changes/add-leaf-id-service/specs/id-service/spec.md`
- Modify: `openspec/changes/add-leaf-id-service/tasks.md`

- [ ] **Step 1: Create `IdMode`**

```java
package com.tongji.common.id;

public enum IdMode {
    SNOWFLAKE,
    SEGMENT
}
```

- [ ] **Step 2: Create `IdNamespace`**

Use the required namespace set above. Provide a getter named either `getMode()` or `mode()`, then use that same method consistently.

- [ ] **Step 3: Create `IdService`**

```java
package com.tongji.common.id;

public interface IdService {
    long nextId(IdNamespace namespace);
}
```

- [ ] **Step 4: Sync OpenSpec for `RELATION`**

Update `design.md`, `spec.md`, and `tasks.md` so relation row IDs are explicitly included in Snowflake scope. This step is only a namespace/documentation correction: do not migrate `RelationServiceImpl`, do not replace relation ID calls, and do not mark tasks complete.

- [ ] **Step 5: Compile**

Run:

```powershell
& $mvn test
```

Expected: `BUILD SUCCESS`.

## Task 3: Add Snowflake Properties and Exception

**Files:**
- Create: `src/main/java/com/tongji/common/id/SnowflakeProperties.java`
- Create: `src/main/java/com/tongji/common/id/ClockBackwardException.java`

- [ ] **Step 1: Create `SnowflakeProperties`**

Use `@Component` and `@ConfigurationProperties(prefix = "id.snowflake")`. Include `workerId` and `datacenterId`, both defaulting to `1`.

- [ ] **Step 2: Create `ClockBackwardException`**

Create a runtime exception with constructors `(String message)` and `(String message, Throwable cause)`.

- [ ] **Step 3: Compile**

Run:

```powershell
& $mvn test
```

Expected: `BUILD SUCCESS`.

## Task 4: Implement Common Snowflake Generator

**Files:**
- Create: `src/main/java/com/tongji/common/id/SnowflakeIdGenerator.java`
- Read: `src/main/java/com/tongji/knowpost/id/SnowflakeIdGenerator.java`

- [ ] **Step 1: Port algorithm**

Port the existing algorithm to package `com.tongji.common.id` and register it as a Spring bean. Because the old `com.tongji.knowpost.id.SnowflakeIdGenerator` remains a `@Component` until Plan 3, avoid the default duplicate bean name by using `@Component("commonSnowflakeIdGenerator")` or an equivalent explicit bean name. Keep:

- epoch `1704067200000L`
- 5 worker bits
- 5 datacenter bits
- 12 sequence bits
- synchronized `nextId()`
- sequence overflow waits for next millisecond

- [ ] **Step 2: Add Spring constructor**

The bean constructor should accept `SnowflakeProperties`. Also keep a test-friendly constructor accepting `datacenterId` and `workerId`.

- [ ] **Step 3: Preserve validation and clock rollback behavior**

Worker/datacenter must be `0..31`. Rollback `<= 5ms` waits; rollback `> 5ms` throws `ClockBackwardException`.

- [ ] **Step 4: Compile**

Run:

```powershell
& $mvn test
```

Expected: `BUILD SUCCESS`.

## Task 5: Implement Default ID Service for Snowflake

**Files:**
- Create: `src/main/java/com/tongji/common/id/DefaultIdService.java`

- [ ] **Step 1: Create Spring service**

Inject the common-package `SnowflakeIdGenerator`. If using the named bean from Task 4, use `@Qualifier("commonSnowflakeIdGenerator")` on the constructor parameter or another explicit wiring approach.

- [ ] **Step 2: Route Snowflake namespaces**

If `namespace` mode is `SNOWFLAKE`, return `snowflakeIdGenerator.nextId()`.

- [ ] **Step 3: Block Segment until Plan 2**

For `SEGMENT`, throw `UnsupportedOperationException("Segment ID mode is not implemented yet: " + namespace)`.

- [ ] **Step 4: Compile**

Run:

```powershell
& $mvn test
```

Expected: `BUILD SUCCESS`.

## Task 6: Add Snowflake Tests

**Files:**
- Create: `src/test/java/com/tongji/common/id/SnowflakeIdGeneratorTest.java`

- [ ] **Step 1: Test concurrent uniqueness**

Create a JUnit test generating `16 * 1000` IDs concurrently. Assert every ID is positive and unique.

- [ ] **Step 2: Test namespace routing**

Instantiate `DefaultIdService` with a test `SnowflakeIdGenerator`. Assert `POST`, `COMMENT`, `PENDING_COMMENT`, `PUBLISH_ATTEMPT`, `OUTBOX_EVENT`, and `RELATION` all return positive unique IDs.

- [ ] **Step 3: Test Segment is not implemented in Plan 1**

Assert `RECONCILIATION_TASK`, `ADMIN_OPERATION`, and `AUDIT_LOG` throw `UnsupportedOperationException`.

- [ ] **Step 4: Test config defaults and invalid worker IDs**

Assert default properties are `1`, and invalid worker/datacenter IDs throw `IllegalArgumentException`.

- [ ] **Step 5: Run targeted tests**

Run:

```powershell
& $mvn -Dtest=SnowflakeIdGeneratorTest test
```

Expected: `BUILD SUCCESS`.

## Task 7: Plan 1 Verification

**Files:**
- All Plan 1 files

- [ ] **Step 1: Run full tests**

```powershell
& $mvn test
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 2: Confirm old class still exists**

```powershell
Test-Path 'src\main\java\com\tongji\knowpost\id\SnowflakeIdGenerator.java'
```

Expected: `True`.

- [ ] **Step 3: Confirm no Segment implementation was added**

```powershell
rg -n "SegmentIdGenerator|LeafAllocMapper|SegmentBuffer" src\main\java\com\tongji\common\id
```

Expected: no output.

## Handoff

Return:

- files changed
- test commands run and outcomes
- whether `RELATION` namespace was added to OpenSpec and `IdNamespace`
- any unrelated existing test failures

