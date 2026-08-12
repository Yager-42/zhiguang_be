# slot-auction-promotions Specification

## Purpose
TBD - created by archiving change add-slot-auction-promotions. Update Purpose after archive.
## Requirements
### Requirement: Slot auction promotions SHALL model zhiguang promotion resources

The system SHALL model slot auction promotions around zhiguang fixed advertising-slot resources. Supported first-phase resources SHALL include `feed_top_slot` and `search_top_slot`; each resource SHALL define a fixed `slotCount`, start price (reserve), bidding window, and allocation window. In auction terms, the platform sells the slot resource, and the winning creator places their post through the resulting allocation.

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

### Requirement: Slot auctions SHALL run shared-price English ascending auction

The system SHALL run each slot auction window as an English ascending-price auction over a single shared price ladder. The window starts with `currentPriceCents` equal to the configured start price (`reservePrice`); every accepted bid SHALL clear `currentPriceCents + incrementCents` (or reach the optional buy-now cap), and acceptance SHALL raise the shared current price to the accepted amount. The bidder holding the highest accepted bid when the window ends wins the single slot and pays their own final bid (first-price settlement). No second-price or multi-slot ranking applies.

#### Scenario: Bid below the shared ladder is rejected
- **WHEN** creator submits a bid below `currentPriceCents + incrementCents` while the window is open
- **THEN** the bid is rejected with `BID_NOT_HIGHER`
- **AND** the rejection payload includes the minimum required amount (`requiredAmount`) and the current shared price

#### Scenario: Accepted bid raises the shared price
- **WHEN** a bid clears the shared price ladder while the window is open
- **THEN** the bidder becomes the current winner
- **AND** the shared current price is raised to the accepted amount
- **AND** subsequent bids must clear the raised price plus the configured increment

#### Scenario: Concurrent bids are amount-linearized
- **WHEN** bid executions overlap in the same auction window and none has completed
- **THEN** the system MAY linearize the pending bids by `bidAmount` descending and stable ingress order
- **AND** one Redis Lua batch accepts at most the highest valid candidate
- **AND** an intermediate amount is not guaranteed a transient acceptance
- **AND** the final highest valid winner and first-price amount are not reduced by batching

#### Scenario: Current winner retries
- **WHEN** the current winner retries the same command id with the same request hash
- **THEN** Redis Lua replays the current `ACCEPTED` decision exactly
- **AND** a different request hash for that current command id returns `IDEMPOTENCY_CONFLICT`
- **AND** after a higher valid bid replaces the winner, retrying the old command is adjudicated against current state and does not replay historical acceptance

#### Scenario: Anti-snipe extension
- **WHEN** an accepted bid arrives within `extendWindowSec` of the window end
- **AND** the configured extension budget (`maxExtensions`) is not exhausted
- **THEN** the window end is extended by `extendSec`
- **AND** an `AUCTION_EXTENDED` realtime event notifies clients of the new end time
- **AND** the allocation period start remains the original window end (fixed allocation-window contract)

#### Scenario: Buy-now cap is reached
- **WHEN** `capPriceCents` is configured
- **AND** a bid reaches the cap
- **THEN** the auction ends immediately in `AUCTION_SOLD`
- **AND** the reaching bidder wins and pays the cap price

#### Scenario: Window closes with a winner
- **WHEN** the auction window ends with at least one accepted bid
- **THEN** the current winner is awarded the single slot
- **AND** pays their own final bid (`currentPriceCents`, first-price settlement)
- **AND** a single slot allocation is projected idempotently from logged auction decisions

#### Scenario: Window closes without bids
- **WHEN** the auction window ends with no accepted bid
- **THEN** the window terminates as `AUCTION_NO_BID`
- **AND** no slot allocation is produced

### Requirement: Promotion bids SHALL reserve wallet funds up to submitted bid

The system SHALL reserve bidder funds when accepting a promotion bid decision. On settlement projection, system SHALL deduct the winner's final bid (first price) and release any excess reservation; every losing campaign SHALL release the full authorized amount through idempotent wallet references. Windows ending with no bid SHALL release all reservations and produce no allocation.

#### Scenario: Winner settles at own final bid
- **WHEN** creator reserved funds at submitted max bid
- **AND** the auction ends with a winner
- **THEN** system deducts the winner's final bid amount (equal to the terminal shared current price)
- **AND** system releases the remaining held amount back to available balance

#### Scenario: Losing bid is projected
- **WHEN** auction window closes and a campaign is not the winner
- **THEN** system marks the bid as lost
- **AND** system releases the full held bid amount back to available balance

#### Scenario: No-bid window releases all reservations
- **WHEN** auction window closes with no accepted bid
- **THEN** all held amounts are released back to available balance
- **AND** no slot allocation is created

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

The system SHALL expose creator-facing realtime state for active slot auction windows when a realtime connection is available. Realtime state SHALL describe the auction window, shared current price, current ranking, private bid outcome notifications, and lifecycle status.

#### Scenario: Creator watches active slot auction
- **WHEN** creator opens an active slot auction window
- **THEN** client reads the current auction snapshot
- **AND** subscribes to realtime auction events for that window
- **AND** displays shared current price and ranking from snapshot plus subsequent public events
- **AND** displays bid confirmation or rejection from private outcome events

#### Scenario: Realtime unavailable
- **WHEN** realtime connection is unavailable or stale
- **THEN** client can continue to read snapshot
- **AND** the auction decision and allocation results remain valid
