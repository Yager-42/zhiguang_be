# Comment System Specification

## ADDED Requirements

### Requirement: Comments SHALL support two levels

The system SHALL support top-level comments and replies to top-level comments.

#### Scenario: Create top-level comment

- **WHEN** a user comments on a published post
- **THEN** the system accepts the request as a top-level comment
- **AND** associates it with the target `post_id`

#### Scenario: Create reply

- **WHEN** a user replies to a top-level comment
- **THEN** the system stores the reply under the top-level `root_id`
- **AND** does not create deeper nesting

### Requirement: Comment writes SHALL be asynchronous

The comment publish API SHALL return acceptance before durable persistence completes.

#### Scenario: Submit comment

- **WHEN** a user submits a comment
- **THEN** the API returns `202 Accepted`
- **AND** returns `clientRequestId` and `pendingCommentId`
- **AND** the client may display the comment as sending

#### Scenario: Async persistence succeeds

- **WHEN** the comment consumer persists the comment
- **THEN** the status query reports success
- **AND** comment list APIs can return the comment

#### Scenario: Async persistence fails

- **WHEN** the comment consumer exhausts retries
- **THEN** the status query reports failed
- **AND** the failure is recorded for compensation or user retry

### Requirement: Comment text SHALL be stored in Cassandra

The system SHALL store comment and reply text in Cassandra and metadata in MySQL.

#### Scenario: Read comments page

- **WHEN** the client requests comments
- **THEN** the system reads comment metadata from MySQL
- **AND** batch reads text from Cassandra by `comment_id`
- **AND** MySQL does not store a separate Cassandra `content_key`

### Requirement: Comments SHALL support soft deletion

Deleted comments SHALL preserve thread structure.

#### Scenario: Delete comment

- **WHEN** a comment owner deletes a comment
- **THEN** the system marks the comment as deleted
- **AND** replies remain addressable under the thread

### Requirement: Comments SHALL support likes and counts

The system SHALL support liking comments and maintaining comment-related counts.

#### Scenario: Like comment

- **WHEN** a user likes a comment
- **THEN** the count system records the action for entity type `comment`
- **AND** duplicate likes are idempotent
