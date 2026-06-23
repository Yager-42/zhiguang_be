## ADDED Requirements

### Requirement: B' compensation SHALL audit auction chain consistency
The system SHALL audit B' promotion auction chain consistency across command records, Kafka/MySQL decision facts, projection checkpoints, wallet ledger facts, Redis hot state, and slot allocations for one auction window.

#### Scenario: Window chain audit finds missing projection
- **WHEN** an auction window has accepted Kafka or MySQL decision facts
- **AND** projection checkpoint or projected bid facts are missing
- **THEN** system schedules promotion decision projection repair tasks
- **AND** each repair task remains idempotent by decision id

#### Scenario: Window chain audit finds missing allocation
- **WHEN** an auction window has close or settled decision facts
- **AND** no slot allocation exists for winning positions
- **THEN** system schedules promotion allocation rebuild repair
- **AND** allocation rebuild uses durable projected bid facts

### Requirement: B' compensation SHALL detect Redis hot-state drift
The system SHALL detect drift between Redis promotion auction hot state and durable B' decision/projection facts. Redis SHALL be treated as a repair target, not the final authority.

#### Scenario: Redis ranking differs from durable ranking
- **WHEN** Redis ranking for an auction window differs from durable accepted decisions or projected bid facts
- **THEN** system records a drift issue or repair task for that auction window
- **AND** does not change Kafka decision facts or wallet ledger facts based on Redis alone

#### Scenario: Redis command replay is missing
- **WHEN** durable command or decision facts exist
- **AND** Redis command replay state is missing for an active or recently closed window
- **THEN** system can rebuild Redis replay state from durable facts

### Requirement: B' compensation SHALL rebuild Redis hot state from durable facts
The system SHALL rebuild B' promotion auction Redis hot state from Kafka/MySQL decision facts and projection facts. Rebuild SHALL restore window state, campaign bid state, command replay state, and ranking state needed by snapshot and active auction display.

#### Scenario: Active window hot ranking is rebuilt
- **WHEN** an active auction window has durable accepted decision facts
- **AND** Redis hot ranking is missing or stale
- **THEN** system rebuilds Redis ranking and campaign state from durable facts
- **AND** snapshot can return rebuilt ranking

#### Scenario: Rebuild skips unknown durable gap
- **WHEN** durable facts have a decision version gap
- **THEN** system does not rebuild Redis from partial facts
- **AND** records reconciliation error for operator-visible follow-up

### Requirement: B' compensation SHALL repair wallet effects idempotently
The system SHALL verify and repair B' wallet effects for accepted, rejected, losing, and winning promotion auction decisions. Repairs SHALL use wallet businessRef idempotency and SHALL reject mismatched existing ledger facts.

#### Scenario: Missing hold is repaired
- **WHEN** an accepted B' bid decision requires a hold
- **AND** wallet ledger has no matching hold businessRef
- **THEN** system executes the hold repair with the original owner, amount, reason, business type, and businessRef
- **AND** repeated repair does not duplicate ledger movement

#### Scenario: Missing winner capture is repaired
- **WHEN** a closed auction window marks a bid as winning with a clearing price
- **AND** wallet ledger has no matching capture businessRef
- **THEN** system captures held balance to platform ledger subject
- **AND** releases any excess hold through its idempotent businessRef

#### Scenario: Existing wallet ledger conflicts
- **WHEN** a required B' wallet businessRef already exists with different owner, amount, reason, business type, or balance delta
- **THEN** system marks the repair task as failed or dead
- **AND** does not append a compensating ledger movement that hides the mismatch

### Requirement: B' compensation SHALL remain eventual and non-blocking
The system SHALL run B' compensation as eventual reconciliation work. Normal bid submission, decision fanout, feed/search rendering, and snapshot reads SHALL NOT synchronously wait for deep compensation scans.

#### Scenario: Drift exists during user flow
- **WHEN** Redis or wallet drift is detected after a user bid flow has completed
- **THEN** system schedules or executes reconciliation asynchronously
- **AND** does not block unrelated feed/search requests
