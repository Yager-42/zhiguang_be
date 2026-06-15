# Leaf ID Service Plan 4 - Final Verification and OpenSpec Closure Implementation Plan

> **For agentic workers:** REQUIRED: Use `superpowers:subagent-driven-development` (if subagents available) or `superpowers:executing-plans` to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add final smoke/throughput verification for `IdService`, run full validation, and close `add-leaf-id-service` OpenSpec tasks only when evidence supports it.

**Architecture:** This plan assumes Plans 1-3 are complete. It does not redesign ID internals. It adds light JUnit smoke coverage, runs repository verification, searches for direct-generator leftovers, then updates `tasks.md`.

**Tech Stack:** Java 21, Spring Boot 3.2.4, JUnit 5, Maven, OpenSpec, ripgrep.

---

## Command Setup

Run this once before executing Maven commands in this plan:

```powershell
$mvn = Join-Path $env:USERPROFILE 'Desktop\文档\.codex\tools\apache-maven-3.9.6\bin\mvn.cmd'
Test-Path $mvn
```

Expected: `True`.

## Subagent Context

You are implementing only Plan 4 of OpenSpec change `add-leaf-id-service`.

Read before editing:

- `openspec/changes/add-leaf-id-service/tasks.md`
- `openspec/changes/add-leaf-id-service/specs/id-service/spec.md`
- `src/main/java/com/tongji/common/id`
- `src/main/java/com/tongji/common/id/segment`
- `src/test/java`
- `db/schema.sql`
- `src/main/resources/application.yml`

Do not mark any task complete until verification commands pass.

## Files

- Create: `src/test/java/com/tongji/common/id/IdServiceSmokeTest.java`
- Optional Create: `src/test/java/com/tongji/common/id/IdServiceThroughputTest.java`
- Modify: `openspec/changes/add-leaf-id-service/tasks.md`

## Task 1: Preflight Plans 1-3

**Files:**
- Inspect: `src/main/java/com/tongji/common/id`
- Inspect: `db/schema.sql`
- Inspect: `src/main/resources/application.yml`

- [ ] **Step 1: Check ID service exists**

```powershell
rg -n "interface IdService|class .*IdService|enum IdNamespace|nextId\(" src\main\java\com\tongji\common\id
```

Expected: unified ID API and implementation found.

- [ ] **Step 2: Check Segment schema and init rows**

```powershell
rg -n "leaf_alloc|reconciliation_task|admin_operation|audit_log" db src\main\resources src\main\java
```

Expected: schema and all three biz tags found.

- [ ] **Step 3: Check Snowflake config**

```powershell
rg -n "snowflake|worker-id|datacenter-id|SNOWFLAKE_WORKER_ID|SNOWFLAKE_DATACENTER_ID" src\main\resources src\main\java
```

Expected: worker/datacenter config found.

- [ ] **Step 4: Check old generator is gone**

```powershell
Test-Path 'src\main\java\com\tongji\knowpost\id\SnowflakeIdGenerator.java'
rg -n "com\.tongji\.knowpost\.id\.SnowflakeIdGenerator|import com\.tongji\.knowpost\.id" src
rg -n "SnowflakeIdGenerator|ThreadLocalRandom\.current\(\)\.nextLong|idGen" src\main\java\com\tongji\knowpost src\main\java\com\tongji\relation
```

Expected: first command returns `False`; both `rg` commands have no matches. The new `com.tongji.common.id.SnowflakeIdGenerator` is the intended implementation and must not be treated as leftover code.

## Task 2: Add Smoke and Throughput Test

**Files:**
- Create: `src/test/java/com/tongji/common/id/IdServiceSmokeTest.java`

- [ ] **Step 1: Follow existing test style**

```powershell
rg -n "@Test|@SpringBootTest|assertEquals|Mockito" src\test\java
```

Expected: confirm JUnit style.

- [ ] **Step 2: Create smoke test**

Prefer direct construction with a controlled fake `SegmentAllocator`/loader so the smoke test does not require an external database. If the final implementation only supports Spring/MyBatis construction, use minimal `@SpringBootTest` and state the precondition in the handoff: the database must be running and `db/schema.sql` must already be applied.

Required behavior:

- Snowflake path generates at least `50_000` positive unique IDs via a Snowflake namespace such as `POST`.
- Segment path generates at least `10_000` positive unique IDs via `RECONCILIATION_TASK`.
- Use broad timeouts such as 5-10 seconds. If local environment is slow, reduce counts to `10_000` and `5_000`, document the adjustment in the test or handoff.

- [ ] **Step 3: Run smoke test**

```powershell
& $mvn -Dtest=IdServiceSmokeTest test
```

Expected: `BUILD SUCCESS`.

## Task 3: Run Full Verification

**Files:**
- Inspect on failure: failing test file from Maven output

- [ ] **Step 1: Run full tests**

```powershell
& $mvn test
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 2: Check residual old ID code**

```powershell
rg -n "com\.tongji\.knowpost\.id\.SnowflakeIdGenerator|import com\.tongji\.knowpost\.id" src db
rg -n "SnowflakeIdGenerator|ThreadLocalRandom\.current\(\)\.nextLong|idGen" src\main\java\com\tongji\knowpost src\main\java\com\tongji\relation
```

Expected: no output, except references inside `src/main/java/com/tongji/common/id` are allowed only for the common Snowflake implementation.

- [ ] **Step 3: Check namespace coverage**

```powershell
rg -n "POST|COMMENT|PENDING_COMMENT|PUBLISH_ATTEMPT|OUTBOX_EVENT|RELATION|RECONCILIATION_TASK|ADMIN_OPERATION|AUDIT_LOG" src\main\java\com\tongji\common\id\IdNamespace.java
rg -n "IdNamespace\.(POST|OUTBOX_EVENT|RELATION|RECONCILIATION_TASK|ADMIN_OPERATION|AUDIT_LOG)" src\main\java src\test\java
```

Expected: all namespaces are defined in `IdNamespace.java`; existing business/test call sites cover currently implemented namespaces. Future modules may not have call sites yet; do not create them here.

- [ ] **Step 4: Check OpenSpec status**

```powershell
openspec status --change "add-leaf-id-service"
```

Expected: proposal/design/specs/tasks all complete artifacts.

If available, also run:

```powershell
openspec validate add-leaf-id-service --strict
```

If unsupported, record that and rely on status output.

## Task 4: Update OpenSpec Tasks

**Files:**
- Modify: `openspec/changes/add-leaf-id-service/tasks.md`

- [ ] **Step 1: Only proceed if full verification passed**

Do not edit checkboxes if Maven or residual searches failed.

- [ ] **Step 2: Mark completed tasks**

Mark a checkbox `[x]` only when evidence exists:

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
| 3.1 | existing Snowflake IDs route through `IdService`; future namespaces defined |
| 3.2 | Segment namespaces route through Segment |
| 3.3 | user ID remains auto-increment and user module does not call `IdService` |
| 4.1 | Snowflake concurrency test passes |
| 4.2 | Segment buffer switch test passes |
| 4.3 | smoke/throughput test passes |

- [ ] **Step 3: Validate markdown**

```powershell
Get-Content -Raw openspec\changes\add-leaf-id-service\tasks.md
```

Expected: Chinese readable, checkbox format intact.

## Task 5: Final Evidence Handoff

**Files:**
- Inspect: `target/surefire-reports`

- [ ] **Step 1: Rerun final commands**

```powershell
& $mvn test
rg -n "com\.tongji\.knowpost\.id\.SnowflakeIdGenerator|import com\.tongji\.knowpost\.id" src db
rg -n "SnowflakeIdGenerator|ThreadLocalRandom\.current\(\)\.nextLong|idGen" src\main\java\com\tongji\knowpost src\main\java\com\tongji\relation
openspec status --change "add-leaf-id-service"
openspec validate add-leaf-id-service --strict
```

Expected: Maven success, no residual old-package/random-ID search output, OpenSpec artifacts complete, strict validation success. If this OpenSpec CLI does not support `validate --strict`, record the unsupported command and the reason in the handoff.

- [ ] **Step 2: Return evidence**

Final handoff must include:

```markdown
Verification evidence:
- Maven: command and result.
- Smoke/throughput: test class, counts used, result.
- Residual search: command and result.
- OpenSpec: status command result, plus strict validate success or unsupported-command reason.
- Tasks: whether tasks.md was updated.
```

Do not archive the change in this plan.

