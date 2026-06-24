# Design

## Boundaries

Backend only. Entry points stay in reconciliation. Facts are read from promotion
and wallet tables. Output stays in `reconciliation_task`, `reconciliation_error_log`,
and existing wallet ledger movement APIs.

## Data Flow

```text
SETTLED promotion_auction_window
-> load active bids for the window
-> recompute expected auction settlement plan
-> compare allocation rows and wallet ledger businessRef rows
-> create repair task or dead task
-> executor runs repair idempotently
```

## Contracts

- Scan type: `promotion_settled_window`.
- Target type: `promotion_auction_window`.
- Task type: `promotion_wallet_effect_repair`.
- Wallet payload fields:
  - `effectType`
  - `ownerUserId`
  - `amount`
  - `reason`
  - `businessType`
  - `direction`
  - `counterpartyUserId`
  - `escrowId`
  - `availableDelta`
  - `heldDelta`
  - `escrowedDelta`
  - `businessRef`
- Dedupe scope remains task type + target type + target id + payload digest.
- Projection replay remains `promotion_decision_projection` with `promotion_decision` target and original Kafka decision payload.

## Rules

- Allocation rebuild handles only zero existing rows.
- Partial existing allocation is dirty data, not an automatic repair target.
- Wallet repair handles only expected `CAPTURE` and `RELEASE` effects.
- Existing ledger with same `businessRef` must match expected identity exactly.
- Missing Kafka decision payload is not patched in the settled scanner.
- Command record absence may warn elsewhere, but never blocks settled allocation or wallet repair when durable facts are complete.

## Compatibility

No new task table. Existing APIs remain. Operator rerun behavior is additive under reconciliation service rerun.

## Trade-Offs

- No second audit-task layer. Scanner creates final repair/dead tasks directly.
- Wallet effect payload uses JSON instead of a new table because one expected effect maps to one task.
- No automatic partial allocation repair. Half-written settled facts are surfaced as `dead`, so operators see the real data conflict.
