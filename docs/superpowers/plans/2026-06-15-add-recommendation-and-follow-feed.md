# Recommendation and Follow Feed Implementation Plan

> **For agentic workers:** REQUIRED: Use `superpowers:subagent-driven-development` if subagents are available, or `superpowers:executing-plans` in the current session. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add adapter-based Gorse recommendations, local follow feed fanout, and a mixed home feed that fills from follow candidates, recommendation candidates, and hot fallback.

**Architecture:** Recommendation is behind `RecommendationEngine`. Follow feed is local Redis ZSet state. Published content and feedback are consumed asynchronously; failures create reconciliation tasks when that service is available.

**Tech Stack:** Java 21, Spring Boot 3.2.4, Redis ZSet, Kafka, RestTemplate, Gorse, MyBatis, JUnit 5, Maven.

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

Prerequisites: `align-publish-relation-architecture` and `add-comment-system` complete.

## Command Setup

Run before Maven commands:

```powershell
$mvn = Join-Path $env:USERPROFILE 'Desktop\文档\.codex\tools\apache-maven-3.9.6\bin\mvn.cmd'
Test-Path $mvn
```

Expected: `True`.

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
- Test: `src/test/java/com/tongji/recommendation/*`

## Task 1: Configuration and Gorse Adapter

- [ ] Add `recommendation.gorse.endpoint`, `api-key`, `timeout-ms`, and `enabled` config. Default `enabled=false`.
- [ ] Add `feed.fanout.large-author-threshold`, `super-large-author-threshold`, and `inbox-max-size` config; use explicit defaults such as `10000`, `500000`, and a bounded inbox size.
- [ ] Add Gorse to `docker-compose.yml` as optional local infrastructure.
- [ ] Define `RecommendationEngine` and `RecommendationCandidate(contentId, score, reason, source)`.
- [ ] Implement `GorseClient` inside `com.tongji.recommendation.gorse`.
- [ ] Implement `GorseRecommendationAdapter`; it returns candidates only, never full feed cards.
- [ ] Add tests for enabled Gorse, disabled fallback, and unavailable fallback.
- [ ] Run `& $mvn -Dtest=*RecommendationAdapter* test`.

## Task 2: Follow Feed Redis Structures

**Files:**
- Create: `src/main/java/com/tongji/recommendation/feed/FollowFeedService.java`
- Create: `src/main/java/com/tongji/recommendation/feed/FollowFeedServiceImpl.java`
- Modify: `src/main/java/com/tongji/counter/service/UserCounterService.java`
- Modify: `src/main/java/com/tongji/counter/service/impl/UserCounterServiceImpl.java`

- [ ] Add a way to read follower count for author classification.
- [ ] Implement `feed:inbox:{userId}` ZSet with max-size trim and no TTL.
- [ ] Implement `feed:author:posts:{authorId}` ZSet with max-size trim and no TTL.
- [ ] For normal authors under threshold, push to all followers from local relation data.
- [ ] For large authors, write author posts only.
- [ ] For super-large authors, never push to follower inboxes.
- [ ] For super-large authors, write `feed:author:posts:{authorId}` or an explicitly equivalent pull index so followers can pull the content while reading follow feed; keep the same content eligible for hot and recommendation sources.
- [ ] Do not implement active-follower priority in v1; current design says skip.
- [ ] Add tests for normal author push, large author pull via author posts, and super-large author no-push plus pull/hot/recommendation availability.
- [ ] Run `& $mvn -Dtest=*FollowFeed* test`.

## Task 3: Event Consumers

**Files:**
- Create: `src/main/java/com/tongji/recommendation/consumer/RecommendationContentConsumer.java`
- Create: `src/main/java/com/tongji/recommendation/consumer/RecommendationFeedbackConsumer.java`

- [ ] Consume `content_published` events only after successful publish.
- [ ] Upsert Gorse item asynchronously; on failure, call `ReconciliationService.createTaskIfAbsent` when available.
- [ ] Feed fanout uses the same successful publish event.
- [ ] Consume comment feedback and counter feedback events.
- [ ] Convert likes, favorites, comments, and follows into Gorse feedback types.
- [ ] Consume user profile change events and sync user snapshot/profile fields to Gorse, matching active task 2.3.
- [ ] Add tests for user profile update sync to Gorse. Do not mark `openspec/changes/add-recommendation-and-follow-feed/tasks.md` item 2.3 complete until this is implemented or explicitly clarified in OpenSpec.
- [ ] Keep failures out of the user flow.
- [ ] Run `& $mvn -Dtest=*Recommendation*Consumer* test`.

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
- [ ] Run `& $mvn -Dtest=*HomeFeedMixing* test`.

## Task 5: API Migration

**Files:**
- Modify: `src/main/java/com/tongji/knowpost/api/KnowPostController.java`

- [ ] Replace current `/api/v1/knowposts/feed` implementation for authenticated users with mixed home feed.
- [ ] Keep anonymous users on hot/public feed fallback.
- [ ] Add `/api/v1/knowposts/feed/follow` for pure follow feed.
- [ ] Keep backward-compatible response type `FeedPageResponse`.
- [ ] Run controller tests.

## Task 6: Verification and OpenSpec Closure

- [ ] Add verification tests for adapter fallback, normal/large/super-large fanout, mixing dedupe, and visibility filter.
- [ ] Run `& $mvn -Dtest="*Recommendation*,*FollowFeed*,*HomeFeed*" test`.
- [ ] Run `& $mvn test`.
- [ ] Run `openspec status --change "add-recommendation-and-follow-feed" --json`.
- [ ] Run `openspec validate add-recommendation-and-follow-feed --strict` if supported.
- [ ] Mark completed checkboxes in `openspec/changes/add-recommendation-and-follow-feed/tasks.md` only after evidence exists.
