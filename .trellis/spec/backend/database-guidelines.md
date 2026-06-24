# Database Guidelines

> Database patterns and conventions for this project.

---

## Overview

<!--
Document your project's database conventions here.

Questions to answer:
- What ORM/query library do you use?
- How are migrations managed?
- What are the naming conventions for tables/columns?
- How do you handle transactions?
-->

(To be filled by the team)

---

## Query Patterns

<!-- How should queries be written? Batch operations? -->

(To be filled by the team)

---

## Migrations

<!-- How to create and run migrations -->

(To be filled by the team)

---

## Naming Conventions

<!-- Table names, column names, index names -->

(To be filled by the team)

---

## Common Mistakes

<!-- Database-related mistakes your team has made -->

## Scenario: Time-Cursor Reconciliation Scans

### 1. Scope / Trigger
- Trigger: backend reconciliation scans over rows ordered by time plus id.
- Use this when a scanner cannot use a monotonic numeric id alone, for example settled auction windows ordered by `settled_at, id`.

### 2. Signatures
- Checkpoint table column: `reconciliation_checkpoint.last_scanned_at DATETIME(3) NULL`.
- Mapper method:

```java
int updateTimeCheckpoint(String scanType, Instant lastScannedAt, Long lastScannedId);
```

- Source query shape:

```sql
WHERE status = 'SETTLED'
  AND settled_at IS NOT NULL
  AND settled_at >= #{lookbackStart}
  AND (
    #{lastSettledAt} IS NULL
    OR settled_at > #{lastSettledAt}
    OR (settled_at = #{lastSettledAt} AND id > #{lastWindowId})
  )
ORDER BY settled_at ASC, id ASC
LIMIT #{batchSize}
```

### 3. Contracts
- `last_scanned_at` and `last_scanned_id` are one cursor pair.
- On a non-empty batch, advance both fields to the last row in result order.
- On an empty batch, do not reset the time cursor. Resetting causes repeated lookback scans and hides bad scan design behind task dedupe.
- Required index for settled window scans: `(status, settled_at, id)`.
- Config key for bounded history: `promotion.bprime.settled-compensation-lookback-seconds`.

### 4. Validation & Error Matrix
- Source row has `settled_at IS NULL` -> exclude it from settled-window scan.
- Batch empty -> no checkpoint write.
- Batch non-empty -> write exact last row cursor.
- Cursor pair missing -> start from configured lookback.

### 5. Good/Base/Bad Cases
- Good: two rows share `settled_at`; the scanner uses `id > lastWindowId` to continue deterministically.
- Base: one batch finishes; checkpoint stores the last row's `settled_at` and `id`.
- Bad: empty batch resets checkpoint to null/0 and the next schedule rescans the lookback window.

### 6. Tests Required
- Assert mapper receives `(lastScannedAt, lastScannedId, lookbackStart, batchSize)`.
- Assert empty result does not call `updateTimeCheckpoint`.
- Assert non-empty result advances to the last row.
- Assert SQL/schema contains the supporting `(status, settled_at, id)` index.

### 7. Wrong vs Correct

#### Wrong

```java
if (windows.isEmpty()) {
    checkpointMapper.updateTimeCheckpoint(scanType, null, 0L);
    return;
}
```

#### Correct

```java
if (windows.isEmpty()) {
    return;
}
checkpointMapper.updateTimeCheckpoint(scanType, last.getSettledAt(), last.getId());
```
