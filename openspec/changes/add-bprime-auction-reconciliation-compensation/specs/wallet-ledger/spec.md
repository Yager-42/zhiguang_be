## MODIFIED Requirements

### Requirement: Wallet SHALL remain durable authority for position auction settlement

The wallet domain SHALL provide the durable source of truth for B' holds, captures, and releases. Redis hot state MAY cache auction ranking and decision replay, but wallet compensation SHALL use wallet ledger and account facts as authority. B' compensation MAY rebuild Redis hot wallet-related cache from durable wallet facts, but MUST NOT treat Redis as the source of wallet truth.

#### Scenario: Settlement uses durable wallet facts
- **WHEN** a logged close decision is projected
- **THEN** system captures or releases held wallet balance with idempotent business references
- **AND** duplicate projection does not create new ledger movements

#### Scenario: Redis wallet cache is missing
- **WHEN** Redis wallet-related hot state is missing or stale
- **THEN** compensation rebuilds cache from wallet ledger and account facts where needed
- **AND** no ledger movement is created from Redis cache alone

## ADDED Requirements

### Requirement: Wallet compensation SHALL reject mismatched B' business references
The wallet domain SHALL reject B' compensation when an existing wallet businessRef has mismatched owner, amount, reason, business type, escrow id, or balance delta. Compensation SHALL surface the mismatch as reconciliation failure instead of hiding it with an opposite ledger movement.

#### Scenario: Mismatched hold businessRef exists
- **WHEN** B' wallet repair expects a hold ledger for a decision
- **AND** the same businessRef exists with different ledger identity
- **THEN** wallet repair fails with a reconciliation-visible error
- **AND** no new compensating movement is appended
