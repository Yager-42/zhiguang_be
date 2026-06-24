## MODIFIED Requirements

### Requirement: Wallet SHALL remain durable authority for position auction settlement

The wallet domain SHALL provide the durable source of truth for B' holds, captures, and releases. Settled-window compensation SHALL use wallet ledger and account facts as authority for final settlement repair and MUST NOT treat Redis or WebSocket state as wallet truth.

#### Scenario: Settlement uses durable wallet facts
- **WHEN** a logged close decision is projected
- **THEN** system captures or releases held wallet balance with idempotent business references
- **AND** duplicate projection does not create new ledger movements

#### Scenario: Settled repair uses durable wallet facts
- **WHEN** a settled window repair recomputes expected capture or release effects
- **THEN** wallet compensation compares only against durable ledger identity
- **AND** no ledger movement is created from Redis or display state alone

## ADDED Requirements

### Requirement: Wallet compensation SHALL reject mismatched B' business references
The wallet domain SHALL reject B' compensation when an existing settled `CAPTURE` or `RELEASE` wallet businessRef has mismatched owner, amount, reason, business type, escrow id, or balance delta. Compensation SHALL surface the mismatch as reconciliation `dead` task instead of hiding it with an opposite ledger movement.

#### Scenario: Mismatched capture businessRef exists
- **WHEN** B' wallet repair expects a settled capture ledger
- **AND** the same businessRef exists with different ledger identity
- **THEN** wallet repair fails with a reconciliation-visible error
- **AND** no new compensating movement is appended

#### Scenario: Hold would be needed to trust a settled repair
- **WHEN** B' settled wallet repair cannot safely derive the final settled effect without trusting a missing or inconsistent historical hold fact
- **THEN** wallet repair fails with a reconciliation-visible error
- **AND** no settled-phase hold repair is appended
