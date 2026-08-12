# bprime-position-auctions Specification

## Purpose
Define the B' fixed-position promotion auction command, hot decision, decision log, projection, and snapshot path.

## Requirements
### Requirement: B' position auctions SHALL accept bids through ordered commands

The system SHALL accept fixed position promotion bids as durable commands routed through an ordered command stream keyed by auction window. HTTP bid submission MUST NOT directly mutate final ranking, bid status, or slot allocation tables.

#### Scenario: Submit bid command
- **WHEN** creator submits a promotion bid for an active position auction window
- **THEN** system creates or publishes a bid command keyed by auction window
- **AND** ordered command processing decides accepted or rejected outcome
- **AND** final ranking state is not mutated directly by the HTTP request handler

### Requirement: Redis Lua SHALL be the hot decision authority

The system SHALL use batched Redis Lua as the sole hot decision authority for active position auction windows. Each application instance SHALL combine pending commands per window, order overlapping incomplete commands by amount descending with stable ingress tie-break, and submit bounded batches. The script SHALL maintain window status, the current-winner replay slot, campaign bid state, escrow hold, ranked bid state, decision version, and Stream events atomically; each batch SHALL create at most one new accepted decision.

#### Scenario: Current winner command replay returns same decision
- **WHEN** the current winning command is processed again with the same request hash before a higher bid replaces it
- **THEN** Redis Lua returns the original current `ACCEPTED` decision
- **AND** no duplicate rank, Stream, version, or wallet hot-state effect is created
- **AND** a conflicting request hash returns `IDEMPOTENCY_CONFLICT`

#### Scenario: Historical command is no longer replayable
- **WHEN** a higher valid bid has replaced a previously accepted command
- **AND** the previous command is submitted again
- **THEN** Redis Lua adjudicates it against the current authoritative state
- **AND** does not replay the historical `ACCEPTED` decision

#### Scenario: Higher bid raises the shared ladder
- **WHEN** a valid bid command clears the shared current price plus the configured increment (or reaches the buy-now cap)
- **THEN** Redis Lua accepts the command
- **AND** updates the shared current price, the current winner, and the window ranking atomically
- **AND** returns the new ranking snapshot for decision logging

### Requirement: Position auction decisions SHALL be appended to Kafka before user-visible confirmation

The system SHALL append accepted, rejected, and terminal promotion auction decisions to a Kafka decision log before treating the decision as user-visible confirmation. Kafka decision log SHALL be the confirmation boundary for realtime fanout and final MySQL projection. MySQL SHALL NOT be the high-frequency store for every bid decision payload.

All promotion auction decision types SHALL share topic `zhiguang.promotion.auction.decisions.v2` and Kafka key `auctionWindowId`. The common envelope SHALL follow the bytedance B' shape: `schemaVersion`, outer `eventType=AUCTION_DECISION`, nested `decision`, `decisionHash`, and `producedAt`. Business type SHALL be carried by nested `decision.type`, not by topic, partition, or outer event type. The system SHALL NOT move terminal decision types (`AUCTION_SOLD` / `AUCTION_NO_BID`) to a separate topic or partition scheme.

#### Scenario: Decision log append succeeds
- **WHEN** command processing produces an accepted decision
- **THEN** system appends the decision to the configured Kafka decision topic
- **AND** uses Kafka key `auctionWindowId`
- **AND** includes a consumer-verifiable `decisionHash`
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

The system SHALL project final promotion auction results to MySQL when a terminal decision (`AUCTION_SOLD` or `AUCTION_NO_BID`) is consumed. Final projection SHALL include the single winner, slot allocation, settlement/wallet effects, final window status, and projection checkpoint. Rejected bid decisions and transient ranking updates SHALL remain in Kafka and Redis hot state, not per-decision MySQL rows.

#### Scenario: Accepted decision is projected
- **WHEN** a `BID_ACCEPTED` decision is consumed by projection
- **THEN** system upserts the lightweight `promotion_bid` fact for campaign, auction window, bidder, bid amount, command id, decision id, and projection source
- **AND** applies the wallet hold state needed by the accepted bid
- **AND** advances projection checkpoint
- **AND** does not create slot allocation or feed/search-visible commercial placement before window close

#### Scenario: Window close decision is projected
- **WHEN** a terminal decision (`AUCTION_SOLD` or `AUCTION_NO_BID`) is consumed by projection
- **AND** the decision payload contains the winner campaign, winning amount (first price), actual end time, wallet effects, allocation window, and final window status
- **THEN** system writes the single slot allocation for the winning campaign (`AUCTION_SOLD` only)
- **AND** writes final settlement or wallet effects needed by the business
- **AND** records final window status and projection checkpoint
- **AND** does not read Redis hot ranking as final settlement authority
- **AND** does not require every prior accepted or rejected bid decision to already exist as a MySQL row

#### Scenario: Rejected decision is consumed
- **WHEN** a rejected bid decision is consumed by projection
- **THEN** system may advance checkpoint
- **AND** does not create a bid fact, slot allocation, or commercial feed/search placement for that rejected decision

### Requirement: Paid boost SHALL be out of scope for B' position auctions

The system SHALL NOT implement paid boost, paid ranking weight, or organic score plus boost effect as part of B' position auctions. Commercial promotion distribution SHALL use fixed advertising-slot allocation.

#### Scenario: Feed commercial item is selected
- **WHEN** feed returns commercial promotion content
- **THEN** content is selected from fixed position allocation
- **AND** system does not calculate paid boost score for commercial ranking
