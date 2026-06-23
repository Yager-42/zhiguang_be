## ADDED Requirements

### Requirement: B' durable facts SHALL define compensation authority
The system SHALL use Kafka decision log, projection checkpoints, wallet ledger facts, and slot allocation facts as compensation authority for B' promotion auctions. MySQL persisted promotion decision facts SHALL NOT be used because the per-decision table is removed. Redis hot state and WebSocket delivery SHALL be repairable or recoverable derived state.

#### Scenario: Redis conflicts with Kafka decision
- **WHEN** Redis hot ranking conflicts with Kafka decision facts or MySQL final projection facts
- **THEN** compensation treats durable decision facts as authoritative
- **AND** rebuilds or marks Redis hot state instead of rewriting decision facts from Redis

#### Scenario: WebSocket event is missing
- **WHEN** a Kafka decision exists but no WebSocket event was delivered
- **THEN** compensation does not rewrite auction facts
- **AND** clients recover display state through snapshot
