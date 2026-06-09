# Microservice Evolution Specification

## ADDED Requirements

### Requirement: Microservice split SHALL be deferred

The system SHALL remain a modular monolith during the first implementation batch.

#### Scenario: Implement comment system

- **WHEN** the comment system is implemented
- **THEN** it remains in the current application
- **AND** it exposes clear service interfaces and events for future extraction

### Requirement: Future service boundaries SHALL be documented

The system SHALL document future service boundaries before implementation starts.

#### Scenario: Plan future split

- **WHEN** the team prepares microservice migration
- **THEN** it identifies boundaries for auth, user, content, comment, relation, counter, search, recommendation, storage, and reconciliation

### Requirement: New modules SHALL avoid unnecessary hard coupling

New modules SHALL prefer interfaces and events over direct implementation coupling where practical.

#### Scenario: Recommendation engine integration

- **WHEN** the feed service needs recommendations
- **THEN** it depends on `RecommendationEngine`
- **AND** does not directly bind to Gorse protocol outside the adapter
