## ADDED Requirements

### Requirement: Paid boost promotions SHALL be non-auction campaigns

The system SHALL model paid boost promotions as non-auction campaigns. A paid boost campaign MUST NOT produce slot winners, GSP clearing prices, or slot allocations.

#### Scenario: Creator opens paid boost campaign
- **WHEN** creator starts paid boost campaign for eligible content
- **THEN** campaign records target content, channel, window, budget, and boost value
- **AND** campaign does not create auction winner semantics

### Requirement: Paid boost campaigns SHALL reserve and settle budget

The system SHALL reserve campaign budget when campaign becomes active, settle consumed spend through wallet ledger movements, and release unconsumed budget when campaign closes.

#### Scenario: Campaign closes with remaining budget
- **WHEN** paid boost campaign ends before consuming full reserved budget
- **THEN** system settles consumed amount
- **AND** releases remaining reserved balance back to creator wallet

### Requirement: Paid boost SHALL affect recommendation ranking through local weighting

The system SHALL combine organic recommendation relevance with configured paid boost effect when ranking eligible promoted content in recommendation flows.

#### Scenario: Boosted content competes in recommendation ranking
- **WHEN** content is eligible for recommendation ranking and has active paid boost campaign
- **THEN** system computes ranking with organic score plus configured boost effect
- **AND** boosted content remains subject to visibility and deletion filtering

### Requirement: Paid boost SHALL affect follow-delivery priority only under constrained delivery

The system SHALL use boost value to prioritize delivery when follow-feed candidate delivery is constrained by configured quota, truncation, or capacity limit.

#### Scenario: Follow delivery candidate set exceeds configured cap
- **WHEN** follow-feed delivery logic must choose subset of eligible content under configured constraint
- **THEN** system prioritizes higher active boost value among otherwise eligible items
- **AND** items outside selected set are not treated as auction losers

### Requirement: Paid boost output SHALL be commercially marked

The system SHALL mark boost-delivered content as commercial content in response contracts or delivery metadata so clients can distinguish promoted distribution from organic distribution.

#### Scenario: Boosted item is returned in feed
- **WHEN** active paid boost causes content to surface in feed
- **THEN** returned item includes commercial promotion marker
- **AND** client can distinguish boosted delivery from organic delivery
