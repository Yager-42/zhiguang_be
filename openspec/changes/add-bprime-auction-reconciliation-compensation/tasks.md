## 1. Reconciliation Contracts

- [ ] 1.1 Add B' compensation task types: `promotion_chain_audit`, `promotion_redis_drift_mark`, `promotion_redis_hot_rebuild`, and `promotion_wallet_effect_repair`.
- [ ] 1.2 Add needed target types for promotion wallet effect and promotion Redis hot state, or document payload-only targeting if existing target types are sufficient.
- [ ] 1.3 Add scan type for recent promotion auction window audit.
- [ ] 1.4 Update `ReconciliationService.rerunTarget` so operators can rerun B' audit for promotion auction window and decision targets.

## 2. Chain Audit Scanner

- [ ] 2.1 Add mapper queries to list recently active or recently closed promotion auction windows by checkpoint.
- [ ] 2.2 Add scan method that creates `promotion_chain_audit` tasks for eligible windows.
- [ ] 2.3 Wire the scan into `ReconciliationScheduler` using the existing reconciliation executor.
- [ ] 2.4 Add tests proving scan checkpoint advances and duplicate audit tasks are deduped.

## 3. Chain Audit Reconciler

- [ ] 3.1 Implement `PromotionAuctionChainAuditReconciler` for `promotion_chain_audit`.
- [ ] 3.2 Detect decisions missing projection and create `promotion_decision_projection` tasks.
- [ ] 3.3 Detect closed windows missing slot allocation and create `promotion_allocation_rebuild` tasks.
- [ ] 3.4 Detect missing or mismatched B' wallet effects and create `promotion_wallet_effect_repair` tasks.
- [ ] 3.5 Detect Redis ranking, campaign state, or command replay drift and create Redis drift/rebuild tasks.
- [ ] 3.6 Keep audit read-only except for creating reconciliation tasks.

## 4. Redis Drift and Rebuild

- [ ] 4.1 Add Redis hot-state reader that compares ranking, campaign bid state, command replay state, and window state against durable facts.
- [ ] 4.2 Implement `PromotionRedisDriftMarkerReconciler` that records drift as failed/dead task or schedules rebuild when repairable.
- [ ] 4.3 Implement `PromotionRedisHotStateRebuildReconciler` that rebuilds Redis state from complete durable decision/projection facts.
- [ ] 4.4 Reject Redis rebuild when durable decision facts have gaps or ambiguous ordering.
- [ ] 4.5 Add tests proving Redis drift never overwrites Kafka/MySQL/wallet facts.

## 5. Wallet Effect Repair

- [ ] 5.1 Define wallet effect repair payload with ownerUserId, amount, effectType, businessRef, reason, businessType, decisionId, and auctionWindowId.
- [ ] 5.2 Implement `PromotionWalletEffectRepairReconciler` for HOLD, CAPTURE, RELEASE, and winner excess release.
- [ ] 5.3 Reuse `WalletService` idempotent businessRef operations for all repairs.
- [ ] 5.4 Fail the task when an existing businessRef has mismatched owner, amount, reason, business type, escrow id, or balance delta.
- [ ] 5.5 Add tests proving duplicate wallet repair does not duplicate ledger entries.

## 6. Authority Boundaries

- [ ] 6.1 Add guard tests proving Redis hot state is treated as repair target, not source of Kafka/MySQL/wallet truth.
- [ ] 6.2 Add tests proving WebSocket/fanout delivery is outside compensation authority and snapshot remains recovery path.
- [ ] 6.3 Add tests proving feed/search do not wait for compensation and continue reading projected allocation facts.

## 7. Verification

- [ ] 7.1 Add unit tests for chain audit task creation paths.
- [ ] 7.2 Add unit tests for Redis drift detection and hot-state rebuild.
- [ ] 7.3 Add unit tests for wallet effect repair success, duplicate retry, and mismatch failure.
- [ ] 7.4 Add integration-style tests for scheduler scan plus reconciliation task execution using mocks or local test DB.
- [ ] 7.5 Run `openspec validate add-bprime-auction-reconciliation-compensation --strict`.
- [ ] 7.6 Run focused backend tests for reconciliation, promotion B', wallet, and allocation rebuild paths.
