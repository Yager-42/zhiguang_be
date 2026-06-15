# Recommendation and Follow Feed Specification

## ADDED Requirements

### Requirement: Recommendation engine SHALL be adapter-based

The application SHALL access Gorse through a `RecommendationEngine` interface.

#### Scenario: Fetch recommended candidates

- **WHEN** the feed service requests recommendations for a user
- **THEN** it calls `RecommendationEngine`
- **AND** receives candidate content IDs, scores, reasons, and source labels

#### Scenario: Replace recommendation engine

- **WHEN** a future engine replaces Gorse
- **THEN** business feed code remains unchanged except for adapter configuration or implementation

### Requirement: Adapter SHALL not return full Feed items

The recommendation adapter SHALL not hydrate post details or decide final mixed Feed output.

#### Scenario: Hydrate candidates

- **WHEN** Gorse returns candidate IDs
- **THEN** local services load post details, author info, counts, and user interaction state
- **AND** local services apply visibility and deletion filters

### Requirement: Follow feed SHALL be local and deterministic

The follow feed SHALL be generated from local relation and publish events, not from Gorse.

#### Scenario: Published content event is consumed

- **WHEN** a post successfully transitions from `publishing` to `published`
- **THEN** the system emits or consumes a `content_published` event
- **AND** follow feed fanout decisions are based on that event
- **AND** failed publish attempts do not enter follower inboxes or author post sets

#### Scenario: Normal author publishes

- **WHEN** an author with fewer than 10,000 followers publishes
- **THEN** the system pushes the post into follower inbox ZSETs

#### Scenario: Large author publishes

- **WHEN** an author with 10,000 to 500,000 followers publishes
- **THEN** the system records the post in the author posts ZSET
- **AND** users pull these posts while reading their follow feed

#### Scenario: Super large author publishes

- **WHEN** an author has at least 500,000 followers
- **THEN** the system does not fanout push to follower inboxes
- **AND** the post is available through pull, hot, and recommendation sources

### Requirement: Home feed SHALL mix multiple sources

The home feed SHALL combine follow feed, recommendation candidates, and hot fallback content.

#### Scenario: Build home feed

- **WHEN** a user requests home feed
- **THEN** the system fetches local follow candidates
- **AND** fetches recommendation candidates through the adapter
- **AND** mixes, deduplicates, filters, and hydrates the final result
