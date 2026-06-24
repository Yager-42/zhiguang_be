# BPrime Auction Reconciliation Compensation

## Goal

Implement settled-window reconciliation compensation for B' promotion auctions.
The repair source is durable settled facts, not Redis hot state, WebSocket delivery,
or RocketMQ command history.

## Confirmed Facts

- Reconciliation already has `reconciliation_task`, checkpoints, error logs, scheduler, scanner, and reconcilers.
- Promotion already has Kafka decision projection replay, `promotion_allocation_rebuild`, auction windows, bids, allocations, and wallet ledger.
- Durable fact order is: Kafka decision payload for projection replay, MySQL settled window/bid facts for allocation and wallet comparison, wallet ledger/account for money effects.
- `promotion_auction_decision` is not a durable compensation source.
- The settled scanner cannot synthesize a Kafka decision payload from a window id. Existing projection replay remains payload-driven.

## Requirements

- Scan only `promotion_auction_window.status = SETTLED`.
- Advance promotion settled scan by `(settled_at, id)`.
- Recompute expected winners, losers, clearing price, and wallet effects from settled window and active bids.
- Create `promotion_allocation_rebuild` only when expected allocation rows are fully missing.
- Mark partial allocation, wrong slot, wrong winner, or wrong clearing price as `dead`.
- Create one `promotion_wallet_effect_repair` task per missing expected `CAPTURE` or `RELEASE`.
- Do not repair `HOLD` in settled-window compensation.
- Treat existing wallet ledger with same `businessRef` and matching identity as already repaired.
- Mark existing wallet ledger with same `businessRef` but different owner, amount, reason, business type, direction, counterparty, escrow id, or deltas as `dead`.
- Let an operator rerun one settled promotion auction window through the same comparison rules.
- Do not block normal bid, fanout, feed/search, snapshot, or WebSocket flows on this deep compensation.

## Out Of Scope

- Full reconciliation system rewrite.
- `OPEN` window scan.
- Redis drift, Redis rebuild, WebSocket delivery repair.
- Redis to Kafka/MySQL/wallet backfill.
- RocketMQ historical command repair.
- Settled-stage `HOLD` repair.
- Kafka decision replay without a stored decision payload.

## Acceptance Criteria

- [x] Add settled promotion scan type and `promotion_wallet_effect_repair`.
- [x] Scanner reads only settled windows and advances by `settled_at, id`.
- [x] Scanner creates allocation rebuild tasks for fully missing allocations.
- [x] Scanner creates wallet repair tasks for missing expected `CAPTURE` and `RELEASE`.
- [x] Scanner marks allocation and wallet identity conflicts as `dead`.
- [x] Wallet repair reuses existing wallet idempotent movement service.
- [x] Manual rerun for one settled promotion auction window returns the same repair/dead result shape.
- [x] Focused tests cover scanner cursor behavior, allocation full-missing/partial-dead, wallet missing/conflict, non-retryable dead, and manual rerun delegation.
