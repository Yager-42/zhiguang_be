## 1. Scope Cleanup and Documentation

- [x] 1.1 Remove active `add-paid-boost-promotions` change from OpenSpec so it no longer appears as implementable work.
- [x] 1.2 Update `CONTEXT.md` commercial terminology to define promotion as fixed position auction only and mark paid boost out of scope.
- [x] 1.3 Update PRD docs to remove paid boost / paid ranking weight requirements and describe B' fixed position auction flow.
- [x] 1.4 Add docs note that archived `2026-06-21-add-slot-auction-promotions` is superseded by this change and must not be edited as history.

## 2. Infrastructure and Configuration

- [x] 2.1 Add RocketMQ broker/name-server to local Docker Compose with healthchecks and documented ports.
- [x] 2.2 Add RocketMQ Java client dependency compatible with Spring Boot 3.2 and Java 21.
- [x] 2.3 Add promotion auction config for command topic, decision topic, consumer groups, Redis key TTLs, slot counts, reserve prices, and feature flag.
- [x] 2.4 Add startup validation for B' promotion auction config, including Kafka availability assumptions and RocketMQ topic names.

## 3. Data Model and Contracts

- [x] 3.1 Add B' command, decision projection, projection checkpoint, and hot-state recovery tables.
- [x] 3.2 Extend promotion bid/allocation schema to store command id, decision id, decision status, projection offset, clearing price, and fixed position metadata.
- [x] 3.3 Add API DTOs for bid command submission, command status, auction snapshot, ranking item, and decision outcome.
- [x] 3.4 Remove or deprecate paid boost API/schema contracts from active implementation path.

## 4. Ordered Command Path

- [x] 4.1 Implement HTTP bid command submission that validates ownership/content eligibility and publishes ordered command without direct ranking mutation.
- [x] 4.2 Implement RocketMQ ordered command producer keyed by `auctionWindowId`.
- [x] 4.3 Implement RocketMQ ordered command consumer that invokes Redis Lua decision script.
- [x] 4.4 Add command idempotency and request-hash replay handling.

## 5. Redis Lua Hot Decision Engine

- [x] 5.1 Port/adapt bytedance `auction-command-decision.lua` into zhiguang promotion terms.
- [x] 5.2 Model Redis keys for window state, command replay, campaign state, and TopN ranking under `auctionWindowId`.
- [x] 5.3 Implement accepted/rejected decision payload mapping for position auctions.
- [x] 5.4 Add Redis Lua tests for replay, below-reserve rejection, higher bid acceptance, ranking updates, and closed-window rejection.

## 6. Kafka Decision Log and Projection

- [x] 6.1 Implement Kafka decision log producer with decision id, command id, auction window id, request hash, ranking snapshot, and wallet effect fields.
- [x] 6.2 Implement projection consumer with per-window ordering, checkpointing, and idempotent decision application.
- [x] 6.3 Project accepted/rejected bid facts into MySQL without duplicate rows on replay.
- [x] 6.4 Project window close decisions into final GSP clearing, bid statuses, and slot allocations.
- [x] 6.5 Add projection replay tests from decision log fixtures.

## 7. Wallet Integration

- [x] 7.1 Extend wallet business refs/reasons for B' position auction hold, capture, release, and excess release.
- [x] 7.2 Integrate accepted bid decisions with durable hold or incremental hold semantics.
- [x] 7.3 Integrate window close projection with winner capture, winner excess release, loser release, and rejected release.
- [x] 7.4 Add wallet idempotency tests for duplicate decision projection and mismatched business refs.
- [x] 7.5 Defer separate hot-wallet rebuild; wallet ledger remains durable authority and Redis only caches auction ranking/replay in this change.

## 8. Snapshot, Realtime, and Read Paths

- [x] 8.1 Implement auction snapshot service from Redis hot state plus MySQL projection fallback.
- [x] 8.2 Defer optional WebSocket/SSE fanout; snapshot API is the recovery authority for this change.
- [x] 8.3 Update feed insertion to consume B' projected feed slot allocations only.
- [x] 8.4 Update search insertion to consume B' projected search slot allocations only.
- [x] 8.5 Ensure response contracts mark commercial fixed-position content and never expose paid boost markers.

## 9. Reconciliation and Degraded Recovery

- [x] 9.1 Add reconciliation task types for decision-without-projection and closed-window-without-allocation.
- [x] 9.2 Implement decision replay repair from Kafka/MySQL checkpoint facts.
- [x] 9.3 Implement allocation rebuild for closed windows from projected bid facts.
- [x] 9.4 Defer wallet effect repair; projection and settlement use idempotent wallet business refs in this change.
- [x] 9.5 Defer Redis drift markers; snapshot uses Redis hot ranking with MySQL projection fallback.

## 10. Tests and Verification

- [x] 10.1 Add unit tests for command submission, command consumer, Lua decision adapter, decision producer, projection consumer, and settlement projection.
- [x] 10.2 Cover HTTP command submission, RocketMQ listener, Redis Lua adapter, Kafka decision logging/listener, projection, and allocation with focused tests; full broker-backed end-to-end test deferred to infra-enabled suite.
- [x] 10.3 Add feed/search integration tests proving commercial items come only from fixed position allocation.
- [x] 10.4 Add regression tests proving paid boost workflow is absent or rejected.
- [x] 10.5 Run `openspec validate add-bprime-position-auctions --strict` and backend test suite for touched modules.
