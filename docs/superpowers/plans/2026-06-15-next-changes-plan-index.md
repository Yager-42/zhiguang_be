# Next OpenSpec Changes Plan Index

> **For agentic workers:** REQUIRED: Use `superpowers:subagent-driven-development` if subagents are available. Otherwise use `superpowers:executing-plans`. Run one plan at a time and do not mix write scopes.

**Goal:** Provide the current execution entry for `add-leaf-id-service` and the following OpenSpec changes.

**Architecture:** These plans follow `openspec/changes/execution-order.md` and `openspec/11pdf-integration-matrix.md`. Current `openspec/changes` artifacts remain authoritative; restored `2026-06-11-*.md` plans are reference material only.

**Tech Stack:** Java 21, Spring Boot 3.2.4, MyBatis, MySQL, Redis, Kafka, MinIO, Elasticsearch, Cassandra, Sentinel, Maven, JUnit 5, OpenSpec.

---

## Execution Order

0. Finish [Leaf ID Service Plan](./2026-06-15-add-leaf-id-service.md).
1. [Publish/Relation Architecture Plan](./2026-06-15-align-publish-relation-architecture.md).
2. [Cassandra Text Storage Plan](./2026-06-15-add-cassandra-text-storage.md).
3. [Comment System Plan](./2026-06-15-add-comment-system.md).
4. [Recommendation and Follow Feed Plan](./2026-06-15-add-recommendation-and-follow-feed.md).
5. [Data Reconciliation Plan](./2026-06-15-add-data-reconciliation.md).

`split-to-microservices` remains architecture-only guidance for this batch. Do not create runtime microservice split work unless the user explicitly starts that change.

## Shared Context

- Repo root: `E:\idk\zhiguang_be`
- Cross-change mapping: `openspec/11pdf-integration-matrix.md`
- Execution order: `openspec/changes/execution-order.md`
- Current authoritative changes: `openspec/changes/*`
- Old restored reference plans: `docs/superpowers/plans/2026-06-11-*.md`
- Shared ID package after Leaf implementation: `com.tongji.common.id`

## Maven Setup

Run before Maven commands:

```powershell
$mvn = Join-Path $env:USERPROFILE 'Desktop\文档\.codex\tools\apache-maven-3.9.6\bin\mvn.cmd'
Test-Path $mvn
```

Expected: `True`.

## Plan Boundaries

| Plan | Primary write scope | Must not do |
|---|---|---|
| `add-leaf-id-service` | `com.tongji.common.id`, `leaf_alloc`, current ID call-site migration, ID verification | Publish/relation manager refactor, comment system, reconciliation framework |
| `align-publish-relation-architecture` | `knowpost`/`relation` managers, publish attempt contract, executors, guard abstraction | Cassandra implementation, comment system, recommendation/feed, full reconciliation repairers |
| `add-cassandra-text-storage` | Cassandra environment, `storage.text`, ES/RAG text reads, publish critical text hook | Comment APIs, recommendation/feed, reconciliation framework |
| `add-comment-system` | `comment` package, comment schema, Kafka write consumer, comment APIs, count hooks | Redis comment cache, infinite nesting, Gorse adapter implementation |
| `add-recommendation-and-follow-feed` | `recommendation` package, follow-feed Redis structures, feed mixing, Gorse adapter | Blocking publish on Gorse/feed success, active-follower optimization v1 |
| `add-data-reconciliation` | `reconciliation` package, task framework, scans, APIs, concrete repairers for existing facts | Replacing normal write paths, Kafka as task fact source |

## Completion Rule

For each plan:

- Implement tasks in order.
- Run the verification commands in the plan.
- Only update that change's `tasks.md` checkboxes after evidence exists.
- Run `openspec status --change "<change>" --json`.
- Run `openspec validate <change> --strict` when supported by the local CLI.
