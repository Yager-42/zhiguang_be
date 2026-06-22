## MODIFIED Requirements

### Requirement: Home feed SHALL mix multiple sources

The home feed SHALL insert active promoted feed slots from B' fixed position auction allocations before organic mixed feed results, then combine follow feed, recommendation candidates, and hot fallback content for the remaining organic positions. Home feed SHALL NOT apply paid boost weighting or `organic score + boost effect`.

#### Scenario: Build home feed with promoted slot
- **WHEN** a user requests home feed during an active feed slot allocation window
- **THEN** the system loads promoted slot allocation projected from B' position auction decisions
- **AND** inserts promoted content with commercial marker into configured feed slot position
- **AND** fills remaining organic positions from local follow candidates, recommendation candidates, and hot fallback
- **AND** deduplicates, filters, and hydrates the final result

#### Scenario: Build home feed without promoted slot
- **WHEN** no active feed slot allocation exists for request scope
- **THEN** the system returns organic home feed built from local follow candidates, recommendation candidates, and hot fallback content

## REMOVED Requirements

### Requirement: Home feed SHALL mix organic sources with paid boost weighting

**Reason**: Paid boost is removed from this phase. Commercial promotion distribution now uses fixed position auction allocation only.

**Migration**: Use `feed_top_slot` position auction allocation for commercial feed placement. Organic ranking remains unchanged.

### Requirement: Follow feed SHALL support boost-based priority under constrained delivery

**Reason**: Paid boost is removed from this phase. Follow-feed delivery SHALL remain organic and local; commercial promotion placement SHALL use fixed feed slot allocation.

**Migration**: Remove paid boost priority from follow delivery. Commercial content enters home feed through projected position allocation.
