# Publish Pipeline Specification

## ADDED Requirements

### Requirement: Publish SHALL be attempt-based

Publishing a post SHALL create a publish attempt and move the post into `publishing`.

#### Scenario: Submit publish request

- **WHEN** the author publishes a draft
- **THEN** the system creates a `publish_attempt`
- **AND** moves the post from `draft` to `publishing`
- **AND** returns `202 Accepted` with `attemptId`

### Requirement: The pipeline SHALL validate critical inputs before publishing

The pipeline SHALL validate permissions, state, text, media references, and object availability before marking content as published.

#### Scenario: Missing text

- **WHEN** Cassandra text for the post is missing
- **THEN** the attempt fails
- **AND** the post enters `publish_failed`

#### Scenario: Missing media object

- **WHEN** a referenced media object does not exist in MinIO
- **THEN** the attempt fails
- **AND** the post is not marked `published`

### Requirement: Official publish SHALL be an atomic DB transition

The system SHALL mark a post as `published` only after critical validation succeeds.

#### Scenario: Critical validation succeeds

- **WHEN** all critical validation passes
- **THEN** the system writes `publish_time`
- **AND** associates the post with the successful `attemptId`
- **AND** changes status to `published`

### Requirement: Derived task failures SHALL not roll back published content

Failures in ES, RAG, Feed cache, counter initialization, or recommendation event delivery SHALL create retry or reconciliation work.

#### Scenario: ES indexing fails

- **WHEN** the post has already been marked `published`
- **AND** ES indexing fails
- **THEN** the post remains `published`
- **AND** a compensation task is recorded

### Requirement: The pipeline SHALL expose status

Clients and operators SHALL be able to inspect publish attempt status.

#### Scenario: Query publish status

- **WHEN** a client queries an attempt
- **THEN** the system returns current status, failed step, and retry eligibility
