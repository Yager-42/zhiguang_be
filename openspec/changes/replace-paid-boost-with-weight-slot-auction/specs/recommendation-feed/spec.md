## MODIFIED Requirements

### Requirement: Home feed SHALL mix multiple sources

The home feed SHALL insert active promoted feed slots from B' fixed advertising-slot auction allocations before organic mixed feed results, then combine follow feed, recommendation candidates, and hot fallback content for the remaining organic positions. Home feed SHALL NOT apply paid boost weighting, weight slot scoring, or `organic score + boost effect`.

#### Scenario: Build home feed with promoted slot
- **WHEN** a user requests home feed during an active feed slot allocation window
- **THEN** the system loads promoted slot allocation projected from B' position auction decisions
- **AND** inserts promoted content with commercial marker into configured feed slot position
- **AND** fills remaining organic positions from local follow candidates, recommendation candidates, and hot fallback
- **AND** deduplicates, filters, and hydrates the final result
- **AND** does not use WebSocket events as the feed allocation source

#### Scenario: Build home feed without promoted slot
- **WHEN** no active feed slot allocation exists for request scope
- **THEN** the system returns organic home feed built from local follow candidates, recommendation candidates, and hot fallback content
- **AND** does not calculate paid boost or paid ranking weight for organic candidates
