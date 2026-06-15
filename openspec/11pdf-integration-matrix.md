# 11.pdf Integration Matrix

Last updated: 2026-06-15

## Purpose

This document tracks how the architecture ideas from `11.pdf` are already reflected in the current OpenSpec changes. It is a cross-change constraint and handoff document, not a new source of truth.

Authority order:

1. Current `openspec/changes` artifacts are authoritative.
2. `openspec/changes/execution-order.md` defines sequencing and superseded changes.
3. This document explains where `11.pdf` ideas landed and how implementation plans should cite them.
4. Old `docs/superpowers/plans/2026-06-11-*.md` files are reference material only unless explicitly promoted again.

If `11.pdf` appears to conflict with an active change, keep the active change as-is. If the conflict would change semantics, ask the user before editing changes or plans.

## Cross-Change Mapping

| `11.pdf` idea | Current landing place | Implementation constraint |
|---|---|---|
| Manager orchestration layer | `align-publish-relation-architecture` | Publish and relation controllers move to `Controller -> Manager -> Helper/DAO/Publisher/Client`; do not keep duplicate Service orchestration. |
| Async publish acceptance | `align-publish-relation-architecture` | Publish returns `202 Accepted + publishAttemptId`; this means accepted into the state machine, not published successfully. |
| Publish idempotency | `align-publish-relation-architecture` | Publish requires client `idempotentKey`; repeated requests reuse the original attempt. |
| Relation idempotency | `align-publish-relation-architecture` | Relation writes use natural keys, state transitions, and database constraints; no client idempotency key is required. |
| Chain-level executor isolation | `align-publish-relation-architecture`; `add-data-reconciliation` | Publish, relation events, Canal/Outbox, and reconciliation use isolated executor beans or equivalent isolated scheduling. |
| Rate limit, circuit breaker, fallback | `align-publish-relation-architecture` | Use Sentinel behind local guard interfaces such as `ResilienceGuard` or `DegradeGuard`; managers should not depend directly on Sentinel annotations. |
| Critical facts vs derived tasks | `align-publish-relation-architecture`; `add-cassandra-text-storage`; `add-recommendation-and-follow-feed`; `add-data-reconciliation` | Critical facts can fail an attempt; derived ES/RAG/feed/Gorse work must create retry or reconciliation work instead of rolling back published content. |
| Unified Leaf-style ID generation | `add-leaf-id-service` | Use `IdService` and explicit `IdNamespace`; do not directly instantiate Snowflake or random ID generators. Relation row IDs such as `following.id` use `RELATION`, while relation outbox IDs use `OUTBOX_EVENT`. |
| Text fact source | `add-cassandra-text-storage` | Cassandra is the fact store for post/comment text; ES and vector stores are rebuildable derived indexes. |
| Async comment write | `add-comment-system` | Comment creation returns `202 + pendingCommentId`; Kafka consumer persists Cassandra text and MySQL metadata. |
| Comment idempotency | `add-comment-system` | `clientRequestId` is enforced by a MySQL unique index in the consumer path. |
| Recommendation and follow feed as derived work | `add-recommendation-and-follow-feed` | Gorse item upsert, feedback delivery, and follow-feed fanout consume events asynchronously and create reconciliation tasks on failure. |
| Compensation, retry, dead tasks, stuck running recovery | `add-data-reconciliation` | `reconciliation_task` is the task fact source; DB polling, exponential backoff, `dead`, manual retry, and running-timeout reset are required. |
| Service boundaries and future split | `split-to-microservices` | Current implementation remains modular monolith; no cross-boundary JOIN, external dependencies go through adapters, writes cross boundaries by events. |
| Old publish pipeline details | `archive/superseded-eventize-publish-pipeline`; absorbed by active changes | Do not execute `eventize-publish-pipeline` as an active change; carry only still-valid details through the active landing places above. |

## Per-Change Guidance

| Change | Already reflects | Out of scope | Plan guidance |
|---|---|---|---|
| `add-leaf-id-service` | Unified ID API, Snowflake for high-throughput entities, Segment for operational tasks, explicit namespaces including publish/comment/relation/outbox/reconciliation. | Microservice deployment, dynamic worker allocation, feature-specific business flows. | Current `2026-06-15-add-leaf-id-service.md` plan should cite this matrix as shared context and keep implementation limited to ID service. |
| `align-publish-relation-architecture` | Manager layer, async publish attempt contract, publish/relation idempotency, executor isolation, Sentinel guard abstraction, critical vs derived failure semantics. | Cassandra implementation, comment system, recommendation/feed, reconciliation repairers, actual service split. | Future implementation plans for this change must treat it as the primary `11.pdf` architecture landing place. |
| `add-cassandra-text-storage` | Text fact source, Cassandra write as part of critical publish flow, fallback reads for legacy MinIO text, hard delete, overwrite on republish. | Feed/list queries, search ranking, comment list pagination, schema DDL from application startup. | Plans must keep Cassandra write failure as publish-attempt failure, not as a derived retry-only task. |
| `add-comment-system` | Async write, pending status, final comment ID from `IdService`, Kafka consumer persistence, MySQL idempotency, dead-letter failure status. | Infinite nesting, Redis comment cache, comment image/audit/sensitive-word workflows. | Plans must depend on `IdService` and `TextStorageService`; they should not reintroduce synchronous comment persistence at the API boundary. |
| `add-recommendation-and-follow-feed` | Event-driven Gorse item upsert, feedback delivery, follow-feed fanout, adapter boundary, fallback to hot content. | Blocking publish on Gorse/feed success, requiring Gorse in local dev, early active-fan filtering. | Plans must consume successful publish/comment/relation events and create reconciliation tasks for failures. |
| `add-data-reconciliation` | Compensation task source, retry/backoff, `dead` state, manual retry, running-timeout recovery, checkpointed scanning. | Kafka as a second task fact source, replacing primary business writes, full repairers before their target modules exist. | Plans may build framework early, but concrete repairers should align with the fact sources introduced by prior changes. |
| `split-to-microservices` | Service-boundary constraints, adapter rule, event rule, no cross-boundary JOIN, future Gateway/Nacos direction. | Runtime microservice split in the first batch. | Treat as architecture-only guidance unless the user explicitly starts a split implementation change. |

## Current Plan Citation Status

| Plan set | Status | Required action |
|---|---|---|
| `2026-06-15-add-leaf-id-service.md` | Current execution entry for `add-leaf-id-service`. | Cite this matrix as shared context. Keep implementation limited to ID service. |
| `2026-06-11-*.md` plans | Restored reference material only. | Do not execute as authoritative plans. Reuse only task granularity that still matches active changes. |
| Future plans for active changes | Not yet regenerated in this handoff. | Read this matrix before writing or executing each plan. Include only the rows relevant to that change. |

## Gap Policy

No semantic gaps requiring OpenSpec change edits were found while creating this matrix. The active designs are consistent on the major `11.pdf` decisions: attempt-based publish, Manager boundaries, Sentinel guard abstraction, Cassandra text fact source, async comment write, derived recommendation/feed work, reconciliation compensation, and microservice constraints.

Allowed lightweight updates:

- Add references from implementation plans to this matrix.
- Add small clarifying notes when a plan needs to know where a `11.pdf` idea landed.
- Correct plan wording that accidentally treats superseded `eventize-publish-pipeline` or old 6.11 plans as authoritative.

Ask the user first before:

- Changing active OpenSpec semantics.
- Reviving `eventize-publish-pipeline` as an active change.
- Reordering the execution phases.
- Rewriting current changes broadly from `11.pdf`.
