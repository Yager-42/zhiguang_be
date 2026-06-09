# Text Storage Specification

## ADDED Requirements

### Requirement: Cassandra SHALL be the text fact store

The system SHALL use Cassandra as the fact store for published post text and comment/reply text.

#### Scenario: Save post text

- **WHEN** a user submits textual post content
- **THEN** the system writes the text to Cassandra using `post_id` as the primary lookup key
- **AND** MySQL stores only metadata and the Cassandra content key

#### Scenario: Save comment text

- **WHEN** a comment or reply is accepted for persistence
- **THEN** the system writes the text to Cassandra using `comment_id` as the primary lookup key
- **AND** MySQL stores comment metadata and the Cassandra content key

### Requirement: Media SHALL remain in object storage

The system SHALL continue to store images, videos, and attachments in MinIO.

#### Scenario: Upload media

- **WHEN** a user uploads media for a post
- **THEN** the system uses the existing MinIO presign flow
- **AND** Cassandra is not used for binary media

### Requirement: Cassandra SHALL not serve list queries

Cassandra SHALL only support ID-based text lookup in the first version.

#### Scenario: Query comments page

- **WHEN** a client requests a page of comments
- **THEN** the system queries MySQL/Redis for comment metadata and IDs
- **AND** batch reads comment text from Cassandra by `comment_id`

### Requirement: Derived indexes SHALL be rebuildable

Elasticsearch and vector indexes SHALL be treated as derived data rebuildable from MySQL and Cassandra.

#### Scenario: Rebuild search index

- **WHEN** a post search document is missing or stale
- **THEN** the system reads metadata from MySQL and text from Cassandra
- **AND** rebuilds the Elasticsearch document
