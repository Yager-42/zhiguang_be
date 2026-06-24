# Quality Guidelines

> Code quality standards for backend development.

---

## Overview

<!--
Document your project's quality standards here.

Questions to answer:
- What patterns are forbidden?
- What linting rules do you enforce?
- What are your testing requirements?
- What code review standards apply?
-->

(To be filled by the team)

---

## Forbidden Patterns

<!-- Patterns that should never be used and why -->

(To be filled by the team)

---

## Required Patterns

<!-- Patterns that must always be used -->

(To be filled by the team)

---

## Testing Requirements

<!-- What level of testing is expected -->

(To be filled by the team)

---

## Code Review Checklist

<!-- What reviewers should check -->

## Scenario: Reconciliation Repair Tasks

### 1. Scope / Trigger
- Trigger: adding reconciliation scanners or reconcilers that compare durable facts and enqueue repair tasks.
- Applies to promotion auction allocation and wallet effect compensation.

### 2. Signatures
- Scan type constants live in `ReconciliationScanType.ALL`.
- Task type constants live in `ReconciliationTaskType.ALL`.
- Repair tasks use `ReconciliationService.createTaskIfAbsent(...)`.
- Non-repairable conflicts use `ReconciliationService.createDeadTaskIfAbsent(...)`.
- Payload-specific dedupe must include the task payload digest.

### 3. Contracts
- Scanner compares durable source facts to derived expected state.
- Settled promotion auction compensation must read `WON` and `LOST` bid facts, not `ACTIVE` bid intake rows.
- Scanner creates the final repair task directly; do not add a second audit-task layer.
- Dirty partial facts are not auto-repaired. Mark them `dead` with the exact mismatch.
- Wallet repair handles missing expected `CAPTURE` and `RELEASE` effects only.
- Wallet repair must use existing wallet idempotent movement APIs, never direct ledger inserts.
- Projection replay requires the original Kafka decision payload. A settled window id is not enough.

### 4. Validation & Error Matrix
- Expected allocation rows missing and actual rows empty -> create allocation rebuild task.
- Expected allocation conflicts with existing rows -> create dead task.
- Expected wallet effect missing -> create wallet repair task with payload.
- Existing wallet ledger identity matches expected payload -> no task.
- Existing wallet ledger identity differs -> create dead task.
- Wallet idempotent API reports duplicate business ref during repair -> non-retryable dead.

### 5. Good/Base/Bad Cases
- Good: shared planner computes expected winners and wallet effects, so settlement and reconciliation cannot drift.
- Base: one missing wallet release creates one `promotion_wallet_effect_repair` task.
- Bad: scanner sees one correct allocation row and one missing row, then silently inserts only the missing row.
- Bad: settled-window scanner calls `listActiveBidsByWindowId`; settled bids have already been changed to `WON` or `LOST`, so the plan becomes empty.

### 6. Tests Required
- Scanner tests for full-missing allocation, partial allocation conflict, missing wallet effect, and wallet identity conflict.
- Contract tests for separate active-bid and settled-bid mapper methods.
- Executor tests for `CAPTURE`, `RELEASE`, and non-retryable duplicate business ref.
- Contract tests for scan/task constants, schema columns, mapper signatures, and scheduled dispatch.
- Rerun tests proving manual window rerun delegates to the same comparison path.

### 7. Wrong vs Correct

#### Wrong

```java
List<PromotionBid> bids = bidMapper.listActiveBidsByWindowId(windowId, startAt, endAt);
```

#### Correct

```java
List<PromotionBid> bids = bidMapper.listSettledBidsByWindowId(windowId, startAt, endAt);
```

#### Also Correct

```java
if (actualAllocations.isEmpty()) {
    reconciliationService.createTaskIfAbsent(ALLOCATION_REBUILD, targetType, windowId);
} else if (!matchesExpected(actualAllocations, expectedAllocations)) {
    reconciliationService.createDeadTaskIfAbsent(ALLOCATION_REBUILD, targetType, windowId, mismatch);
}
```
