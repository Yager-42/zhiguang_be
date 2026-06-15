# Leaf ID Service Plan 3 - Existing Integration Migration Implementation Plan

> **For agentic workers:** REQUIRED: Use `superpowers:subagent-driven-development` (if subagents available) or `superpowers:executing-plans` to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Migrate existing ID generation call sites to `IdService`, delete the old knowpost-specific Snowflake class, and preserve current MySQL auto-increment user IDs.

**Architecture:** Existing services must depend on `IdService` instead of concrete generators or random IDs. Each call site uses an explicit `IdNamespace`: `POST` for knowpost IDs, `OUTBOX_EVENT` for outbox IDs, and `RELATION` for relation row IDs.

**Tech Stack:** Java 21, Spring Boot 3.2.4, Maven, MyBatis, JUnit 5.

---

## Command Setup

Run this once before executing Maven commands in this plan:

```powershell
$mvn = Join-Path $env:USERPROFILE 'Desktop\文档\.codex\tools\apache-maven-3.9.6\bin\mvn.cmd'
Test-Path $mvn
```

Expected: `True`.

## Subagent Context

You are implementing only Plan 3 of OpenSpec change `add-leaf-id-service`.

Plan 1 and Plan 2 must already be complete.

Read before editing:

- `src/main/java/com/tongji/common/id/IdService.java`
- `src/main/java/com/tongji/common/id/IdNamespace.java`
- `src/main/java/com/tongji/knowpost/service/impl/KnowPostServiceImpl.java`
- `src/main/java/com/tongji/relation/service/impl/RelationServiceImpl.java`
- `src/main/java/com/tongji/knowpost/id/SnowflakeIdGenerator.java`
- `db/schema.sql`

This plan covers OpenSpec tasks `3.1`, `3.2`, and `3.3` for currently existing code. Do not implement future comment, publish-attempt, reconciliation, admin, or audit business modules.

## Files

- Modify: `src/main/java/com/tongji/knowpost/service/impl/KnowPostServiceImpl.java`
- Modify: `src/main/java/com/tongji/relation/service/impl/RelationServiceImpl.java`
- Delete: `src/main/java/com/tongji/knowpost/id/SnowflakeIdGenerator.java`
- Inspect: `src/main/java/com/tongji/common/id/IdNamespace.java`
- Inspect: `db/schema.sql`

## Task 1: Preflight Checks

**Files:**
- Inspect: `src/main/java/com/tongji/common/id`

- [ ] **Step 1: Confirm ID API exists**

```powershell
rg -n "interface IdService|enum IdNamespace|POST|OUTBOX_EVENT|RELATION|RECONCILIATION_TASK|ADMIN_OPERATION|AUDIT_LOG" src\main\java\com\tongji\common\id
```

Expected: all names found.

- [ ] **Step 2: Confirm Segment namespace exists**

```powershell
rg -n "RECONCILIATION_TASK|ADMIN_OPERATION|AUDIT_LOG|SEGMENT" src\main\java\com\tongji\common\id
```

Expected: all three route to Segment.

- [ ] **Step 3: Confirm user IDs remain auto-increment**

```powershell
rg -n "CREATE TABLE IF NOT EXISTS users|AUTO_INCREMENT" db\schema.sql
```

Expected: `users.id` remains `AUTO_INCREMENT`.

## Task 2: Migrate `KnowPostServiceImpl`

**Files:**
- Modify: `src/main/java/com/tongji/knowpost/service/impl/KnowPostServiceImpl.java`

- [ ] **Step 1: Replace imports**

Remove:

```java
import com.tongji.knowpost.id.SnowflakeIdGenerator;
```

Add:

```java
import com.tongji.common.id.IdNamespace;
import com.tongji.common.id.IdService;
```

- [ ] **Step 2: Replace field and constructor injection**

Replace `SnowflakeIdGenerator idGen` with `IdService idService`. Remove unused `@Resource` import/annotation if no longer needed.

- [ ] **Step 3: Replace post ID generation**

Change draft creation to:

```java
long id = idService.nextId(IdNamespace.POST);
```

- [ ] **Step 4: Replace knowpost outbox ID generation**

Every knowpost outbox ID should use:

```java
long outId = idService.nextId(IdNamespace.OUTBOX_EVENT);
```

- [ ] **Step 5: Verify file**

```powershell
rg -n "SnowflakeIdGenerator|idGen|nextId\(\)" src\main\java\com\tongji\knowpost\service\impl\KnowPostServiceImpl.java
```

Expected: no output.

- [ ] **Step 6: Compile**

```powershell
& $mvn test
```

Expected: `BUILD SUCCESS`.

## Task 3: Migrate `RelationServiceImpl`

**Files:**
- Modify: `src/main/java/com/tongji/relation/service/impl/RelationServiceImpl.java`

- [ ] **Step 1: Add ID imports and field**

Add:

```java
import com.tongji.common.id.IdNamespace;
import com.tongji.common.id.IdService;
```

Add field:

```java
private final IdService idService;
```

- [ ] **Step 2: Update constructor**

Add `IdService idService` to constructor parameters and assign `this.idService = idService;`.

- [ ] **Step 3: Replace relation row ID generation**

Change the `following.id` generation in `follow()` to:

```java
long id = idService.nextId(IdNamespace.RELATION);
```

Do not use `OUTBOX_EVENT` for relation row IDs.

- [ ] **Step 4: Replace relation outbox ID generation**

Both `FollowCreated` and `FollowCanceled` outbox IDs should use:

```java
Long outId = idService.nextId(IdNamespace.OUTBOX_EVENT);
```

- [ ] **Step 5: Remove unused random import**

Remove `java.util.concurrent.ThreadLocalRandom` if it is no longer used.

- [ ] **Step 6: Verify file**

```powershell
rg -n "ThreadLocalRandom\.current\(\)\.nextLong|IdNamespace\.OUTBOX_EVENT|IdNamespace\.RELATION" src\main\java\com\tongji\relation\service\impl\RelationServiceImpl.java
```

Expected: no random ID generation; `OUTBOX_EVENT` and `RELATION` are present.

- [ ] **Step 7: Compile**

```powershell
& $mvn test
```

Expected: `BUILD SUCCESS`.

## Task 4: Delete Old Snowflake Class

**Files:**
- Delete: `src/main/java/com/tongji/knowpost/id/SnowflakeIdGenerator.java`

- [ ] **Step 1: Delete file**

Delete the old concrete class. Do not keep a compatibility wrapper.

- [ ] **Step 2: Verify deletion and references**

```powershell
Test-Path 'src\main\java\com\tongji\knowpost\id\SnowflakeIdGenerator.java'
rg -n "com\.tongji\.knowpost\.id\.SnowflakeIdGenerator|import com\.tongji\.knowpost\.id" src
rg -n "SnowflakeIdGenerator|ThreadLocalRandom\.current\(\)\.nextLong|idGen" src\main\java\com\tongji\knowpost src\main\java\com\tongji\relation
```

Expected: first command returns `False`; both `rg` commands have no matches. The new `com.tongji.common.id.SnowflakeIdGenerator` may still exist and is allowed.

- [ ] **Step 3: Compile**

```powershell
& $mvn test
```

Expected: `BUILD SUCCESS`.

## Task 5: Verify Integration Scope

**Files:**
- Inspect: `src/main/java`
- Inspect: `db/schema.sql`

- [ ] **Step 1: Required residual search**

```powershell
rg -n "com\.tongji\.knowpost\.id\.SnowflakeIdGenerator|import com\.tongji\.knowpost\.id" src
rg -n "SnowflakeIdGenerator|ThreadLocalRandom\.current\(\)\.nextLong|idGen" src\main\java\com\tongji\knowpost src\main\java\com\tongji\relation
```

Expected: no output. Do not treat the common-package Snowflake implementation as a residual reference.

- [ ] **Step 2: Ensure all business calls use namespaces**

```powershell
rg -n "nextId\(" src\main\java
```

Expected: business calls are `idService.nextId(IdNamespace.X)`, not empty-arg generator calls. Ignore API/implementation methods inside `src/main/java/com/tongji/common/id`.

- [ ] **Step 3: Verify user IDs unchanged**

```powershell
rg -n "IdService|IdNamespace|nextId\(" src\main\java\com\tongji\user
rg -n "users \(|users|AUTO_INCREMENT" db\schema.sql
```

Expected: user module does not call `IdService`; `users.id` remains auto-increment.

- [ ] **Step 4: Full test**

```powershell
& $mvn test
```

Expected: `BUILD SUCCESS`.

## Handoff

Return:

- files changed/deleted
- all ID-generation call sites migrated
- residual search result
- Maven result
- confirmation that user ID strategy was not changed

