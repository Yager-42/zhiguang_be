# Superseded Change Note

This change was intentionally removed from the active OpenSpec queue because its scope overlaps with newer, more precise changes.

## Status

Superseded, not implemented as a standalone change.

## Why

`align-publish-relation-architecture` now owns the publish API contract, manager orchestration, idempotency, retry/status semantics, executor isolation, and critical-vs-derived failure boundaries.

The original `eventize-publish-pipeline` artifacts also mixed Cassandra text storage, recommendation/feed events, and reconciliation behavior into the publish pipeline change. Those concerns now live in their own focused changes.

## Absorbed By

- `align-publish-relation-architecture`
  - `202 Accepted + publishAttemptId`
  - publish attempt state machine
  - `draft -> publishing -> published/publish_failed`
  - status query and retry
  - critical-vs-derived publish failure semantics

- `add-cassandra-text-storage`
  - Cassandra as post/comment text fact store
  - Cassandra write failure blocks `publishing -> published`
  - MinIO fallback for historical text

- `add-recommendation-and-follow-feed`
  - `content_published` consumption
  - Gorse item upsert
  - follow feed fanout after successful publish

- `add-data-reconciliation`
  - derived task compensation
  - stuck `publishing` detection
  - ES/RAG/Gorse/feed repair tasks

## Conflict Resolution

The chosen publish API semantics are true asynchronous acceptance:

```text
POST /publish
  -> lightweight validation + idempotency + attempt creation/reuse
  -> CAS into publishing
  -> return 202 + publishAttemptId
  -> background critical publish flow
       -> success: attempt=succeeded, post=published
       -> failure: attempt=failed, post=publish_failed
```

`202 Accepted` is only an acceptance signal. It does not mean the post has already been published.
