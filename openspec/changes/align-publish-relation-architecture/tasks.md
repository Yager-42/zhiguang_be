# Tasks

## 1. Architecture Scaffolding

- [x] 1.1 Create publish manager package and collaborator boundaries for helpers, DAOs, publishers, and clients.
- [x] 1.2 Create relation manager package and collaborator boundaries for helpers, DAOs, publishers, and clients.
- [x] 1.3 Define shared result models for accepted publish attempts, publish status, retry eligibility, and relation write outcomes.
- [x] 1.4 Move orchestration-only responsibilities out of `KnowPostServiceImpl` and `RelationServiceImpl` into the new managers.

## 2. Publish API and Idempotency

- [x] 2.1 Add publish request DTO support for the required client `idempotentKey`.
- [x] 2.2 Add publish response DTO returning `publishAttemptId` with `202 Accepted`.
- [x] 2.3 Implement publish idempotency lookup and persistence keyed by author, post, and `idempotentKey`.
- [x] 2.4 Reject publish requests that omit `idempotentKey` before creating attempts or side effects.
- [x] 2.5 Add publish status query endpoint returning attempt status, post status, failed step, and retry eligibility.
- [x] 2.6 Add publish retry endpoint that accepts only retryable failed attempts, reuses the original attempt row, increments `retry_count`, and returns the same `publishAttemptId`.
- [x] 2.7 Add publish attempt persistence and post fields for current attempt, `publishing`, `publish_failed`, and publish failure reason.
- [x] 2.8 Ensure `202 Accepted` is returned after attempt acceptance, not after final publication.

## 3. Publish Manager Flow

- [x] 3.1 Implement request-path orchestration for lightweight validation, idempotency, original attempt creation/reuse, retry-count increment on retry, and CAS transition into `publishing`.
- [x] 3.2 Run the critical publish flow asynchronously on the publish executor after `202 Accepted`.
- [x] 3.3 Implement critical flow transitions from `publishing` to `published` or `publish_failed`.
- [x] 3.4 Separate critical publish facts from derivative publish tasks in manager flow and helper contracts.
- [x] 3.5 Ensure critical publish failures update attempt/post failure status rather than reporting successful publication.
- [x] 3.6 Ensure derivative task failures record retry or reconciliation work without rolling back published content.
- [x] 3.7 Update `KnowPostController` to depend directly on the publish manager for migrated publish use cases.
- [x] 3.8 Remove or reduce `KnowPostService` so it no longer duplicates publish manager orchestration.

## 4. Relation Manager Flow

- [x] 4.1 Implement relation manager orchestration for follow and unfollow state decisions.
- [x] 4.2 Enforce natural idempotency through actor, target, action, current state, and database constraints.
- [x] 4.3 Prevent duplicate relation side effects for repeated follow or unfollow requests.
- [x] 4.4 Move Outbox/event/cache coordination behind relation manager collaborators.
- [x] 4.5 Update `RelationController` to depend directly on the relation manager for migrated relation use cases.
- [x] 4.6 Remove or reduce `RelationService` so it no longer duplicates relation manager orchestration.

## 5. Executor Isolation

- [x] 5.1 Replace the single generic `taskExecutor` dependency with named executor beans for publish, relation event handling, Canal/Outbox consumption, and reconciliation work.
- [x] 5.2 Configure each executor with explicit thread name prefix, pool size, queue size, rejection policy, and shutdown behavior.
- [x] 5.3 Wire `CanalKafkaBridge` to the Canal/Outbox executor.
- [x] 5.4 Wire relation event consumers/processors to the relation event executor where asynchronous execution is required.
- [x] 5.5 Wire publish derivative work and reconciliation scheduling to their dedicated executors.

## 6. Sentinel-backed Resilience

- [x] 6.1 Add Sentinel dependencies and application configuration required for local rate limiting, degradation, and circuit breaking.
- [x] 6.2 Define local guard interfaces such as `ResilienceGuard` or `DegradeGuard` in a common infrastructure package.
- [x] 6.3 Implement Sentinel-backed guard adapters behind the local interfaces.
- [x] 6.4 Add failure classification so expected business exceptions are not counted as Sentinel system failures.
- [x] 6.5 Apply guarded execution to external or failure-prone publish and relation dependencies.
- [x] 6.6 Implement fallback outcomes for guarded operations according to critical versus derivative failure semantics.

## 7. Migration and Cleanup

- [x] 7.1 Update Spring wiring so publish and relation controllers use managers directly.
- [x] 7.2 Remove obsolete service interfaces or keep only non-orchestration compatibility methods with clear ownership.
- [x] 7.3 Update API documentation or examples for `202 Accepted`, `publishAttemptId`, idempotency key, status query, and retry endpoints.
- [x] 7.4 Verify no migrated publish or relation use case still has duplicate orchestration split between service and manager layers.

## 8. Verification

- [x] 8.1 Add manager tests for publish idempotency and duplicate request behavior.
- [x] 8.2 Add controller tests for publish `202 Accepted`, missing idempotency key rejection, status query, and retry.
- [x] 8.3 Add publish manager tests proving `202 Accepted` does not require final publication and that critical failure moves the attempt/post to failed states.
- [x] 8.4 Add relation manager tests for duplicate follow and duplicate unfollow natural idempotency.
- [x] 8.5 Add executor wiring tests or context assertions for named executor injection into Canal/Outbox and migrated chains.
- [x] 8.6 Add resilience guard tests for Sentinel fallback behavior and business-exception classification.
- [x] 8.7 Run the project build and relevant test suite for the migrated modules.
