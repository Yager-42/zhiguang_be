# Leaf ID Service Plan Index

> **For agentic workers:** REQUIRED: Use `superpowers:subagent-driven-development` if subagents are available. Run one plan per fresh subagent and do not mix write scopes.

**Goal:** Split `openspec/changes/add-leaf-id-service` into four bounded implementation plans that can be executed without context compaction.

**Architecture:** Implement the ID service in layers: public API and Snowflake first, Segment second, existing call-site migration third, final verification and OpenSpec closure last. Each plan has a disjoint primary write scope and includes the context a fresh subagent needs.

**Tech Stack:** Java 21, Spring Boot 3.2.4, MyBatis, MySQL schema SQL, JUnit 5, Maven, OpenSpec.

---

## Execution Order

1. [Plan 1 - ID API and Snowflake](./2026-06-15-leaf-id-service-plan-1-snowflake.md)
2. [Plan 2 - Segment Buffer](./2026-06-15-leaf-id-service-plan-2-segment.md)
3. [Plan 3 - Existing Integration Migration](./2026-06-15-leaf-id-service-plan-3-integration.md)
4. [Plan 4 - Final Verification and OpenSpec Closure](./2026-06-15-leaf-id-service-plan-4-verification.md)

## Subagent Assignment

| Plan | Subagent Scope | Must Not Touch |
|---|---|---|
| 1 | `com.tongji.common.id` API, Snowflake, config, Snowflake tests, OpenSpec namespace correction | Segment implementation, business service migration |
| 2 | `leaf_alloc`, Segment mapper/generator/buffer, Segment tests | `KnowPostServiceImpl`, `RelationServiceImpl` |
| 3 | Existing service migration to `IdService`, deletion of old Snowflake class | Segment internals, smoke/throughput closure |
| 4 | Smoke/throughput tests, verification commands, OpenSpec task checkbox closure | Core architecture redesign |

## Shared Context

- Repo root: `E:\idk\zhiguang_be`
- OpenSpec change: `openspec/changes/add-leaf-id-service`
- Maven command setup:

```powershell
$mvn = Join-Path $env:USERPROFILE 'Desktop\文档\.codex\tools\apache-maven-3.9.6\bin\mvn.cmd'
Test-Path $mvn
& $mvn test
```

- Current direct ID generation references before implementation:

```powershell
rg -n "SnowflakeIdGenerator|ThreadLocalRandom\.current\(\)\.nextLong|idGen" src\main\java
```

- Git may report `detected dubious ownership` in this workspace. If a plan asks for `git diff --name-only` and Git refuses to run, use this read-only fallback instead:

```powershell
Get-ChildItem -Recurse src\main\java\com\tongji\common\id,src\test\java\com\tongji\common\id,db,src\main\resources |
  Select-Object FullName,LastWriteTime
```

- Important design decision: add `RELATION(IdMode.SNOWFLAKE)` because `RelationServiceImpl` currently generates `following.id`. Do not reuse `OUTBOX_EVENT` for relation row IDs.

## Completion Rule

Only Plan 4 may mark `openspec/changes/add-leaf-id-service/tasks.md` checkboxes complete, and only after verification evidence is available.

