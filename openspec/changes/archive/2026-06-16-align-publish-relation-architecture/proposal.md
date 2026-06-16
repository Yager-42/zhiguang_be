## Why

`11.pdf` describes a layered, high-concurrency architecture with clear manager orchestration, isolation, idempotency, fallback, and compensation boundaries. The current publish and relation paths mix orchestration, transactions, cache work, Outbox writes, and dependency calls inside service implementations, which makes those safeguards hard to apply consistently.

## What Changes

- **BREAKING**: Replace the publish and relation `Service` entry style with `Controller -> Manager -> Helper/DAO/Publisher/Client`.
- **BREAKING**: Change publish from synchronous `204 No Content` to true asynchronous `202 Accepted + publishAttemptId`.
- Add a publish idempotency contract using a client-provided `idempotentKey`.
- Add publish status query and retry semantics around `publishAttemptId`.
- Add the publish attempt state machine: accepted requests move posts into `publishing`; the background critical publish flow later marks attempts as `succeeded`/`failed` and posts as `published`/`publish_failed`.
- Introduce chain-level executor isolation for publish, relation events, Canal/Outbox consumption, and reconciliation work.
- Introduce a Sentinel-backed guard abstraction for rate limiting, circuit breaking, degradation, and fallback.
- Keep relation APIs semantically idempotent without requiring a client idempotency key.
- Keep this as an architecture-alignment change; existing feature changes remain responsible for broader Cassandra, Leaf, recommendation, comment, and reconciliation capabilities.

## Capabilities

### New Capabilities

- `publish-relation-architecture`: Layering, API, resilience, idempotency, and execution-isolation requirements for the publish and relation chains.

### Modified Capabilities

- None.

## Impact

- Affected code areas: `knowpost` controllers/services, `relation` controllers/services, Outbox/Canal bridge, event processors, thread-pool configuration, and common infrastructure packages.
- API impact: publish response changes to `202 Accepted`, adds `publishAttemptId`, requires `idempotentKey`, and adds status/retry endpoints; `202` means accepted for processing, not already published.
- Dependency impact: add Sentinel integration while isolating it behind a local guard interface.
- Compatibility impact: existing `KnowPostService` and `RelationService` abstractions are removed or migrated; controllers depend on managers directly.
