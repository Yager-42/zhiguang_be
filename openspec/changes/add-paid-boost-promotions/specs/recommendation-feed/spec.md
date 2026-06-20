## MODIFIED Requirements

### Requirement: Home feed SHALL mix organic sources with paid boost weighting

The home feed SHALL continue to combine follow feed, recommendation candidates, and hot fallback content, while applying paid boost weighting to eligible promoted content inside applicable ranking stages.

#### Scenario: Build home feed with boosted recommendation candidates
- **WHEN** user requests home feed and some eligible candidates have active paid boost campaigns
- **THEN** the system applies configured paid boost weighting during applicable ranking stage
- **AND** the system still combines follow candidates, recommendation candidates, and hot fallback content
- **AND** boosted items remain subject to deduplication, filtering, hydration, and commercial marking

#### Scenario: Build home feed without active paid boost
- **WHEN** no eligible candidate has active paid boost campaign
- **THEN** the system returns home feed using organic follow, recommendation, and hot fallback behavior

## ADDED Requirements

### Requirement: Follow feed SHALL support boost-based priority under constrained delivery

The follow feed SHALL support paid boost priority when delivery must be constrained by configured quota, truncation, or capacity rule, while remaining locally generated from relation and publish events.

#### Scenario: Constrained follow delivery prefers boosted item
- **WHEN** follow delivery logic must choose between multiple eligible items under configured constraint
- **AND** some items have active paid boost campaigns
- **THEN** the system prioritizes boosted items according to configured boost rule
- **AND** follow feed still remains locally generated rather than delegated to external recommendation engine
