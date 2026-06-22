## ADDED Requirements

### Requirement: B' position auctions SHALL accept bids through ordered commands

The system SHALL accept fixed position promotion bids as durable commands routed through an ordered command stream keyed by auction window. HTTP bid submission MUST NOT directly mutate final ranking, bid status, or slot allocation tables.

#### Scenario: Submit bid command
- **WHEN** creator submits a promotion bid for an active position auction window
- **THEN** system creates or publishes a bid command keyed by auction window
- **AND** ordered command processing decides accepted or rejected outcome
- **AND** final ranking state is not mutated directly by the HTTP request handler

### Requirement: Redis Lua SHALL be the hot decision authority

The system SHALL use Redis Lua as the hot decision authority for active position auction windows. The script SHALL maintain window status, command replay, campaign bid state, and ranked bid state atomically for each auction window.

#### Scenario: Command replay returns same decision
- **WHEN** the same bid command is processed more than once with the same request hash
- **THEN** Redis Lua returns the original decision
- **AND** no duplicate rank or wallet hot-state effect is created

#### Scenario: Higher bid updates ranking
- **WHEN** a valid bid command exceeds current bidder amount and satisfies reserve/increment rules
- **THEN** Redis Lua accepts the command
- **AND** updates the window ranking atomically
- **AND** returns the new ranking snapshot for decision logging

### Requirement: Position auction decisions SHALL be appended to Kafka before user-visible confirmation

The system SHALL append accepted and rejected bid decisions to a Kafka decision log before treating the bid outcome as user-visible confirmation. MySQL projection SHALL consume the decision log rather than bypass it.

#### Scenario: Decision log append succeeds
- **WHEN** command processing produces an accepted decision
- **THEN** system appends the decision to the configured Kafka decision topic
- **AND** downstream projection can replay the decision from Kafka
- **AND** user-visible confirmation references the logged decision

#### Scenario: Decision log append fails
- **WHEN** Redis Lua produces a decision but Kafka decision append fails
- **THEN** system does not report the bid as confirmed
- **AND** releases any local wallet hold
- **AND** marks the command failed instead of retrying a released hold decision

### Requirement: Position auction projection SHALL build durable bid and allocation facts

The system SHALL project decision log records into MySQL bid facts, projection checkpoints, and final slot allocations. Projection MUST be idempotent by decision id and ordered per auction window.

#### Scenario: Accepted decision is projected
- **WHEN** projection consumer reads an accepted bid decision
- **THEN** system records durable promotion bid fact
- **AND** updates projection checkpoint for the auction window
- **AND** repeated consumption of same decision does not create duplicate facts

#### Scenario: Window close decision is projected
- **WHEN** projection consumer reads a window close decision
- **THEN** system computes winners and clearing prices from final ranked state
- **AND** writes slot allocations for configured fixed positions
- **AND** marks non-winning bids as lost or released

### Requirement: Position auction snapshots SHALL restore current window state

The system SHALL expose current position auction snapshot from Redis hot state and durable projection. Clients and operators SHALL recover from missed realtime events by reading snapshot rather than requiring WebSocket replay.

#### Scenario: Client reconnects during active auction
- **WHEN** client reconnects to an active position auction window
- **THEN** system returns current status, ranking, and server time from snapshot
- **AND** client can resume display without replaying realtime events

### Requirement: Paid boost SHALL be out of scope for B' position auctions

The system SHALL NOT implement paid boost, paid ranking weight, or organic score plus boost effect as part of B' position auctions. Commercial promotion distribution SHALL use fixed position allocation.

#### Scenario: Feed commercial item is selected
- **WHEN** feed returns commercial promotion content
- **THEN** content is selected from fixed position allocation
- **AND** system does not calculate paid boost score for commercial ranking
