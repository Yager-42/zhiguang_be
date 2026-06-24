## MODIFIED Requirements

### Requirement: Slot auction promotions SHALL model zhiguang promotion resources

The system SHALL model slot auction promotions around zhiguang fixed advertising-slot resources. Supported first-phase resources SHALL include `feed_top_slot` and `search_top_slot`; each resource SHALL define a fixed `slotCount`, reserve price, bidding window, and allocation window. In auction terms, the platform sells the slot resource, and the winning creator places their post through the resulting allocation.

#### Scenario: Promotion campaign targets feed slot
- **WHEN** creator submits slot promotion for a post
- **THEN** campaign references zhiguang content and `feed_top_slot` resource
- **AND** campaign does not require product or live-room entities
- **AND** campaign competes for one of the configured fixed feed positions
- **AND** the post is the content displayed after the creator wins the slot allocation

### Requirement: Slot auctions SHALL reject paid boost semantics

The system SHALL NOT treat slot auction promotion as paid boost, paid ranking weight, weight slot auction, or `organic score + boost effect`. Fixed advertising-slot allocation SHALL be the only commercial promotion distribution mechanism in this capability.

#### Scenario: Creator requests ranking weight purchase
- **WHEN** creator attempts to create a promotion that buys recommendation weight without competing for fixed advertising-slot allocation
- **THEN** system rejects or hides the unsupported paid boost workflow
- **AND** no paid boost campaign, weight campaign, boost delivery fact, or paid ranking score is created

#### Scenario: Existing code uses weight naming
- **WHEN** a promotion auction API, DTO, topic, config, or UI label exposes weight-slot or paid-boost terminology
- **THEN** the implementation renames the surface to fixed slot auction terminology
- **AND** preserves existing fixed slot auction behavior

## ADDED Requirements

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
