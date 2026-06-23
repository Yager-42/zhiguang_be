## MODIFIED Requirements

### Requirement: Reconciliation SHALL cover B' position auction command and decision chain

The system SHALL repair B' inconsistencies where Kafka decision payloads need replay into MySQL projection facts, where closed windows are missing slot allocation, where Redis hot state drifts from durable facts, and where wallet effects are missing or inconsistent. RocketMQ command records, Redis hot state, Kafka decisions, projection checkpoints, wallet ledger facts, and slot allocations SHALL be audited as one B' promotion auction chain. MySQL per-decision facts SHALL NOT be used because `promotion_auction_decision` is removed by the promotion realtime change.

#### Scenario: Decision is logged but projection is missing
- **WHEN** Kafka decision log contains an accepted position auction decision
- **AND** MySQL projection for that decision is missing
- **THEN** reconciliation schedules or executes projection replay
- **AND** wallet and bid facts remain idempotent

#### Scenario: Decision log has expired
- **WHEN** MySQL projection is missing
- **AND** required Kafka promotion decision facts are older than the 7-day retention window
- **THEN** reconciliation marks the task failed or dead for operator-visible follow-up
- **AND** does not use a MySQL per-decision backup table as an alternate replay source

#### Scenario: Slot allocation is missing after closed window
- **WHEN** a position auction window has a logged close decision
- **AND** no slot allocation exists for its winning positions
- **THEN** reconciliation rebuilds allocation from logged decisions and final ranking facts

#### Scenario: Redis hot state drifts from durable facts
- **WHEN** Redis ranking, campaign state, or command replay differs from durable B' decision/projection facts
- **THEN** reconciliation records drift and schedules Redis hot-state rebuild when durable facts are complete
- **AND** Redis is not used to overwrite Kafka decisions or wallet ledger facts

#### Scenario: Wallet effect is missing
- **WHEN** a B' decision requires hold, capture, or release wallet movement
- **AND** the matching wallet businessRef is missing
- **THEN** reconciliation schedules or executes wallet effect repair
- **AND** repeated repair remains idempotent

### Requirement: Reconciliation SHALL start with minimal projection repair

The system SHALL keep first-phase B' reconciliation compatible with existing projection replay and allocation rebuild, then extend it with Redis hot-state drift detection, Redis hot-state rebuild, wallet effect repair, and chain audit for promotion auctions. Deep repair SHALL remain scoped to B' promotion auction facts and SHALL NOT become a full-platform reconciliation rewrite.

#### Scenario: Redis ranking differs from projected decisions
- **WHEN** Redis ranking for an auction window differs from durable projection
- **THEN** reconciliation can mark drift and rebuild Redis hot state from durable facts
- **AND** wallet ledger repair is attempted only from logged B' wallet effects or settled projection facts

#### Scenario: Non-B' target is encountered
- **WHEN** a B' deep compensation task receives a target type outside Kafka promotion decision id, promotion auction window, wallet effect, or Redis hot state
- **THEN** system rejects the task
- **AND** does not run generic full-platform repair
