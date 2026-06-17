# Recommendation and Follow Feed Implementation Plan

> **For agentic workers:** Use `superpowers:subagent-driven-development` only when the user/environment has authorized subagents. Otherwise execute this single plan in the current session with `superpowers:executing-plans`. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add adapter-based Gorse recommendations, local follow feed fanout, and a mixed home feed that fills from follow candidates, recommendation candidates, and hot fallback.

**Architecture:** Recommendation is behind `RecommendationEngine`. Follow feed is a Cassandra-persistent + Redis-cache-aside timeline (`feed_inbox` / `feed_author_feed`, TWCS, 30-day TTL); fanout is driven by `content_published` via Canal CDC (`canal-outbox`). Published content and feedback are consumed asynchronously; failures rely on Kafka redelivery (at-least-once) + idempotent upsert (no reconciliation_task).

**Tech Stack:** Java 21, Spring Boot 3.2.4, Spring Data Cassandra + `CqlSession`, Redis (cache-aside), Kafka, Canal CDC, RestTemplate, Gorse, MyBatis, JUnit 5, Maven.

---

## Required Context

Read before editing:

- `openspec/11pdf-integration-matrix.md`
- `openspec/changes/add-recommendation-and-follow-feed/proposal.md`
- `openspec/changes/add-recommendation-and-follow-feed/design.md`
- `openspec/changes/add-recommendation-and-follow-feed/specs/recommendation-feed/spec.md`
- `openspec/changes/add-recommendation-and-follow-feed/tasks.md`
- `src/main/java/com/tongji/knowpost/service/KnowPostFeedService.java`
- `src/main/java/com/tongji/knowpost/service/impl/KnowPostFeedServiceImpl.java`
- `src/main/java/com/tongji/relation/service/RelationService.java`
- `src/main/java/com/tongji/profile/service/impl/ProfileServiceImpl.java`

Prerequisites: `align-publish-relation-architecture` and `add-comment-system` complete.

## Command Setup

Run Maven commands from the repo root. The current workspace root is `/Volumes/lexar/revive/zhiguang_be`; on other machines, use that machine's repo root.

```bash
mvn -version
```

Windows PowerShell optional equivalent: `mvn.cmd -version` if `mvn.cmd` is on `PATH`.

## Files

- Modify: `docker-compose.yml`
- Modify: `src/main/resources/application.yml`
- Create: `src/main/java/com/tongji/recommendation/*`
- Create: `src/main/java/com/tongji/recommendation/gorse/*`
- Create: `src/main/java/com/tongji/recommendation/feed/*`
- Create: `src/main/java/com/tongji/recommendation/consumer/*`
- Modify: `src/main/java/com/tongji/knowpost/service/KnowPostFeedService.java`
- Modify: `src/main/java/com/tongji/knowpost/service/impl/KnowPostFeedServiceImpl.java`
- Modify: `src/main/java/com/tongji/knowpost/api/KnowPostController.java`
- Modify: `src/main/java/com/tongji/knowpost/mapper/KnowPostMapper.java`
- Modify: `src/main/resources/mapper/KnowPostMapper.xml`
- Modify: `src/main/java/com/tongji/counter/service/UserCounterService.java`
- Modify: `src/main/java/com/tongji/profile/service/impl/ProfileServiceImpl.java`
- Create: `src/main/java/com/tongji/profile/event/*`
- Test: `src/test/java/com/tongji/recommendation/*`

## Task 1: Configuration and Gorse Adapter

- [ ] Add `recommendation.gorse.endpoint`, `api-key`, `timeout-ms`, and `enabled` config. Default `enabled=false`.
- [ ] Add `feed.fanout.push-pull-threshold` (default 10000), `feed.inbox.ttl-days` (30), and `feed.cache.timeline-ttl-seconds` (300) / `author-head-ttl-seconds` (120) config. Two-tier fanout (prior `super-large-author-threshold` removed; `logical-cap` dropped — advisory, no enforcement).
- [ ] Add Gorse to `docker-compose.yml` as optional local infrastructure.
- [ ] Define `RecommendationEngine` and `RecommendationCandidate(contentId, source)` (score/reason unused by the priority-fill mixer).
- [ ] Implement `GorseClient` inside `com.tongji.recommendation.gorse`.
- [ ] Implement `GorseRecommendationAdapter`; it returns candidates only, never full feed cards.
- [ ] Add tests for enabled Gorse, disabled fallback, and unavailable fallback.
- [ ] Run `mvn -Dtest=*RecommendationAdapter* test`.

## Task 2: Follow Feed Storage (Cassandra + Redis cache-aside)

**Files:**
- Modify: `db/cassandra/init.cql` (add `feed_inbox`, `feed_author_feed`)
- Create: `src/main/java/com/tongji/recommendation/feed/FollowFeedService.java`
- Create: `src/main/java/com/tongji/recommendation/feed/FollowFeedServiceImpl.java`
- Create: `src/main/java/com/tongji/recommendation/feed/TimelineDispatcher.java`
- Create: `src/main/java/com/tongji/recommendation/feed/TimelineExecutor.java`
- Create: Cassandra entities for `feed_inbox`/`feed_author_feed` (compound `@PrimaryKeyClass`: 1 PARTITION + 2 CLUSTERED DESC)
- Modify: `src/main/java/com/tongji/relation/mapper/RelationMapper.xml` (keyset follower paging)

- [ ] Add `feed_inbox`/`feed_author_feed` to `init.cql`: TWCS (`DAYS`/`1`), `default_time_to_live=2592000`, `gc_grace_seconds=0`, `CLUSTERING ORDER BY (publish_ts DESC, content_id DESC)`.
- [ ] Add follower-count read for author classification (MySQL `countFollowerActive`, queried once per publish — no cache).
- [ ] `TimelineDispatcher`: `@KafkaListener(topics=canal-outbox, groupId=feed-timeline-consumer)`, filter `eventType=content_published`, classify author by soft-hint follower count, dispatch to executor.
- [ ] `TimelineExecutor` via `CqlSession.executeAsync` (inflight ≤256, per-message deadline, no-ack on any failure): normal authors keyset-page followers and async `INSERT feed_inbox` only; large authors `INSERT feed_author_feed` only. No cross-write.
- [ ] `publish_ts` captured once from the event (ms), reused verbatim on replay; `content_id` snowflake as DESC tiebreaker; idempotent upsert (PK includes content_id).
- [ ] Read path: `feed:timeline:{userId}` cache (no single-flight — per-user key); on miss slice `feed_inbox` + followed large-author `feed:author:{authorId}:head` (else `feed_author_feed`), each slice `LIMIT 20`; merge by `(publish_ts DESC, content_id DESC)`, dedup by `content_id`; hydrate `feed:item:{id}` (existing fragment cache, miss → Cassandra text + MySQL meta); read-time repair filter `status='published' AND visible∈{public,followers}` (`school` out of follow-feed scope); cursor `(publish_ts, content_id)`.
- [ ] No background DELETE on feed tables; deletes are read-time repair + TTL expiry only.
- [ ] Convert follower scan from `LIMIT/OFFSET` to keyset `(created_at, from_user_id)` in `RelationMapper.xml`.
- [ ] Tests: normal-author inbox push + author_feed fallback; large-author author_feed-only pull; idempotent replay (no dup rows); cache miss→Cassandra; read-time repair filtering deleted/private/unlisted; cursor stability across same-second posts.
- [ ] Run `mvn -Dtest=*FollowFeed* test`.

## Task 3: Event Consumers

**Files:**
- Create: `src/main/java/com/tongji/recommendation/consumer/RecommendationContentConsumer.java`
- Create: `src/main/java/com/tongji/recommendation/consumer/RecommendationFeedbackConsumer.java`
- Modify: `src/main/java/com/tongji/profile/service/impl/ProfileServiceImpl.java`
- Create: `src/main/java/com/tongji/profile/event/UserProfileUpdatedEvent.java`
- Create: `src/main/java/com/tongji/profile/event/UserProfileEventProducer.java`

- [ ] Consume `content_published` via `canal-outbox` (Canal CDC on the outbox row), filtering `eventType=content_published`, only after successful publish; `canal.enabled=true` is required (same bridge as `relation-outbox-consumer` / `search-index-consumer`).
- [ ] Upsert Gorse item asynchronously; on failure, no-ack for Kafka redelivery (idempotent). No `ReconciliationService` (does not exist yet); no `reconciliation_task` table in this change.
- [ ] Feed fanout uses the same successful publish event.
- [ ] Consume comment feedback and counter feedback events.
- [ ] Convert likes, favorites, comments, and follows into Gorse feedback types.
- [ ] Produce `user_profile_updated` events from profile update and avatar update paths in `ProfileServiceImpl`, through a small profile event producer.
- [ ] Consume `user_profile_updated` events and sync user snapshot/profile fields to Gorse, matching active task 2.3.
- [ ] Add tests for user profile update sync to Gorse. Do not mark `openspec/changes/add-recommendation-and-follow-feed/tasks.md` item 2.3 complete until this is implemented or explicitly clarified in OpenSpec.
- [ ] Keep failures out of the user flow.
- [ ] Run `mvn -Dtest=*Recommendation*Consumer* test`.

## Task 4: Feed Hydration and Mixing

**Files:**
- Modify: `src/main/java/com/tongji/knowpost/service/KnowPostFeedService.java`
- Modify: `src/main/java/com/tongji/knowpost/service/impl/KnowPostFeedServiceImpl.java`
- Modify: `src/main/java/com/tongji/knowpost/mapper/KnowPostMapper.java`
- Modify: `src/main/resources/mapper/KnowPostMapper.xml`
- Create: `src/main/java/com/tongji/recommendation/HomeFeedMixingService.java`

- [ ] Add `getFeedByIds(List<Long> ids, Long currentUserIdNullable)` to hydrate selected post IDs.
- [ ] Add mapper SQL to fetch published, public posts by IDs and preserve input order in service code.
- [ ] Use current `FeedItemResponse` and `FeedPageResponse` DTOs.
- [ ] Implement home feed priority fill: follow candidates, recommendation candidates, hot fallback.
- [ ] Deduplicate by post ID before hydration.
- [ ] Let `KnowPostFeedService` enforce visibility/deleted filtering during hydration.
- [ ] Add tests for fill order, deduplication, and visibility filtering.
- [ ] Run `mvn -Dtest=*HomeFeedMixing* test`.

## Task 5: API Migration

**Files:**
- Modify: `src/main/java/com/tongji/knowpost/api/KnowPostController.java`

- [ ] Replace current `/api/v1/knowposts/feed` implementation for authenticated users with mixed home feed.
- [ ] Keep anonymous users on hot/public feed fallback.
- [ ] Add `/api/v1/knowposts/feed/follow` for pure follow feed.
- [ ] Keep backward-compatible response type `FeedPageResponse`.
- [ ] Run controller tests.

## Task 6: Verification and OpenSpec Closure

- [ ] Add verification tests for adapter fallback, normal/large fanout (two-tier), mixing dedupe, and visibility filter.
- [ ] Benchmark `SELECT ... FROM feed_inbox WHERE user_id=? LIMIT 20` at 30-day fill (RF=1) and record p99 — validates the `feed:timeline` cache-aside decision (keep if p99 ≥ ~8ms; revisit simplification if lower).
- [ ] Run `mvn -Dtest="*Recommendation*,*FollowFeed*,*HomeFeed*" test`.
- [ ] Run `mvn test`.
- [ ] Run `openspec status --change "add-recommendation-and-follow-feed" --json`.
- [ ] Run `openspec validate add-recommendation-and-follow-feed --strict` if supported.
- [ ] Mark completed checkboxes in `openspec/changes/add-recommendation-and-follow-feed/tasks.md` only after evidence exists.
