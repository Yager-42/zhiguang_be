# recommendation-feed Specification

## Purpose
TBD - created by archiving change add-recommendation-and-follow-feed. Update Purpose after archive.
## Requirements
### Requirement: Recommendation engine SHALL be adapter-based

The application SHALL access Gorse through a `RecommendationEngine` interface.

#### Scenario: Fetch recommended candidates

- **WHEN** the feed service requests recommendations for a user
- **THEN** it calls `RecommendationEngine`
- **AND** receives candidate content IDs and source labels

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

#### Scenario: Published content event is consumed via Canal CDC

- **WHEN** a post successfully transitions from `publishing` to `published`
- **THEN** the system writes a `content_published` row to the transactional outbox
- **AND** the row is bridged to the `canal-outbox` Kafka topic via Canal CDC (`canal.enabled=true`)
- **AND** follow feed fanout is driven by consuming that event
- **AND** failed publish attempts do not enter follower inboxes or author feeds

#### Scenario: Normal author publishes

- **WHEN** an author with fewer than the push/pull threshold (default 10,000) followers publishes
- **THEN** the system writes the post to every follower's `feed_inbox`
- **AND** does not write `feed_author_feed` (a followed author later reclassified as large is pulled on the read-path miss)

#### Scenario: Large author publishes

- **WHEN** an author with at least the threshold followers publishes
- **THEN** the system records the post only in `feed_author_feed`
- **AND** followers pull the post (from cache, then `feed_author_feed`) while reading their follow feed
- **AND** the post remains eligible for hot and recommendation sources

#### Scenario: Fanout retry is idempotent

- **WHEN** a `content_published` event is redelivered (at-least-once)
- **THEN** the `publish_ts` is reused verbatim from the event (never regenerated)
- **AND** no duplicate rows are created, because the primary key includes `content_id`

### Requirement: Follow feed storage SHALL be bounded and tiered

The follow feed inbox and author feed SHALL be persisted in Cassandra as derived indexes, with Redis as a cache-aside for active readers, and SHALL be bounded by time-to-live rather than unbounded RAM.

#### Scenario: Storage is bounded by TTL, not by RAM

- **WHEN** inbox or author feed rows reach their time-to-live (default 30 days)
- **THEN** they expire and are purged by TimeWindowCompactionStrategy
- **AND** no background delete or trim job runs against the feed tables

#### Scenario: Active readers are served from cache

- **WHEN** a user reads their follow feed
- **THEN** the merged result is cached in Redis with a short TTL
- **AND** the per-user timeline key is populated plainly (no single-flight; per-user keys have no herd)
- **AND** the shared `feed:author:{authorId}:head` key for large authors is single-populated to absorb hot-partition read amplification
- **AND** each read slice is bounded (`LIMIT` per source) to stay within the tombstone budget
- **AND** a cache miss reads from Cassandra, not from MySQL

### Requirement: Deleted and hidden content SHALL be excluded by read-time repair

The follow feed SHALL exclude deleted and non-feed-visible posts at read time via filtering, without synchronously deleting from the feed tables (stale rows expire via TTL).

#### Scenario: Deleted or non-feed-visible content is filtered at read

- **WHEN** a post is deleted (`status='deleted'`) or is not feed-visible (`visible` not in the follow-feed allowlist `{public, followers}`)
- **THEN** the hydrate path filters it out of the follow feed
- **AND** `visible='school'` posts are out of follow-feed scope (no viewer-scope check in this change)
- **AND** no synchronous deletion is performed against the feed tables; stale rows expire via TTL

### Requirement: Follow feed pagination SHALL use a stable waterfall cursor

The follow feed SHALL paginate using a waterfall cursor of `(publish_ts, content_id)`, both descending, and SHALL NOT use offset-based deep pagination.

#### Scenario: Cursor pagination

- **WHEN** a client paginates the follow feed
- **THEN** the system uses a cursor of `(publish_ts, content_id)`, both descending
- **AND** `publish_ts` is captured once from the event at millisecond precision and reused verbatim on replay (never `now()`)
- **AND** `content_id` (a snowflake, ms in high bits) is the authoritative tiebreaker
- **AND** offset-based deep pagination is not used

### Requirement: Home feed SHALL mix multiple sources

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

