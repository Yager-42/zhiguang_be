# wallet-ledger Specification

## Purpose
TBD - created by archiving change add-wallet-and-escrow. Update Purpose after archive.
## Requirements
### Requirement: Wallet ownership SHALL bind to zhiguang user identity

The system SHALL bind each wallet to an existing zhiguang `user` identity. It MUST NOT introduce a second commercial identity or a separate commercial authentication subject for wallet ownership.

#### Scenario: User wallet is created
- **WHEN** system initializes wallet state for a zhiguang user
- **THEN** wallet owner is existing `user.id`
- **AND** no separate business `account` identity is created

### Requirement: Platform ledger subject SHALL exist as a system accounting counterparty

The system SHALL maintain a platform ledger subject for accounting operations such as grants, subsidies, promotion revenue intake, and forfeitures. This subject SHALL be internal to ledger accounting and SHALL NOT behave as a normal content-community user.

#### Scenario: Platform issues registration grant
- **WHEN** system grants virtual currency to a newly registered user
- **THEN** ledger records platform ledger subject as grant counterparty
- **AND** user wallet balance increases by granted amount

### Requirement: Wallet balances SHALL track available, hold, and escrow states

The wallet domain SHALL support three balance states: available balance, held balance, and escrowed balance. Internal state transitions such as hold, hold release, hold-to-escrow, escrow release, and refund SHALL preserve balance consistency across the affected wallet entries. Platform-funded grants/subsidies and platform forfeitures are external source/sink movements recorded with the platform ledger subject.

#### Scenario: Promotion bid creates hold
- **WHEN** user places promotion bid that requires fund reservation
- **THEN** available balance decreases by reserved amount
- **AND** held balance increases by same amount

#### Scenario: Hold enters escrow
- **WHEN** business workflow locks reserved funds into escrow
- **THEN** held balance decreases by locked amount
- **AND** escrowed balance increases by same amount

### Requirement: Wallet ledger SHALL be append-only

The system SHALL persist an append-only wallet ledger for all balance-changing actions, including grants, subsidies, holds, hold releases, escrow transfers, escrow releases, refunds, and forfeitures.

#### Scenario: Balance-changing action is recorded
- **WHEN** any wallet-affecting operation succeeds
- **THEN** system appends ledger entry with operation type, amount, parties, business reference, and resulting balances
- **AND** system does not mutate historical ledger entries

### Requirement: Wallet operations SHALL support idempotent business references

Wallet-affecting operations SHALL support idempotent execution keyed by business reference so that retries do not create duplicate balance movements.

#### Scenario: Duplicate grant retry arrives
- **WHEN** same business operation is retried with same idempotency reference
- **THEN** system returns existing result
- **AND** system does not append duplicate ledger movement

#### Scenario: Idempotency reference is reused with different parameters
- **WHEN** a wallet operation reuses an existing business reference with a different owner, amount, reason, business type, escrow id, or balance delta
- **THEN** system rejects the operation
- **AND** system does not return the previous result as if it matched
- **AND** system does not append a new ledger movement

### Requirement: Wallet SHALL support platform-funded grants and subsidies

The system SHALL support platform-funded registration grants and platform-funded subsidies as first-class ledger operations.

#### Scenario: Subsidy is issued for qualifying workflow
- **WHEN** business module requests configured platform subsidy for qualifying user action
- **THEN** system records subsidy from platform ledger subject to target user wallet
- **AND** user available balance increases by subsidy amount

### Requirement: Wallet SHALL reject unsupported cash operations

The system SHALL NOT expose recharge, withdrawal, or real-money settlement operations in this phase.

#### Scenario: Recharge is requested
- **WHEN** client attempts unsupported recharge or withdrawal action
- **THEN** system rejects request
- **AND** no wallet ledger movement is created

