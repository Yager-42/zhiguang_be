# CONTEXT — zhiguang glossary

A glossary of domain terms. No implementation details — for those, see `openspec/` and `docs/superpowers/`.

## Feed / Timeline

- **inbox** — per-follower timeline of posts pushed to that user (the "push" half of the follow feed). Backed by Cassandra `feed_inbox` (+ Redis cache-aside). Not the same as the MySQL transactional outbox.
- **author_feed** — per-author recent-posts list, the "pull" source that followers of a large author read from. Backed by Cassandra `feed_author_feed`. This term **deliberately replaces the overloaded word "outbox" on the feed side** to avoid collision with the table below.
- **outbox (transactional)** — the MySQL `outbox` table (`aggregate_type`/`aggregate_id`/`type`/`payload`/`created_at`) used for reliable event publishing; `content_published` is written here, then Canal CDC bridges the row to the `canal-outbox` Kafka topic. Unrelated to the feed inbox/author_feed.

## Post lifecycle & visibility

- **status** — `draft` | `publishing` | `published` | `publish_failed` | `deleted`. The follow feed and public feed include only `published` rows.
- **visible** — `public` | `followers` | `school` | `private` | `unlisted`. The follow timeline shows `published` posts with `visible ∈ {public, followers}`; `school` requires a viewer-scope check; `private` and `unlisted` are excluded from feeds.

## Author tiers (fanout)

- **normal author** — `< threshold` followers (default 10000): publish is pushed into every follower's inbox.
- **large author** — `≥ threshold` followers: publish is written only to `author_feed`; followers pull at read time (cached).
