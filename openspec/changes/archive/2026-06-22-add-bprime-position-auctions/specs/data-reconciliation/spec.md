## ADDED Requirements

### Requirement: Reconciliation SHALL cover B' position auction command and decision chain

The system SHALL repair first-phase B' inconsistencies where Kafka decision payloads or MySQL decision facts need replay into MySQL projection facts and where closed windows are missing slot allocation. Full RocketMQ/Redis/wallet drift repair is outside this change.

#### Scenario: Decision is logged but projection is missing
- **WHEN** Kafka decision log contains an accepted position auction decision
- **AND** MySQL projection for that decision is missing
- **THEN** reconciliation schedules or executes projection replay
- **AND** wallet and bid facts remain idempotent

#### Scenario: Slot allocation is missing after closed window
- **WHEN** a position auction window has a logged close decision
- **AND** no slot allocation exists for its winning positions
- **THEN** reconciliation rebuilds allocation from logged decisions and final ranking facts

### Requirement: Reconciliation SHALL start with minimal projection repair

The system SHALL keep first-phase B' reconciliation limited to replaying logged decisions into MySQL projection and rebuilding missing slot allocation from projected bid facts. Redis hot-state drift detection and wallet ledger repair are deferred until a later change.

#### Scenario: Redis ranking differs from projected decisions
- **WHEN** Redis ranking for an auction window differs from durable projection
- **THEN** snapshot falls back to projected decision facts when Redis hot ranking is missing
- **AND** no wallet ledger repair is attempted by this change
