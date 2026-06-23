## MODIFIED Requirements

### Requirement: Position auction decisions SHALL be appended to Kafka before user-visible confirmation

The system SHALL append accepted, rejected, and terminal promotion auction decisions to a Kafka decision log before treating the decision as user-visible confirmation. Kafka decision log SHALL be the confirmation boundary for realtime fanout and final MySQL projection. MySQL SHALL NOT be the high-frequency store for every bid decision payload.

#### Scenario: Decision log append succeeds
- **WHEN** command processing produces an accepted decision
- **THEN** system appends the decision to the configured Kafka decision topic
- **AND** downstream projection can replay the decision from Kafka
- **AND** downstream realtime fanout can publish the decision from Kafka
- **AND** user-visible confirmation references the logged decision
- **AND** MySQL is not required to store this individual bid decision before confirmation is visible

#### Scenario: Decision log append fails
- **WHEN** Redis Lua produces a decision but Kafka decision append fails
- **THEN** system does not report the bid as confirmed
- **AND** releases any local wallet hold
- **AND** marks the command failed instead of retrying a released hold decision
- **AND** does not publish WebSocket or STOMP confirmation for the failed append

### Requirement: Position auction snapshots SHALL restore current window state

The system SHALL expose current position auction snapshot from Redis hot state while the window is active and from final MySQL projection after the window closes. Clients and operators SHALL recover from reconnects, missed realtime events, and version gaps by reading snapshot. Snapshot SHALL NOT replace the normal WebSocket or STOMP realtime fanout path while clients are connected.

#### Scenario: Client reconnects during active auction
- **WHEN** client reconnects to an active position auction window
- **THEN** system returns current status, ranking, server time, and latest decision version from Redis-backed snapshot
- **AND** client can resume display without replaying all realtime events

#### Scenario: Client loads closed window
- **WHEN** client reads snapshot after a position auction window closes
- **THEN** system returns final status and final allocation from MySQL projection
- **AND** does not read WebSocket state or active Redis hot ranking as final allocation authority

#### Scenario: Client misses realtime event
- **WHEN** client detects that realtime event versions have a gap
- **THEN** client reads the snapshot API
- **AND** replaces local display state with the snapshot result

## ADDED Requirements

### Requirement: Position auction decisions SHALL feed projection and fanout independently
The system SHALL run final MySQL projection and realtime fanout as separate consumers of the Kafka decision topic. Projection SHALL build final allocation, settlement/wallet effects, final window status, and checkpoints. Fanout SHALL publish display events. Neither consumer SHALL be the authority for the other.

#### Scenario: Same decision feeds two consumers
- **WHEN** a promotion auction decision is appended to Kafka
- **THEN** the projection consumer reads it when needed to update final MySQL projection and checkpoints
- **AND** the fanout consumer reads it to publish realtime display events
- **AND** both consumers use the Kafka decision id and decision version for idempotency

#### Scenario: Fanout fails after decision append
- **WHEN** the fanout consumer fails to publish a realtime event
- **THEN** MySQL projection can still consume and apply the decision
- **AND** client recovery remains possible through snapshot

### Requirement: Closed position auction SHALL project only final results to MySQL
The system SHALL project final promotion auction results to MySQL when a `WINDOW_CLOSED` decision is consumed. Final projection SHALL include winners, slot allocation, settlement/wallet effects, final window status, and projection checkpoint. Rejected bid decisions and transient ranking updates SHALL remain in Kafka and Redis hot state, not per-decision MySQL rows.

#### Scenario: Window close decision is projected
- **WHEN** a `WINDOW_CLOSED` decision is consumed by projection
- **THEN** system writes final slot allocation for the winning campaigns
- **AND** writes final settlement or wallet effects needed by the business
- **AND** records final window status and projection checkpoint
- **AND** does not require every prior accepted or rejected bid decision to already exist as a MySQL row

#### Scenario: Rejected decision is consumed
- **WHEN** a rejected bid decision is consumed by projection
- **THEN** system may advance checkpoint
- **AND** does not create a bid fact, slot allocation, or commercial feed/search placement for that rejected decision
