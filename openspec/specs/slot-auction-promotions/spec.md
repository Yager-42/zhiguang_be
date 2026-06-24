# slot-auction-promotions Specification

## Purpose
TBD - created by archiving change add-slot-auction-promotions. Update Purpose after archive.
## Requirements
### Requirement: Slot auction promotions SHALL model zhiguang promotion resources

The system SHALL model slot auction promotions around zhiguang fixed advertising-slot resources. Supported first-phase resources SHALL include `feed_top_slot` and `search_top_slot`; each resource SHALL define a fixed `slotCount`, reserve price, bidding window, and allocation window. In auction terms, the platform sells the slot resource, and the winning creator places their post through the resulting allocation.

#### Scenario: Promotion campaign targets feed slot
- **WHEN** creator submits slot promotion for a post
- **THEN** campaign references zhiguang content and `feed_top_slot` resource
- **AND** campaign does not require product or live-room entities
- **AND** campaign competes for one of the configured fixed feed positions
- **AND** the post is the content displayed after the creator wins the slot allocation

### Requirement: Slot auctions SHALL run by cached auction windows

The system SHALL collect bids into explicit auction windows through the B' ordered command and Redis Lua decision path, then project durable allocations when each window closes. Read paths SHALL consume cached allocation results instead of running auctions per request.

#### Scenario: Feed request reads current allocation
- **WHEN** client requests feed during active allocation period
- **THEN** system reads current cached feed slot allocation for applicable window
- **AND** system does not execute auction ranking inside request path

### Requirement: Slot auctions SHALL use multi-slot GSP pricing

The system SHALL allocate top M effective bids to M fixed positions and SHALL settle each winning position at the next effective bid or configured reserve price, whichever is higher. Ranking input SHALL come from the B' decision/projection chain rather than ad hoc MySQL sorting in the request path.

#### Scenario: Two-slot window settles
- **WHEN** auction window for two slots closes with ranked effective bids
- **THEN** top two bids win slot allocations
- **AND** each winner pays GSP settlement price derived from next ranked bid or reserve floor
- **AND** settlement is projected idempotently from logged auction decisions

### Requirement: Promotion bids SHALL reserve wallet funds up to submitted bid

The system SHALL reserve bidder funds when accepting a promotion bid decision. On settlement projection, system SHALL deduct final clearing price and release any excess reservation. Rejected, below-reserve, or losing bids SHALL release their held funds through idempotent wallet references.

#### Scenario: Winning bid clears below reserved max
- **WHEN** creator reserved funds at submitted max bid
- **AND** final GSP clearing price is lower than reserved amount
- **THEN** system deducts clearing price
- **AND** system releases remaining held amount back to available balance

#### Scenario: Losing bid is projected
- **WHEN** auction window closes and a bid is outside configured slot count
- **THEN** system marks bid as lost
- **AND** system releases the full held bid amount back to available balance

### Requirement: Slot allocations SHALL drive feed and search insertion

The system SHALL expose slot allocation results to feed and search rendering, including promoted-vs-organic distinction and resource-specific placement metadata. Slot allocations SHALL be produced only by B' position auction projection.

#### Scenario: Search result page includes promoted slot
- **WHEN** active search slot allocation exists for request scope
- **THEN** search response includes promoted result with commercial placement metadata
- **AND** promoted result is distinguishable from organic result items

### Requirement: Slot allocations SHALL enforce commercial limits and labeling

The system SHALL enforce configured promoted-slot count limits and SHALL mark promoted content as commercial content in output contracts.

#### Scenario: Feed promoted limit is reached
- **WHEN** feed response already contains configured maximum promoted slot count for rendered slice
- **THEN** system does not insert additional promoted slot items
- **AND** inserted promoted items are flagged as commercial

### Requirement: Slot auction promotions SHALL reject paid boost semantics

The system SHALL NOT treat slot auction promotion as paid boost, paid ranking weight, weight slot auction, or `organic score + boost effect`. Fixed advertising-slot allocation SHALL be the only commercial promotion distribution mechanism in this capability.

#### Scenario: Creator requests ranking weight purchase
- **WHEN** creator attempts to create a promotion that buys recommendation weight without competing for fixed advertising-slot allocation
- **THEN** system rejects or hides the unsupported paid boost workflow
- **AND** no paid boost campaign, weight campaign, boost delivery fact, or paid ranking score is created

#### Scenario: Existing code uses weight naming
- **WHEN** a promotion auction API, DTO, topic, config, or UI label exposes weight-slot or paid-boost terminology
- **THEN** the implementation renames the surface to fixed slot auction terminology
- **AND** preserves existing fixed slot auction behavior

### Requirement: Slot auction UI SHALL expose realtime auction state

The system SHALL expose creator-facing realtime state for active slot auction windows when a realtime connection is available. Realtime state SHALL describe the auction window, current ranking, private bid outcome notifications, and lifecycle status.

#### Scenario: Creator watches active slot auction
- **WHEN** creator opens an active slot auction window
- **THEN** client reads the current auction snapshot
- **AND** subscribes to realtime auction events for that window
- **AND** displays ranking from snapshot plus subsequent public events
- **AND** displays bid confirmation or rejection from private outcome events

#### Scenario: Realtime unavailable
- **WHEN** realtime connection is unavailable or stale
- **THEN** client can continue to read snapshot
- **AND** the auction decision and allocation results remain valid
