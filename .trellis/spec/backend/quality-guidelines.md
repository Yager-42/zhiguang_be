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

## Scenario: Capability Retirement Across Layers

### 1. Scope / Trigger
- Trigger: a backend capability is intentionally removed from the product surface.
- Applies when deleting an API, async flow, search/index path, recommendation path, reconciliation family, or third-party integration.

### 2. Signatures
- Remove public entrypoints first: controller routes, DTOs, service interfaces, and service implementations that only exist for the retired capability.
- Remove derived execution signatures tied only to the retired capability:
  - event consumers
  - scheduled jobs
  - reconciliation scan types
  - reconciliation task types
  - reconcilers / repair executors
- Remove dependency signatures tied only to the retired capability:
  - `pom.xml` dependencies and BOM entries
  - `application.yml` keys
  - `@ConfigurationProperties` fields

### 3. Contracts
- Product removal means the endpoint or task is gone, not soft-disabled.
- No compatibility shim, no no-op handler, and no dead config key unless a separate requirement explicitly keeps it.
- Current user-facing docs must stop advertising the removed behavior in the same task.
- Tests that only prove the retired behavior must be deleted.
- Tests for remaining neighboring behavior must be updated so they no longer mention or expect the retired path.

### 4. Validation & Error Matrix
- Public route still registered after retirement -> fail the task.
- Capability-specific dependency or config key still present -> fail the task.
- Async consumer / scheduler / reconciliation constant still references the retired capability -> fail the task.
- Current API docs still describe the removed capability as available -> fail the task.
- Historical or archive docs still mention the old capability -> allowed unless the task explicitly includes history cleanup.

### 5. Good/Base/Bad Cases
- Good: delete endpoint, service wiring, async consumers, reconciliation types, config, dependency, tests, and current docs in one change.
- Base: keep shared storage, search, or persistence building blocks that still serve non-retired features.
- Bad: replace the removed endpoint with a no-op `200 OK`.
- Bad: delete controller code but leave scheduler, reconciliation task types, or config keys behind.
- Bad: delete runtime code but leave current API docs claiming the feature exists.

### 6. Tests Required
- Search the active runtime and current docs for the retired identifiers and integration names.
- Run targeted tests for touched neighboring modules to prove remaining behavior still works.
- Add or update contract tests when retirement changes enums, scan families, scheduler wiring, or service branching.

### 7. Wrong vs Correct

#### Wrong

```java
@PostMapping("/description/suggest")
public DescriptionSuggestResponse suggest(...) {
    return new DescriptionSuggestResponse("");
}
```

#### Correct

```java
// Delete the controller, DTOs, service wiring, config, and tests for the retired capability.
```

#### Also Correct

```java
// Keep shared Elasticsearch or storage wiring only when another live feature still owns it.
```

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
