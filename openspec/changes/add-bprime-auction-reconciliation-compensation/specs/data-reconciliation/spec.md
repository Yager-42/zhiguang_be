## MODIFIED Requirements

### Requirement: Reconciliation SHALL cover B' position auction command and decision chain

The system SHALL repair B' inconsistencies where Kafka decision payloads need replay into MySQL projection facts, where `SETTLED` windows are fully missing slot allocation, and where settled `CAPTURE` / `RELEASE` wallet effects are missing or inconsistent. RocketMQ command records SHALL remain audit-only and SHALL NOT block settled repair when the durable settled chain is otherwise complete. MySQL per-decision facts SHALL NOT be used because `promotion_auction_decision` is removed by the promotion realtime change.

#### Scenario: Decision is logged but projection is missing
- **WHEN** Kafka decision log contains an accepted position auction decision
- **AND** MySQL projection for that decision is missing
- **THEN** reconciliation schedules or executes projection replay
- **AND** wallet and bid facts remain idempotent

#### Scenario: Decision log has expired
- **WHEN** MySQL projection is missing
- **AND** required Kafka promotion decision facts are older than the configured retention window
- **THEN** reconciliation marks the task `dead` for operator-visible follow-up
- **AND** does not use a MySQL per-decision backup table as an alternate replay source

#### Scenario: Slot allocation is fully missing after settled window
- **WHEN** a position auction window is `SETTLED`
- **AND** no slot allocation exists for that window
- **AND** MySQL settled bid and window facts are sufficient to recompute winners and clearing prices
- **THEN** reconciliation rebuilds allocation from recomputed durable facts

#### Scenario: Slot allocation is partially present after settled window
- **WHEN** a position auction window is `SETTLED`
- **AND** slot allocation rows exist but are incomplete or inconsistent
- **THEN** reconciliation marks the window `dead`
- **AND** does not attempt automatic partial-row repair

#### Scenario: Command record is missing but settled facts are complete
- **WHEN** a settled window has durable projection, allocation, and wallet facts
- **AND** the RocketMQ command record is missing
- **THEN** reconciliation records an operator-visible warning
- **AND** does not fail or block settled repair on command absence

#### Scenario: Wallet effect is missing
- **WHEN** a recomputed settled result requires capture or release wallet movement
- **AND** the matching wallet businessRef is missing
- **THEN** reconciliation schedules or executes wallet effect repair
- **AND** repeated repair remains idempotent

### Requirement: Reconciliation SHALL start with minimal projection repair

The system SHALL keep first-phase B' reconciliation compatible with existing projection replay and allocation rebuild, then extend it with settled-window wallet effect repair and operator rerun for promotion auctions. Deep repair SHALL remain scoped to B' promotion auction settled facts and SHALL NOT become a full-platform reconciliation rewrite.

#### Scenario: Wallet repair runs from settled facts
- **WHEN** wallet ledger repair is triggered for a settled window
- **THEN** reconciliation derives expected `CAPTURE` / `RELEASE` effects from recomputed MySQL settled facts
- **AND** does not add settled-phase `HOLD` repair

#### Scenario: Non-B' target is encountered
- **WHEN** a B' deep compensation task receives a target type outside Kafka promotion decision id or promotion auction window
- **THEN** system rejects the task
- **AND** does not run generic full-platform repair
