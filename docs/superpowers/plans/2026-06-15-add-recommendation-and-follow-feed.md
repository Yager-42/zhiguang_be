# Recommendation and Follow Feed Implementation Plan

> **For agentic workers:** Use `superpowers:subagent-driven-development` only when the user/environment has authorized subagents. Otherwise execute this single plan in the current session with `superpowers:executing-plans`. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add adapter-based Gorse recommendations, local follow feed fanout, and a mixed home feed that fills from follow candidates, recommendation candidates, and hot fallback.

**Architecture:** Recommendation is behind `RecommendationEngine`. Follow feed is a Cassandra-persistent + Redis-cache-aside timeline (`feed_inbox` / `feed_author_feed`, TWCS, 30-day TTL); fanout is driven by `content_published` via Canal CDC (`canal-outbox`). Published content and feedback are consumed asynchronously; failures rely on Kafka redelivery (at-least-once) + idempotent upsert (no reconciliation_task).

**Authority note:** This plan follows active `openspec/changes/add-recommendation-and-follow-feed/{proposal,design,tasks}` and its delta spec; no `reconciliation_task` or `ReconciliationService` in this change per active tasks.

**Tech Stack:** Java 21, Spring Boot 3.2.4, Cassandra driver `CqlSession` (direct prepared statements for feed tables), Redis (cache-aside), Kafka, Canal CDC, RestTemplate, Gorse, MyBatis, JUnit 5, Maven.

---

## Required Context

Read before editing:

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

Run Maven commands from the repo root for the current checkout; do not hardcode a machine-specific path.

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
- Modify: `src/main/java/com/tongji/knowpost/api/dto/FeedPageResponse.java`
- Modify: `src/main/java/com/tongji/knowpost/mapper/KnowPostMapper.java`
- Modify: `src/main/resources/mapper/KnowPostMapper.xml`
- Modify: `src/main/java/com/tongji/counter/service/UserCounterService.java`
- Modify: `src/main/java/com/tongji/profile/service/impl/ProfileServiceImpl.java`
- Create: `src/main/java/com/tongji/profile/event/*`
- Test: `src/test/java/com/tongji/recommendation/*`

## Task 1: Configuration and Gorse Adapter

- [ ] Add `recommendation.gorse.endpoint`, `api-key`, `timeout-ms`, and `enabled` config. Default `enabled=false`.
- [ ] Add `feed.home.mixed-enabled` config for `/api/v1/knowposts/feed` rollout. Default `false`; this flag controls home-feed replacement independently from `recommendation.gorse.enabled`.
- [ ] Add `feed.fanout.push-pull-threshold` (default 10000), `feed.inbox.ttl-days` (30), and `feed.cache.timeline-ttl-seconds` (300) / `author-head-ttl-seconds` (120) config. Two-tier fanout (prior `super-large-author-threshold` removed; `logical-cap` dropped - advisory, no enforcement).
- [ ] Add Gorse to `docker-compose.yml` as optional local infrastructure.
- [ ] Define `RecommendationEngine` and `RecommendationCandidate(contentId, source)` (score/reason unused by the priority-fill mixer).
- [ ] Implement `GorseClient` inside `com.tongji.recommendation.gorse`.
- [ ] Implement `GorseRecommendationAdapter`; it returns candidates only, never full feed cards.
- [ ] Add tests for enabled Gorse, disabled fallback, and unavailable fallback.
- [ ] Run `mvn -Dtest=*RecommendationAdapter* test`.

## Task 2: Follow Feed Storage (Cassandra + Redis cache-aside)

**Files:**
- Modify: `db/cassandra/init.cql` (verify existing `feed_inbox`, `feed_author_feed`; patch schema drift only)
- Create: `src/main/java/com/tongji/recommendation/feed/FollowFeedService.java`
- Create: `src/main/java/com/tongji/recommendation/feed/FollowFeedServiceImpl.java`
- Create: `src/main/java/com/tongji/recommendation/feed/TimelineDispatcher.java`
- Create: `src/main/java/com/tongji/recommendation/feed/TimelineExecutor.java`
- Use direct `CqlSession` prepared statements/async writes for `feed_inbox`/`feed_author_feed`; do not create Spring Data Cassandra entities or repositories for feed tables.
- Modify: `src/main/java/com/tongji/relation/mapper/RelationMapper.java` (internal fanout keyset scan)
- Modify: `src/main/java/com/tongji/relation/mapper/RelationMapper.xml` (keyset follower paging)

- [ ] Verify `feed_inbox`/`feed_author_feed` in `init.cql`; patch only if schema drift exists. Required shape: TWCS (`DAYS`/`1`), `default_time_to_live=2592000`, `gc_grace_seconds=0`, `CLUSTERING ORDER BY (publish_ts DESC, content_id DESC)`.
- [ ] Add follower-count read for author classification (MySQL `countFollowerActive`, queried once per publish - no cache).
- [ ] `TimelineDispatcher`: `@KafkaListener(topics=canal-outbox, groupId=feed-timeline-consumer)`, filter `eventType=content_published`, classify author by soft-hint follower count, dispatch to executor.
- [ ] `TimelineExecutor` via direct `CqlSession` prepared statements + `executeAsync` (inflight <= 256, per-message deadline, no-ack on any failure): normal authors keyset-page followers and async `INSERT feed_inbox` only; large authors `INSERT feed_author_feed` only. No cross-write.
- [ ] `publish_ts` captured once from the event (ms), reused verbatim on replay; `content_id` snowflake as DESC tiebreaker; idempotent upsert (PK includes content_id).
- [ ] Read path: `feed:timeline:{userId}` cache (no single-flight; per-user key only); on miss slice `feed_inbox` + followed large-author `feed:author:{authorId}:head` (else `feed_author_feed`), each slice `LIMIT 20`; `feed:author:{authorId}:head` is shared for large authors and must be single-populated/shared to absorb hot read amplification; merge by `(publish_ts DESC, content_id DESC)`, dedup by `content_id`; hydrate `feed:item:{id}` (existing fragment cache, miss -> Cassandra text + MySQL meta); read-time repair filter `status='published' AND visible IN ('public','followers')` (`school` out of follow-feed scope); cursor `(publish_ts, content_id)`.
- [ ] Add read-path followed-author discovery such as `RelationMapper.listFollowedAuthorsForFeed(userId, cursorCreatedAtNullable, cursorToUserIdNullable, limit)` plus a service/helper loop. Use MySQL relation data, with cache only if an existing relation cache fits, to enumerate followed author IDs for the reader and classify/filter them to large authors before pulling `feed:author:{authorId}:head` / `feed_author_feed`. Keep public relation list APIs unchanged unless an existing caller requires it.
- [ ] No background DELETE on feed tables; deletes are read-time repair + TTL expiry only.
- [ ] Add internal fanout scan methods such as `RelationMapper.listFollowersForFanout(toUserId, cursorCreatedAtNullable, cursorFromUserIdNullable, limit)` and matching service/helper loop. Do not change public relation list APIs unless a caller requires it.
- [ ] Follower fanout SQL must keyset on `(created_at, from_user_id)` with a deterministic tie-breaker: `WHERE to_user_id=#{toUserId} AND rel_status=1 AND (#{cursorCreatedAt} IS NULL OR created_at < #{cursorCreatedAt} OR (created_at = #{cursorCreatedAt} AND from_user_id < #{cursorFromUserId})) ORDER BY created_at DESC, from_user_id DESC LIMIT #{limit}`.
- [ ] Tests: normal author writes `feed_inbox` only and does not write `feed_author_feed`; large author writes `feed_author_feed` only and is pulled separately; user follows only a large author and receives that author's post from `feed:author:{authorId}:head` / `feed_author_feed` despite empty inbox; idempotent replay (no dup rows); cache miss -> Cassandra; read-time repair filtering deleted/private/unlisted; cursor stability across same-second posts.
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
- [ ] Consume feedback from `counter-events` for knowpost likes/favorites, `comment-feedback` for comments, and `canal-outbox` relation events `FollowCreated`/`FollowCanceled` for follows.
- [ ] Convert likes, favorites, comments, and follows from those sources into Gorse feedback types.
- [ ] Produce `user_profile_updated` events from profile update and avatar update paths in `ProfileServiceImpl`, through a small profile event producer.
- [ ] Consume `user_profile_updated` events and sync user snapshot/profile fields to Gorse, matching active task 2.3.
- [ ] Add tests for user profile update sync to Gorse. Do not mark `openspec/changes/add-recommendation-and-follow-feed/tasks.md` item 2.3 complete until this is implemented or explicitly clarified in OpenSpec.
- [ ] Keep failures out of the user flow.
- [ ] Run `mvn -Dtest=*Recommendation*Consumer* test`.

## Task 4: Feed Hydration and Mixing

**Files:**
- Modify: `src/main/java/com/tongji/knowpost/service/KnowPostFeedService.java`
- Modify: `src/main/java/com/tongji/knowpost/service/impl/KnowPostFeedServiceImpl.java`
- Modify: `src/main/java/com/tongji/knowpost/api/dto/FeedPageResponse.java`
- Modify: `src/main/java/com/tongji/knowpost/mapper/KnowPostMapper.java`
- Modify: `src/main/resources/mapper/KnowPostMapper.xml`
- Create: `src/main/java/com/tongji/recommendation/HomeFeedMixingService.java`

- [ ] Add `getFeedByIds(List<Long> ids, Long currentUserIdNullable, FeedVisibilityScope scope)` or equivalent source-aware hydration to hydrate selected post IDs.
- [ ] Add mapper SQL to fetch published posts by IDs with source-specific visibility: follow candidates allow `visible IN ('public','followers')`; recommendation and hot fallback candidates remain public-only. Preserve input order in service code.
- [ ] Extend existing `FeedPageResponse` with nullable `String nextCursor` and keep a backward-compatible constructor/factory that defaults it to `null`.
- [ ] Implement home feed priority fill: follow candidates, recommendation candidates, hot fallback.
- [ ] Deduplicate by post ID before hydration while retaining the most permissive eligible source (`follow` beats recommendation/hot for visibility).
- [ ] Let `KnowPostFeedService` enforce visibility/deleted filtering during hydration; when filtering drops candidates, continue filling from remaining sources until the fixed mixed home target of 20 items is met or all sources are exhausted.
- [ ] Add tests for fill order, deduplication, source-specific visibility filtering (`followers` allowed only from follow candidates), and refill after hidden/deleted candidates are filtered.
- [ ] Run `mvn -Dtest=*HomeFeedMixing* test`.

## Task 5: API Migration

**Files:**
- Modify: `src/main/java/com/tongji/knowpost/api/KnowPostController.java`

- [ ] Replace current `/api/v1/knowposts/feed` implementation for authenticated users with mixed home feed only when `feed.home.mixed-enabled=true`; this mixed path returns the fixed home feed target of 20 items and ignores or clamps existing `page`/`size` params as appropriate without implying variable page size.
- [ ] When `feed.home.mixed-enabled=false`, keep the current public/hot fallback behavior with existing `page`/`size` compatibility for rollback.
- [ ] Keep anonymous users on hot/public feed fallback with existing `page`/`size` compatibility regardless of `feed.home.mixed-enabled`.
- [ ] Add `/api/v1/knowposts/feed/follow` for pure follow feed with cursor request support and stable `(publish_ts, content_id)` pagination. Cursor query param and `FeedPageResponse.nextCursor` use the same nullable string format: `<publishTsMillis>:<contentId>`.
- [ ] Reject invalid cursor strings at the controller/service boundary with `BAD_REQUEST`; add tests for cursor encoding, decoding, and invalid cursor rejection.
- [ ] Mixed `/api/v1/knowposts/feed` may leave `nextCursor=null` unless a later product decision defines mixed-feed cursor semantics.
- [ ] Keep response type `FeedPageResponse`; pure follow feed sets nullable `nextCursor`, while page-based responses leave it `null`.
- [ ] Controller tests must assert `/feed` with `feed.home.mixed-enabled=true` uses mixed home feed for authenticated users with fixed 20-item target/fill behavior, `/feed` with `feed.home.mixed-enabled=false` keeps old public/hot `page`/`size` behavior, anonymous `/feed` keeps public/hot `page`/`size` fallback, and follow-feed `nextCursor` is returned and can be used for the next page without offset pagination.
- [ ] Run controller tests.

## Task 6: Verification and OpenSpec Closure

- [ ] Add verification tests for adapter fallback, normal/large fanout (two-tier), mixing dedupe, and visibility filter.
- [ ] Benchmark `SELECT ... FROM feed_inbox WHERE user_id=? LIMIT 20` at 30-day fill (RF=1) and record p99 - validates the `feed:timeline` cache-aside decision (keep if p99 >= ~8ms; if lower, revisit simplifying to direct Cassandra + author head cache).
- [ ] Run `mvn -Dtest="*Recommendation*,*FollowFeed*,*HomeFeed*" test`.
- [ ] Run `mvn test`.
- [ ] Run `openspec status --change "add-recommendation-and-follow-feed" --json`.
- [ ] Run `openspec validate add-recommendation-and-follow-feed --strict` if supported.
- [ ] Mark completed checkboxes in `openspec/changes/add-recommendation-and-follow-feed/tasks.md` only after evidence exists.
