## MODIFIED Requirements

### Requirement: Wallet balances SHALL track available, hold, and escrow states

The wallet domain SHALL support three balance states: available balance, held balance, and escrowed balance. Internal state transitions such as hold, hold release, hold-to-escrow, escrow release, and refund SHALL preserve balance consistency across the affected wallet entries. Platform-funded grants/subsidies and platform forfeitures are external source/sink movements recorded with the platform ledger subject. Position auction bid acceptance SHALL reserve held balance for the submitted max bid, and auction projection SHALL capture or release held balance according to logged decisions.

#### Scenario: Promotion bid creates hold
- **WHEN** user places promotion bid that requires fund reservation
- **THEN** available balance decreases by reserved amount
- **AND** held balance increases by same amount

#### Scenario: Hold enters escrow
- **WHEN** business workflow locks reserved funds into escrow
- **THEN** held balance decreases by locked amount
- **AND** escrowed balance increases by same amount

#### Scenario: Position auction winner is captured
- **WHEN** B' position auction projection settles a winning bid
- **THEN** wallet captures the GSP clearing price from held balance to platform ledger subject
- **AND** wallet releases any held amount above the clearing price

#### Scenario: Position auction loser is released
- **WHEN** B' position auction projection marks bid as losing or rejected
- **THEN** wallet releases the held bid amount back to available balance

### Requirement: Wallet operations SHALL support idempotent business references

Wallet-affecting operations SHALL support idempotent execution keyed by business reference so that retries do not create duplicate balance movements. B' position auction wallet references SHALL be derived from command, decision, bid, and settlement identifiers so projection replay remains idempotent.

#### Scenario: Duplicate grant retry arrives
- **WHEN** same business operation is retried with same idempotency reference
- **THEN** system returns existing result
- **AND** system does not append duplicate ledger movement

#### Scenario: Idempotency reference is reused with different parameters
- **WHEN** a wallet operation reuses an existing business reference with a different owner, amount, reason, business type, escrow id, or balance delta
- **THEN** system rejects the operation
- **AND** system does not return the previous result as if it matched
- **AND** system does not append a new ledger movement

#### Scenario: Decision projection is replayed
- **WHEN** the same position auction decision is consumed again by projection
- **THEN** wallet operation with matching business reference returns existing ledger result
- **AND** no duplicate hold, capture, or release is created

## ADDED Requirements

### Requirement: Wallet SHALL remain durable authority for position auction settlement

The wallet domain SHALL provide the durable source of truth for B' holds, captures, and releases. Redis hot state MAY cache auction ranking and decision replay, but this change SHALL NOT implement a separate Redis hot-wallet rebuild path.

#### Scenario: Settlement uses durable wallet facts
- **WHEN** a logged close decision is projected
- **THEN** system captures or releases held wallet balance with idempotent business references
- **AND** duplicate projection does not create new ledger movements
