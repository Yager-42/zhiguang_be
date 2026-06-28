# Database Guidelines

> Database patterns and conventions for this project.

---

## Overview

The backend uses MyBatis mapper interfaces plus XML SQL files. Schema bootstrap for local development is centralized in `db/schema.sql`.

When a flow changes both primary business state and downstream propagation state, the local database remains the fact source. Cache invalidation, MQ delivery, and search sync are follow-up effects and must be driven from committed facts instead of replacing them.

---

## Query Patterns

Use mapper methods with explicit state predicates for workflow transitions.

Example:

```sql
UPDATE moderation_reports
SET status = #{status},
    reviewed_at = #{reviewedAt},
    updated_at = #{updatedAt}
WHERE id = #{id}
  AND status = 'pending'
```

This project prefers the database to enforce one-way transitions and idempotency instead of relying on in-memory checks alone.

---

## Migrations

Local schema changes are added to `db/schema.sql`. New tables and indexes should ship with the feature that depends on them, together with schema contract tests that pin table names, key columns, and critical indexes.

---

## Naming Conventions

Use snake_case for tables, columns, and indexes.

Index naming should describe purpose, not only fields:

- `uk_<table>_<meaning>` for uniqueness constraints
- `idx_<table>_<query-path>` for read or scan paths

Examples:

- `uk_moderation_report_reporter_target`
- `idx_moderation_report_retry`

---

## Common Mistakes

## Scenario: Idempotent Insert With Duplicate-Key Recovery

### 1. Scope / Trigger
- Trigger: request flows that use a unique key to collapse duplicate submissions, but still need to return the already-created record in the same request.

### 2. Signatures
- Service method: `ModerationReportResponse submitReport(long reporterUserId, ModerationReportRequest request)`
- Mapper methods:
  - `ModerationReport findByReporterAndTarget(long reporterUserId, String targetType, long targetId)`
  - `int insert(ModerationReport report)`
- DB constraint:
  - `UNIQUE KEY uk_moderation_report_reporter_target (reporter_user_id, target_type, target_id)`

### 3. Contracts
- First submission inserts a new row and returns the new `reportId`.
- Duplicate submission for the same reporter and target returns the existing row instead of creating a second pending record.
- The duplicate-key recovery query must still run inside the same service method after `DuplicateKeyException`.

### 4. Validation & Error Matrix
- no existing row + insert succeeds -> return new record
- existing row found before insert -> return existing record
- concurrent insert loses unique-key race -> catch `DuplicateKeyException`, re-read by unique key, return existing record
- duplicate-key raised but re-read still missing -> rethrow, treat as real inconsistency

### 5. Good/Base/Bad Cases
- Good: use `@Transactional(noRollbackFor = DuplicateKeyException.class)` when the recovery path queries after the failed insert.
- Base: check existing row before insert, but still keep the duplicate-key catch because check-then-insert races are real.
- Bad: use plain `@Transactional` and then catch `DuplicateKeyException` inside the method. Spring marks the transaction rollback-only, and the recovery query path becomes unreliable.

### 6. Tests Required
- Service test for duplicate submit returning the existing row.
- Contract test that the service method carries `noRollbackFor = DuplicateKeyException.class`.
- Schema contract test for the unique key that enforces idempotency.

### 7. Wrong vs Correct
#### Wrong

```java
@Transactional
public ModerationReportResponse submitReport(...) {
    try {
        reportMapper.insert(report);
    } catch (DuplicateKeyException ex) {
        return toResponse(reportMapper.findByReporterAndTarget(...));
    }
}
```

#### Correct

```java
@Transactional(noRollbackFor = DuplicateKeyException.class)
public ModerationReportResponse submitReport(...) {
    try {
        reportMapper.insert(report);
    } catch (DuplicateKeyException ex) {
        ModerationReport raced = reportMapper.findByReporterAndTarget(...);
        if (raced != null) {
            return toResponse(raced);
        }
        throw ex;
    }
}
```

## Scenario: Business State And Outbox Must Commit Together

### 1. Scope / Trigger
- Trigger: flows that change canonical business state and also emit an outbox event for downstream consumers such as search, recommendation, or async processing.

### 2. Signatures
- Service method: `void applyApprovedAction(ModerationReport report)`
- Mapper calls:
  - `knowPostMapper.rejectPublishedForModeration(postId)`
  - `outboxMapper.insert(id, "knowpost", postId, "KnowPostModerationRejected", payload)`

### 3. Contracts
- If the primary state change succeeds, the matching outbox fact must exist in the same commit.
- If outbox insert fails, the primary state change must roll back.
- Cache invalidation is best-effort and should run after commit, not inside the transaction boundary as a reason to roll back facts.

### 4. Validation & Error Matrix
- state update succeeds + outbox insert succeeds -> commit transaction, then invalidate cache
- state update succeeds + outbox insert fails -> roll back transaction
- state update affects zero rows because target already left expected state -> re-read and confirm terminal state before deciding whether to continue

### 5. Good/Base/Bad Cases
- Good: keep mapper update and outbox insert in one transactional service method.
- Base: register cache invalidation in `afterCommit`.
- Bad: update business row first, swallow outbox failure, and still report success. That creates local state that downstream systems never learn about.

### 6. Tests Required
- Service test proving outbox insert failure aborts the action.
- Service test proving cache invalidation failure does not roll back committed DB work.
- Mapper or service test proving zero-row updates re-check current state.

### 7. Wrong vs Correct
#### Wrong

```java
knowPostMapper.rejectPublishedForModeration(postId);
try {
    outboxMapper.insert(...);
} catch (Exception ignored) {
    log.warn("outbox failed");
}
```

#### Correct

```java
@Transactional
public void applyApprovedAction(ModerationReport report) {
    rejectPost(postId);
    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override
        public void afterCommit() {
            invalidatePostCachesBestEffort(postId);
        }
    });
}
```
