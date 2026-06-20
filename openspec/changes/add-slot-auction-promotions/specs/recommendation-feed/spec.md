## MODIFIED Requirements

### Requirement: Home feed SHALL mix promoted slots and organic sources

The home feed SHALL insert active promoted feed slots before organic mixed feed results, then combine follow feed, recommendation candidates, and hot fallback content for the remaining organic positions.

#### Scenario: Build home feed with promoted slot
- **WHEN** a user requests home feed during an active feed slot allocation window
- **THEN** the system loads promoted slot allocation for applicable window
- **AND** inserts promoted content with commercial marker into configured feed slot position
- **AND** fills remaining organic positions from local follow candidates, recommendation candidates, and hot fallback
- **AND** deduplicates, filters, and hydrates the final result

#### Scenario: Build home feed without promoted slot
- **WHEN** no active feed slot allocation exists for request scope
- **THEN** the system returns organic home feed built from local follow candidates, recommendation candidates, and hot fallback content
