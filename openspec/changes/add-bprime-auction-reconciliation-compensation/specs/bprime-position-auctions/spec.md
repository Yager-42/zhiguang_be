## ADDED Requirements

### Requirement: B' durable facts SHALL define compensation authority
The system SHALL use Kafka decision log, projection checkpoints, settled MySQL bid/window/allocation facts, and wallet ledger facts as compensation authority for B' promotion auctions. MySQL persisted promotion decision facts SHALL NOT be used because the per-decision table is removed. RocketMQ command records are audit-only entry facts. Redis hot state and WebSocket delivery are derived display state and are not compensation targets in this change.

#### Scenario: Command record is missing but settled chain is complete
- **WHEN** a settled auction window has durable decision, projection, wallet, and allocation facts
- **AND** the RocketMQ command record is missing
- **THEN** compensation records an operator-visible warning
- **AND** does not fail the settled repair path on command absence

#### Scenario: WebSocket event is missing
- **WHEN** a Kafka decision exists but no WebSocket event was delivered
- **THEN** compensation does not rewrite auction facts
- **AND** clients recover display state through snapshot
