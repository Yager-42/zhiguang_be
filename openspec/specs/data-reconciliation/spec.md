# data-reconciliation Specification

## Purpose
TBD - created by archiving change add-data-reconciliation. Update Purpose after archive.
## Requirements
### Requirement: Reconciliation SHALL cover fact-to-derived links

The system SHALL detect and repair inconsistencies between fact stores and derived stores.

#### Scenario: Missing search index

- **WHEN** a post is `published` in MySQL and has text in Cassandra
- **AND** the ES document is missing
- **THEN** reconciliation creates or refreshes the ES document

#### Scenario: Missing comment text

- **WHEN** MySQL has comment metadata
- **AND** Cassandra text is missing
- **THEN** reconciliation records an error or schedules a repair if a source event is available

### Requirement: Reconciliation SHALL use task state machine

Each repair unit SHALL be represented by a task with explicit state.

#### Scenario: Task succeeds

- **WHEN** a pending task is executed successfully
- **THEN** its state transitions to `succeeded`
- **AND** execution metadata is recorded

#### Scenario: Task exhausts retries

- **WHEN** a task fails beyond its retry limit
- **THEN** its state transitions to `dead`
- **AND** an error log is recorded

### Requirement: Reconciliation SHALL support multiple triggers

The system SHALL support scheduled scans, failure-generated tasks, manual reruns, and startup repair.

#### Scenario: Derived task fails

- **WHEN** a publish-derived ES indexing step fails after the post has reached `published`
- **THEN** a reconciliation task is created for ES indexing

#### Scenario: Publishing attempt is stuck

- **WHEN** a post remains in `publishing` beyond the configured timeout
- **THEN** reconciliation or startup repair marks the related attempt as failed or schedules operator-visible retry work
- **AND** it does not publish the post without rerunning the critical publish flow

#### Scenario: Manual rerun

- **WHEN** an operator requests rerun for a post
- **THEN** the system schedules eligible reconciliation tasks for that post

### Requirement: Reconciliation SHALL not require realtime consistency

The system SHALL repair inconsistencies eventually and SHALL NOT block normal user flows unless a critical fact write fails.

#### Scenario: Gorse feedback delivery fails

- **WHEN** recommendation feedback delivery fails
- **THEN** the user action remains accepted
- **AND** feedback delivery is retried through reconciliation

