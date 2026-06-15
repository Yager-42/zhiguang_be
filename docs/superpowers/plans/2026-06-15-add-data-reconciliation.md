# Data Reconciliation Implementation Plan

> **For agentic workers:** Use `superpowers:subagent-driven-development` only when the user/environment has authorized subagents. Otherwise execute this single plan in the current session with `superpowers:executing-plans`. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a DB-backed reconciliation framework for fact-to-derived inconsistencies with polling, Redis locks, retry/backoff, dead tasks, checkpoint scans, stuck running recovery, and manual rerun APIs.

**Architecture:** `reconciliation_task` is the only task fact source. Scheduled workers poll pending tasks, claim with CAS plus Redis lock, dispatch to typed reconcilers, and update state. Scans create missing repair tasks; concrete repairers are added only for fact sources that already exist.

**Tech Stack:** Java 21, Spring Boot 3.2.4, MyBatis, MySQL, Redis/Redisson, Spring Scheduling, `IdService`, `TextStorageService`, ES/RAG services, JUnit 5, Maven.

---

## Required Context

Read before editing:

- `openspec/11pdf-integration-matrix.md`
- `openspec/changes/add-data-reconciliation/proposal.md`
- `openspec/changes/add-data-reconciliation/design.md`
- `openspec/changes/add-data-reconciliation/specs/data-reconciliation/spec.md`
- `openspec/changes/add-data-reconciliation/tasks.md`
- `src/main/java/com/tongji/search/index/SearchIndexService.java`
- `src/main/java/com/tongji/llm/rag/RagIndexService.java`
- `src/main/java/com/tongji/storage/text/TextStorageService.java`

Prerequisites: `add-cassandra-text-storage`, `add-comment-system`, and `add-recommendation-and-follow-feed` for full repairer coverage. Framework work can start earlier only if repairers for missing modules remain disabled.
ID imports must use `com.tongji.common.id.IdService` and `com.tongji.common.id.IdNamespace`.

## Command Setup

Run Maven commands from the repo root. The current workspace root is `/Volumes/lexar/revive/zhiguang_be`; on other machines, use that machine's repo root.

```bash
mvn -version
```

Windows PowerShell optional equivalent: `mvn.cmd -version` if `mvn.cmd` is on `PATH`.

## Files

- Modify: `db/schema.sql`
- Create: `src/main/java/com/tongji/reconciliation/*`
- Create: `src/main/java/com/tongji/reconciliation/model/*`
- Create: `src/main/java/com/tongji/reconciliation/mapper/*`
- Create: `src/main/resources/mapper/Reconciliation*.xml`
- Create: `src/main/java/com/tongji/reconciliation/service/*`
- Create: `src/main/java/com/tongji/reconciliation/executor/*`
- Create: `src/main/java/com/tongji/reconciliation/scan/*`
- Create: `src/main/java/com/tongji/reconciliation/api/*`
- Modify: publish/recommendation consumers to create reconciliation tasks on derived failure
- Test: `src/test/java/com/tongji/reconciliation/*`

## Task 1: Schema, Constants, Models, Mappers

- [ ] Add `reconciliation_task`, `reconciliation_checkpoint`, and `reconciliation_error_log` to `db/schema.sql`.
- [ ] Define task types: `es_index`, `rag_index`, `feed_cache_invalidate`, `gorse_item_upsert`, `gorse_feedback`, `cassandra_text`, `comment_count`, `follow_inbox`.
- [ ] Define target types: `post`, `comment`, `user`.
- [ ] Define scan types: `post_es`, `post_rag`, `post_gorse`, `post_cassandra`, `comment_cassandra`, `running_timeout`.
- [ ] Create models and MyBatis mappers/XML for task, checkpoint, and error log.
- [ ] Mapper must support `pollPending`, `markRunning`, `markSucceeded`, `markPendingRetry`, `markDead`, `resetDeadToPending`, `findStuckRunning`, `resetRunningToPending`, `existsActiveTask`, and filtered query.
- [ ] Define mapper state semantics explicitly: `markRunning` is `pending -> running` CAS, `existsActiveTask` only treats `pending` and `running` as active, `resetDeadToPending` clears retry state for manual retry, and `findStuckRunning` is based on stale `updated_at`.
- [ ] Run `mvn -DskipTests compile`.

## Task 2: Reconciliation Service

**Files:**
- Create: `src/main/java/com/tongji/reconciliation/service/ReconciliationService.java`
- Create: `src/main/java/com/tongji/reconciliation/service/impl/ReconciliationServiceImpl.java`
- Test: `src/test/java/com/tongji/reconciliation/service/ReconciliationServiceTest.java`

- [ ] Use `IdService.nextId(IdNamespace.RECONCILIATION_TASK)` from `com.tongji.common.id` for task IDs.
- [ ] Implement `createTask`, `createTaskIfAbsent`, `retryTask`, `rerunTarget`, `findById`, and filtered query.
- [ ] `createTaskIfAbsent` returns existing/no-op result if pending/running task already exists.
- [ ] `dead -> pending` manual retry resets retry count and `next_execute_at`.
- [ ] Add tests for duplicate prevention, manual retry, and target rerun.
- [ ] Run `mvn -Dtest=ReconciliationServiceTest test`.

## Task 3: Executor and Retry State Machine

**Files:**
- Create: `src/main/java/com/tongji/reconciliation/executor/Reconciler.java`
- Create: `src/main/java/com/tongji/reconciliation/executor/ReconciliationTaskExecutor.java`
- Create: `src/main/java/com/tongji/reconciliation/executor/ReconciliationScheduler.java`
- Test: `src/test/java/com/tongji/reconciliation/executor/ReconciliationTaskExecutorTest.java`

- [ ] Poll pending tasks whose `next_execute_at <= NOW()`.
- [ ] Acquire Redis lock `recon:lock:{taskId}` before execution.
- [ ] CAS `pending -> running` before invoking the reconciler.
- [ ] On success, mark `succeeded`.
- [ ] On failure with retry count below 5, schedule exponential backoff as first failure waits 1 minute, then 2, 4, 8, and 16 minutes. Implement as `delayMinutes = 2^oldRetryCount` before increment, or equivalently `2^(newRetryCount - 1)` after increment.
- [ ] On failure at retry limit, mark `dead` and write `reconciliation_error_log`.
- [ ] Reset stuck `running` tasks older than 10 minutes on a scheduled scan.
- [ ] Add tests for success, retry, dead, lock skip, lost CAS, and exact retry delays `1, 2, 4, 8, 16` minutes across consecutive failures.
- [ ] Run `mvn -Dtest=ReconciliationTaskExecutorTest test`.

## Task 4: Concrete Reconcilers

**Files:**
- Create: `src/main/java/com/tongji/reconciliation/executor/EsIndexReconciler.java`
- Create: `src/main/java/com/tongji/reconciliation/executor/RagIndexReconciler.java`
- Create: `src/main/java/com/tongji/reconciliation/executor/CassandraTextReconciler.java`
- Create: optional Gorse/feed/comment count reconcilers only when their modules are already present
- Test: `src/test/java/com/tongji/reconciliation/executor/ReconcilerTest.java`

- [ ] `es_index` for post calls `SearchIndexService.upsertKnowPost(postId)`.
- [ ] `rag_index` for post calls `RagIndexService.ensureIndexed(postId)`.
- [ ] `cassandra_text` for post restores from MinIO fallback only when source exists.
- [ ] `cassandra_text` for comment records dead/operator-visible error if no source event or body exists.
- [ ] `gorse_item_upsert`, `gorse_feedback`, and `follow_inbox` can be implemented after recommendation/feed classes exist; otherwise leave them unregistered and covered by task dead/error behavior.
- [ ] Add smoke tests for at least ES, RAG, and Cassandra text.
- [ ] Run `mvn -Dtest=ReconcilerTest test`.

## Task 5: Checkpointed Scans

**Files:**
- Create: `src/main/java/com/tongji/reconciliation/scan/ReconciliationScanService.java`
- Modify: `src/main/java/com/tongji/knowpost/mapper/KnowPostMapper.java`
- Modify: `src/main/resources/mapper/KnowPostMapper.xml`
- Modify: `src/main/java/com/tongji/comment/mapper/CommentMapper.java` if comment module exists

- [ ] Add cursor scan methods for published post IDs.
- [ ] Add optional cursor scan methods for comment IDs.
- [ ] Implement `post_es`, `post_rag`, `post_cassandra`, and `comment_cassandra` scans.
- [ ] Use `reconciliation_checkpoint.last_scanned_id`.
- [ ] Scan in batches; update checkpoint to last scanned ID.
- [ ] Reset checkpoint to `0` after completing a full pass.
- [ ] Stuck `publishing` posts must respect publish attempt semantics: fail/surface retry work, never directly mark published.
- [ ] Add checkpoint tests.
- [ ] Run `mvn -Dtest=*ReconciliationScan* test`.

## Task 6: Failure Hooks and API

**Files:**
- Modify: publish derived consumers
- Modify: recommendation consumers
- Create: `src/main/java/com/tongji/reconciliation/api/ReconciliationController.java`
- Create: `src/main/java/com/tongji/reconciliation/api/ReconciliationTaskResponse.java`

- [ ] On ES/RAG/Gorse/feed derived failure, call `createTaskIfAbsent`.
- [ ] Do not create reconciliation tasks for critical publish fact failures; those belong to publish attempt status.
- [ ] Add `GET /api/v1/reconciliation/tasks`.
- [ ] Add `GET /api/v1/reconciliation/tasks/{id}`.
- [ ] Add `POST /api/v1/reconciliation/tasks/{id}/retry`.
- [ ] Add `POST /api/v1/reconciliation/targets/{type}/{id}/rerun`.
- [ ] Add API tests for listing, detail, retry, and rerun.

## Task 7: Observability, Verification, OpenSpec Closure

- [ ] Record execution duration, retry count, and failure reason in task rows and logs.
- [ ] Run state machine tests.
- [ ] Run checkpoint tests.
- [ ] Run at least three domain smoke tests.
- [ ] Run `mvn -Dtest="*Reconciliation*" test`.
- [ ] Run `mvn test`.
- [ ] Run `openspec status --change "add-data-reconciliation" --json`.
- [ ] Run `openspec validate add-data-reconciliation --strict` if supported.
- [ ] Mark completed checkboxes in `openspec/changes/add-data-reconciliation/tasks.md` only after evidence exists.
