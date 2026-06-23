## ADDED Requirements

### Requirement: Promotion auction realtime SHALL fan out from Kafka decisions
The system SHALL deliver promotion auction realtime updates by consuming the Kafka promotion auction decision topic with an independent fanout consumer group. The fanout consumer SHALL run independently from the MySQL projection consumer and MUST NOT wait for projection checkpoint advancement before publishing user-facing realtime messages.

#### Scenario: Accepted decision is fanned out
- **WHEN** Redis Lua accepts a promotion bid command
- **AND** the decision is appended to Kafka
- **THEN** the realtime fanout consumer consumes the same decision topic as projection
- **AND** publishes a promotion auction realtime event for the affected auction window
- **AND** does not require the MySQL projection consumer to finish first

#### Scenario: Projection is delayed
- **WHEN** a promotion decision exists in Kafka
- **AND** MySQL projection is temporarily behind
- **THEN** realtime fanout can still publish the logged decision event
- **AND** clients treat the event as based on Kafka decision authority rather than projection state
- **AND** MySQL is not required to contain an individual row for that bid decision

### Requirement: WebSocket messages SHALL be display events, not authority
The system SHALL use WebSocket or STOMP only to deliver display events for promotion auction windows. Kafka decision log SHALL remain the authority for accepted, rejected, and terminal decision facts.

#### Scenario: Client receives ranking update
- **WHEN** the client receives a ranking update event over WebSocket
- **THEN** the client may update visible ranking, price, countdown, and own bid status from the event payload
- **AND** the server does not treat WebSocket delivery as proof that the auction decision exists

#### Scenario: WebSocket delivery fails
- **WHEN** a logged decision cannot be delivered through WebSocket
- **THEN** the logged Kafka decision remains valid
- **AND** final MySQL projection remains valid when the close decision is projected
- **AND** the client can recover by reading the snapshot API

#### Scenario: WebSocket layer has no business state
- **WHEN** a service needs auction ranking, allocation, or final winner state
- **THEN** it reads Redis active snapshot, Kafka replay, or MySQL final projection according to the use case
- **AND** no service reads WebSocket session state as auction authority

### Requirement: Realtime events SHALL cover ranking, bid outcome, and window lifecycle
The system SHALL publish realtime promotion auction events for public window changes and targeted bidder outcomes. Public events SHALL include ranking or window lifecycle changes. Targeted events SHALL include bid confirmed and bid rejected outcomes for the bidder.

#### Scenario: Public ranking changes
- **WHEN** an accepted bid changes the auction window ranking
- **THEN** the system publishes a public ranking update for the auction window
- **AND** the event includes decision version or equivalent monotonic version

#### Scenario: Bidder receives outcome
- **WHEN** a creator's bid decision is accepted or rejected
- **THEN** the system publishes a targeted outcome event to that creator
- **AND** the event includes the command id, decision id, decision version, and outcome reason when rejected

#### Scenario: Window closes
- **WHEN** a close decision is logged for an auction window
- **THEN** the system publishes a terminal window event
- **AND** clients can update the visible auction status without waiting for feed or search rendering

### Requirement: WebSocket routing SHALL separate public window topics and private creator outcomes
The system SHALL expose one STOMP endpoint for promotion auction realtime delivery. Public window events SHALL be sent to an auction-window topic. Private bid outcome events SHALL be sent through Spring user destinations keyed by the authenticated creator user id.

#### Scenario: Client subscribes to public window topic
- **WHEN** a client subscribes to `/topic/promotion-auctions/{auctionWindowId}`
- **THEN** the client receives public ranking and lifecycle events for that window
- **AND** guest clients may receive public events

#### Scenario: Creator receives private outcome
- **WHEN** fanout publishes a bid confirmed or bid rejected outcome for bidder user `42`
- **THEN** system sends the event to user `42` at `/queue/promotion-auction-outcomes`
- **AND** does not publish the private outcome to a public user-id topic

#### Scenario: WebSocket principal is resolved
- **WHEN** a client connects to `/ws/promotion-auction` with `Authorization: Bearer <jwt>` or `?access_token=<jwt>`
- **THEN** system resolves `Principal.getName()` to the zhiguang user id
- **AND** anonymous clients receive a guest principal for public topic access

### Requirement: Snapshot SHALL recover realtime gaps
The system SHALL keep promotion auction snapshot as the recovery path for reconnects, missed WebSocket messages, and version gaps. Clients SHALL use snapshot when their local event version is stale or when they reconnect to an active auction window.

#### Scenario: Client reconnects
- **WHEN** a client reconnects to an active promotion auction window
- **THEN** the client reads snapshot for current status, ranking, server time, and latest decision version
- **AND** resumes WebSocket consumption from the latest visible state

#### Scenario: Client detects version gap
- **WHEN** a client receives a realtime event whose version does not follow its local version
- **THEN** the client reads snapshot
- **AND** replaces its local auction display state with snapshot data

#### Scenario: Creator restores own visible state
- **WHEN** a creator reloads after reconnect
- **THEN** the client derives current visible rank from snapshot ranking when its campaign is present
- **AND** uses subsequent private outcome events for confirmed or rejected bid status
- **AND** backend snapshot does not need to query a separate own-bid state for this change
