## ADDED Requirements

### Requirement: Slot auction promotions SHALL model zhiguang promotion resources

The system SHALL model slot auction promotions around zhiguang promotion resources instead of `bytedance` product-auction terms. Supported first-phase resources SHALL include `feed_top_slot` and `search_top_slot`.

#### Scenario: Promotion campaign targets feed slot
- **WHEN** creator submits slot promotion for a post
- **THEN** campaign references zhiguang content and `feed_top_slot` resource
- **AND** campaign does not require product or live-room entities

### Requirement: Slot auctions SHALL run by cached auction windows

The system SHALL collect bids into explicit auction windows and SHALL compute allocations when each window closes. Read paths SHALL consume cached allocation results instead of running auctions per request.

#### Scenario: Feed request reads current allocation
- **WHEN** client requests feed during active allocation period
- **THEN** system reads current cached feed slot allocation for applicable window
- **AND** system does not execute auction ranking inside request path

### Requirement: Slot auctions SHALL use multi-slot GSP pricing

The system SHALL allocate top M bids to M slots and SHALL settle each winning slot at the next effective bid or configured reserve price, whichever is higher.

#### Scenario: Two-slot window settles
- **WHEN** auction window for two slots closes with ranked effective bids
- **THEN** top two bids win slot allocations
- **AND** each winner pays GSP settlement price derived from next ranked bid or reserve floor

### Requirement: Promotion bids SHALL reserve wallet funds up to submitted bid

The system SHALL reserve bidder funds when accepting a promotion bid. On settlement, system SHALL deduct final clearing price and release any excess reservation.

#### Scenario: Winning bid clears below reserved max
- **WHEN** creator reserved funds at submitted max bid
- **AND** final GSP clearing price is lower than reserved amount
- **THEN** system deducts clearing price
- **AND** system releases remaining held amount back to available balance

### Requirement: Slot allocations SHALL drive feed and search insertion

The system SHALL expose slot allocation results to feed and search rendering, including promoted-vs-organic distinction and resource-specific placement metadata.

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
