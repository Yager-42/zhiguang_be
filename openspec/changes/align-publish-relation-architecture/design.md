# Design: align-publish-relation-architecture

## Context

`11.pdf` proposes a high-concurrency layered architecture with explicit entry, manager, helper, DAO/DAL, async infrastructure, idempotency, degradation, isolation, and compensation boundaries. The current publish and relation code is still service-centric: controllers call `KnowPostService` and `RelationService`, while the service implementations mix orchestration, transaction transitions, cache changes, Outbox writes, dependency calls, and Redis/Lua behavior.

This change aligns the publish and relation chains to that architecture without implementing the broader product roadmap. It focuses on publish and relation request paths, event-consumption paths, executor isolation, idempotency, and resilience. It does not merge the existing Cassandra, Leaf, recommendation, comment, reconciliation, or microservice changes into this change.

## Goals / Non-Goals

**Goals:**

- Introduce an explicit Manager orchestration layer for publish and relation chains.
- Make controllers depend on managers directly for publish and relation use cases.
- Migrate or remove the old publish/relation service entry abstractions so there is not a duplicate orchestration layer.
- Change publish to a true asynchronous attempt-based API using `202 Accepted`, `publishAttemptId`, status query, retry, and client idempotency.
- Keep relation operations idempotent through natural keys and state transitions, without requiring a client idempotency key.
- Isolate execution resources by chain: publish, relation event handling, Canal/Outbox consumption, and reconciliation.
- Add Sentinel-backed resilience through local guard interfaces rather than binding managers to Sentinel annotations.

**Non-Goals:**

- Do not refactor the count chain in this change.
- Do not split the application into microservices.
- Do not require Nacos or a dynamic configuration center.
- Do not implement Cassandra text storage, Leaf IDs, comment, recommendation, or reconciliation capabilities here; this change only creates compatible boundaries.
- Do not make every derived publish task a synchronous blocker.

## Decisions

### 1. Controller -> Manager -> Helper/DAO/Publisher/Client

Publish and relation controllers will call managers directly. Managers own use-case orchestration, request-level idempotency, state decisions, and fallback behavior. Helpers, DAOs, publishers, and clients own narrower technical work such as validation, persistence, event publication, cache updates, and external dependency calls.

Alternative considered: keep `KnowPostService` and `RelationService` and add managers behind them. That keeps API compatibility inside the codebase but creates two orchestration layers with overlapping responsibilities. This change chooses a breaking migration because the user explicitly allowed breaking changes and the architecture is cleaner when only one layer owns orchestration.

### 2. Publish becomes attempt-based and asynchronous at the API boundary

Publish will require a client-provided `idempotentKey` and return `202 Accepted` with `publishAttemptId`. `202 Accepted` means the request has been accepted into the publish state machine; it does not mean the post has already been published.

The synchronous request path owns lightweight validation, idempotency lookup, publish attempt creation or reuse, and a CAS state transition such as `draft -> publishing` or retryable `publish_failed -> publishing`. The critical publish flow then runs asynchronously on the publish executor. It validates and persists critical facts, invokes enabled helpers or clients such as media validation and text storage, and eventually marks the attempt as `succeeded` with the post `published` or marks the attempt as `failed` with the post `publish_failed`.

Clients can query status and retry failed attempts. The manager maps repeated initial requests with the same author, post, and idempotency key to the same publish attempt instead of creating duplicate work. In v1, explicit retry reuses the original failed attempt row, increments `retry_count`, clears retryable failure fields as needed, and moves that same attempt back to `publishing`. It does not create a new attempt for the same idempotency key, and `retry_of_attempt_id` is not used in v1.

Alternative considered: keep `204 No Content`. That hides the actual processing state from clients and makes retry semantics ambiguous. The attempt model provides a stable contract for status, retry, and compensation.

### 3. Relation keeps natural idempotency

Follow and unfollow do not require client idempotency keys. The relation manager will derive idempotency from `fromUserId`, `toUserId`, `action`, current relation state, and database constraints. Repeated follow or unfollow calls return the current valid outcome without duplicating side effects.

Alternative considered: require an idempotency key for relation writes as well. That adds client burden without much value because relation writes already have a compact natural key and state-machine semantics.

### 4. Chain-level executor isolation

The current global `taskExecutor` is not sufficient for high-concurrency chains. This change introduces named executors for publish work, relation event processing, Canal/Outbox bridge/consumer work, and reconciliation scheduling. Queue sizes, thread names, rejection policy, and monitoring will be configured separately per chain.

Alternative considered: tune the existing global executor. That is simpler, but one slow chain can still exhaust the shared pool and degrade unrelated chains.

### 5. Sentinel is hidden behind local guard interfaces

Sentinel will be used for rate limiting, circuit breaking, degradation, and fallback. Application code will depend on local interfaces such as `ResilienceGuard` or `DegradeGuard`; Sentinel-specific details live in infrastructure adapters and configuration. Managers may select guarded operations and fallback behavior, but they do not depend directly on Sentinel annotations as the primary abstraction.

Alternative considered: use Resilience4j annotations directly in managers. Resilience4j is common, but this project will use Sentinel per the current decision. Hiding Sentinel behind local guards keeps future replacement possible and keeps business orchestration readable.

### 6. Critical facts and derivative tasks have different failure semantics

Critical publish facts include authorization, valid draft state, idempotency ownership, core persistence transitions, enabled text/media fact-store writes, and durable event/attempt records. Failures there fail the publish attempt and prevent `publishing -> published`. Derived tasks such as search indexing, RAG preparation, feed invalidation, and other eventual work must not roll back already-published content; they must create retry or reconciliation work.

Alternative considered: make all publish side effects part of one transaction. That would simplify success/failure reporting but couples slow or unreliable dependencies to the core publish path.

## Risks / Trade-offs

- [Risk] Breaking controller/service wiring can affect existing tests and clients. -> Mitigation: update controller tests and API docs with the new `202 + publishAttemptId` contract.
- [Risk] Managers can become oversized if helpers are not extracted. -> Mitigation: tasks require helpers/DAOs/publishers/clients with narrow responsibilities and focused tests.
- [Risk] Sentinel rules can be misclassified and degrade business validation errors. -> Mitigation: guard adapters must distinguish system failures from expected business exceptions.
- [Risk] Executor isolation adds configuration and operational overhead. -> Mitigation: start with conservative defaults, explicit bean names, thread name prefixes, and metrics visibility.
- [Risk] Publish idempotency storage can conflict with retry semantics. -> Mitigation: bind idempotency and retry to the original publish attempt row in v1; retry only increments `retry_count` and reuses the same `publishAttemptId`.
- [Risk] This architecture change overlaps with other OpenSpec changes. -> Mitigation: keep this change limited to layering, contracts, resilience, and isolation; leave feature-specific data stores and consumers to their own changes.

## Migration Plan

1. Add manager, helper, DAO/publisher/client, resilience, and executor packages without changing external behavior.
2. Add publish attempt persistence, idempotency contracts, and post status fields for `publishing` and `publish_failed`.
3. Switch publish APIs to return `202 Accepted` after attempt creation/reuse and state-machine acceptance, not after final publication.
4. Move publish and relation orchestration from service implementations into managers.
5. Update controllers to depend on managers directly.
6. Remove or reduce `KnowPostService` and `RelationService` so they no longer duplicate manager responsibilities.
7. Wire named executors into Canal/Outbox, relation event, publish, and reconciliation paths.
8. Add Sentinel adapter configuration behind guard interfaces.
9. Remove obsolete service tests or rewrite them as manager/helper tests.

Rollback is a code rollback because this is an intentionally breaking architecture migration. If partial deployment is required, keep controller routing behind a temporary feature flag until manager parity is verified.

## Open Questions

- None for this OpenSpec stage. The chosen direction is to use Sentinel, managers as the single orchestration layer, and breaking controller wiring.
