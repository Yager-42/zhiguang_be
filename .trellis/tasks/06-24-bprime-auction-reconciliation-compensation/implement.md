# Implementation Plan

## 1. Recon Model

- [x] Add `promotion_settled_window` scan type.
- [x] Add `promotion_wallet_effect_repair` task type.
- [x] Reuse `promotion_auction_window` target type.

## 2. Settled Facts

- [x] Add mapper query for settled windows by `(settled_at, id)`.
- [x] Load bids, allocations, and wallet ledger by existing mapper/service boundaries.
- [x] Extract shared auction settlement planner and reuse it from settlement and reconciliation.

## 3. Scanner

- [x] Extend `ReconciliationScanService` to scan settled promotion windows.
- [x] Create allocation and wallet repair tasks directly.
- [x] Keep projection replay payload-driven; do not synthesize Kafka payload from a settled window.
- [x] Mark non-repairable allocation and wallet conflicts `dead`.
- [x] Advance checkpoint by `settled_at + window_id`.
- [x] Keep checkpoint unchanged when a time-cursor batch is empty.

## 4. Executor

- [x] Add wallet effect repair reconciler.
- [x] Validate expected ledger identity before deciding repaired/missing/dead.
- [x] Call existing wallet idempotent movement service.
- [x] Treat mismatch as non-retryable `dead`.

## 5. Operator Rerun

- [x] Add service rerun path for one promotion auction window.
- [x] Reuse scanner comparison logic.

## 6. Tests

- [x] Scanner test: settled only, checkpoint order, empty cursor behavior, task creation.
- [x] Allocation test: full missing rebuild, partial dirty dead.
- [x] Wallet test: missing effect repair and mismatch dead.
- [x] Executor test: wallet capture/release payload and non-retryable dead.
- [x] Service test: manual rerun delegates to settled-window compensation.

## Validation

- [x] Focused Maven tests for reconciliation, promotion, and wallet touched classes pass.
- [ ] Full `mvn clean test` is blocked by existing local integration environment/schema issues unrelated to this task.
