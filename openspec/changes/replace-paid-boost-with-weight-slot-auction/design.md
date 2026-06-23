## Context

zhiguang promotion is now a fixed advertising-slot auction. The platform sells scarce resources such as `feed_top_slot` and `search_top_slot`; creators bid with campaigns; winners place their posts into the resulting slot allocation.

The B' chain remains:

```text
HTTP bid command
  -> RocketMQ ordered command
  -> Redis Lua decision
  -> Kafka decision log
      -> realtime fanout consumer
      -> final projection consumer
```

The important boundary is now stricter than the earlier draft: **Redis + Kafka + WebSocket handle the auction process; MySQL stores final results and query projections.** MySQL SHALL NOT receive every bid decision payload as a high-frequency event store, and the old `promotion_auction_decision` table SHALL be removed instead of redefined.

## Goals / Non-Goals

**Goals:**
- Remove `paid boost` / `weight` semantics and keep only fixed advertising-slot auction semantics.
- Use Redis Lua for hot ranking and per-window `decisionVersion` generation.
- Use Kafka decision log as the process fact stream for accepted bids, rejected bids, and window close decisions.
- Add Kafka fanout consumer and WebSocket/STOMP display events without waiting for MySQL projection.
- Store only final allocation, final window status, settlement / wallet effects, and projection checkpoint in MySQL.
- Remove the MySQL per-decision table and all mapper/service/test surfaces that treat MySQL as a bid-decision fact store.
- Keep feed/search reading final projected allocation only.

**Non-Goals:**
- No paid boost, paid ranking weight, or `organic score + boost effect`.
- No WebSocket state store.
- No server-side complex coalesce scheduler.
- No per-decision MySQL payload/event table for all bid attempts.
- No backend `ownBidState` snapshot query in this change; private outcome events and ranking snapshot cover the current display need.
- No frontend implementation in this backend change.

## Interface and Test Design Gate

### Backend interface inventory

Existing HTTP surfaces stay:

```text
POST /api/v1/promotions/campaigns
GET  /api/v1/promotions/campaigns/{campaignId}
POST /api/v1/promotions/campaigns/{campaignId}/bids
GET  /api/v1/promotions/allocations/active?resourceType=...
GET  /api/v1/promotions/windows/{auctionWindowId}/snapshot
GET  /api/v1/knowposts/feed
GET  /api/v1/search
```

New realtime surface:

```text
STOMP endpoint: /ws/promotion-auction
public topic:   /topic/promotion-auctions/{auctionWindowId}
private queue:  /user/queue/promotion-auction-outcomes
```

Messaging surfaces:

```text
RocketMQ command topic: zhiguang_promotion_auction_commands_v2
RocketMQ command group: zhiguang-promotion-command-consumer
Kafka decision topic:   zhiguang.promotion.auction.decisions.v2
Kafka key:              auctionWindowId
Kafka projection group: zhiguang-promotion-projection-consumer
Kafka fanout group:     zhiguang-promotion-fanout-consumer
```

DTO/model surfaces to change or add:

```text
PromotionAuctionDecisionLogEnvelope
PromotionAuctionDecision
PromotionDecisionType
PromotionDecisionHasher
PromotionDecisionKafkaSupport
PromotionAuctionRealtimeEvent
PromotionAuctionOutcomeEvent
PromotionAuctionSnapshot
PromotionRankingItem
PromotionWalletEffect
```

Internal entry points:

```text
PromotionCommandSubmissionService.submit
PromotionCommandRocketMqListener.onMessage
PromotionCommandProcessingService.process
PromotionRedisDecisionAdapter.decide / commit
KafkaPromotionDecisionLogPort.append
PromotionDecisionProjectionKafkaListener.onMessage
PromotionDecisionProjectionService.project
PromotionDecisionFanoutKafkaListener.onMessage
PromotionAuctionRealtimePublisher
PromotionSnapshotService.snapshot
PromotionAuctionWindowCloser.closeDueWindows
PromotionAllocationService.getActiveFeedAllocation / getActiveSearchAllocation
KnowPostFeedServiceImpl / HomeFeedMixingService / SearchServiceImpl allocation insertion
```

### Authority boundary

```text
HTTP submit bid     -> command intake only
RocketMQ command    -> ordered command delivery
Redis               -> active auction hot ranking and decisionVersion
Kafka decision log  -> bid-decision fact source for 7 days
WebSocket/STOMP     -> display delivery only
MySQL               -> lightweight accepted bid facts, final projection, allocation, wallet/checkpoint facts
feed/search         -> final slot allocation only
```

`promotion_auction_decision`, `PromotionAuctionDecisionMapper`, and `PromotionAuctionDecisionRecord` are out. They currently make MySQL a per-decision payload store and must be deleted with their snapshot/projection/test usage.

### Test inventory

Business behavior:

```text
accepted bid appends Kafka envelope before visible confirmation
rejected bid advances checkpoint only
WINDOW_CLOSED projects final allocation/status/wallet/checkpoint
feed/search read only promotion_slot_allocation or allocation cache
paid boost / weight workflow absent or rejected
```

Recovery/usability:

```text
snapshot returns status, ranking, serverTime, decisionVersion
active snapshot reads Redis
closed snapshot reads MySQL final allocation/projection
client version gap and reconnect contract documented
fanout can run while projection is behind
```

Concurrency/boundary/error:

```text
decisionVersion is monotonic per auctionWindowId
duplicate Kafka decision is idempotent
out-of-order or skipped decisionVersion fails projection/fanout handling
malformed envelope, wrong schemaVersion, wrong eventType, bad decisionHash are rejected
WINDOW_CLOSED fanout comes from Kafka, not scheduler direct push
fanout failure does not rollback Kafka append or block projection
projection does not recreate or query promotion_auction_decision
7-day Kafka replay window is the only bid-decision replay window
```

Out of scope:

```text
full frontend UI automation
long-running soak test
full Kafka/RocketMQ/Redis/MySQL middleware stress suite
server-side fanout coalescing scheduler
backend ownBidState query
```

## Decisions

### 1. Fixed advertising slots are the auction goods

`feed_top_slot` and `search_top_slot` are platform resources sold by auction. `PromotionCampaign` is the bidder's campaign; `postId` is the content displayed after winning allocation. This is not a recommendation-weight purchase.

### 2. Kafka decision log is the process fact stream

Redis Lua produces decisions and appends them to Kafka. Kafka carries:

```text
BID_ACCEPTED
BID_REJECTED
WINDOW_CLOSED
```

Only decisions successfully appended to Kafka become user-visible confirmations. Fanout and projection consume the same Kafka topic independently.

All promotion auction decision events SHALL use the same Kafka topic and ordering key:

```text
topic: zhiguang.promotion.auction.decisions.v2
key: auctionWindowId
```

The topic follows the bytedance B' shape: one common envelope, one outer event type, and business decision type inside `decision.type`. Type-specific fields live inside `decision.payload`; the envelope fields do not vary:

```text
schemaVersion
eventType = AUCTION_DECISION
decision
decisionHash
producedAt
```

The nested `decision` contains:

```text
type = BID_ACCEPTED | BID_REJECTED | WINDOW_CLOSED
auctionWindowId
commandId
decisionId
decisionVersion
previousVersion
decidedAt
payload
```

`decisionHash` is computed from a canonical decision representation and verified by consumers before projection or fanout. `BID_ACCEPTED`, `BID_REJECTED`, and `WINDOW_CLOSED` SHALL NOT be split into separate topics or type-specific partitions, because all decisions for one auction window must keep Kafka key ordering by `auctionWindowId`.

### 3. MySQL stores final results, not the whole auction process

MySQL SHALL store:

```text
lightweight accepted bid facts
promotion_slot_allocation
final auction window status
settlement / wallet final effects
projection checkpoint
```

MySQL SHALL NOT store every accepted/rejected decision payload as the normal high-frequency event log. The `promotion_auction_decision` table SHALL be deleted; there is no MySQL "decision fact" authority after this change. Kafka is the replay/audit source for the auction process.

Projection follows the bytedance split:

```text
BID_ACCEPTED  -> upsert lightweight promotion_bid fact and wallet hold state
BID_REJECTED  -> advance checkpoint only; do not create feed/search-visible facts
WINDOW_CLOSED -> write final bid statuses, slot allocation, settlement/wallet effects, final window status, checkpoint
```

This keeps accepted bid query/reconciliation state available without recreating a generic per-decision payload table.

### 4. decisionVersion is per auction window

`decisionVersion` is generated by Redis Lua with a per-window counter:

```text
promotion:auction:{auctionWindowId}:decision_version
```

The generated version is written into the Kafka decision payload. Leaf may generate `commandId`, `decisionId`, or `eventId`, but SHALL NOT generate `decisionVersion`, because clients need per-window monotonic versions for gap detection.

### 5. Window close is also a decision

The backend closer may be scheduled, but it only triggers close processing. The close path SHALL produce a `WINDOW_CLOSED` decision into Kafka. Projection consumes that decision to write final allocation/settlement. Fanout consumes it to publish a terminal WebSocket event.

The closer SHALL NOT directly write final allocation and SHALL NOT directly push WebSocket events.

`WINDOW_CLOSED` payload SHALL contain enough final settlement data for projection to write MySQL without consulting Redis hot ranking as final authority:

```text
final ranking snapshot
winners
clearing prices
wallet effects
allocationStartAt / allocationEndAt
final window status
```

### 6. WebSocket is a display channel

WebSocket/STOMP publishes public ranking/lifecycle events and private bid outcome events from Kafka decisions. It stores no business state. Duplicate Kafka delivery is handled by `decisionId`-derived event idempotency plus client-side `decisionVersion` rules.

Endpoint/channel shape:

```text
/ws/promotion-auction
/topic/promotion-auctions/{auctionWindowId}
/user/queue/promotion-auction-outcomes
```

Private routing follows the BD shape: JWT in `Authorization: Bearer ...` or `?access_token=...` sets `Principal.getName()` to user id; `convertAndSendToUser(userId, "/queue/promotion-auction-outcomes", event)` delivers the outcome.

### 7. Snapshot is recovery, not per-event polling

Clients load snapshot on first entry, reconnect, or version gap. During an active window, snapshot reads Redis hot state. After close, snapshot reads MySQL final allocation/projection.

Snapshot returns public recoverable state:

```text
auctionWindowId
status
ranking
serverTime
decisionVersion
```

Creator-specific status is handled by private outcome events and by finding the creator's campaign in ranking. A later `?campaignId=` snapshot extension can be added only if product needs off-ranking self state.

### 8. Feed/search read final allocation only

Realtime ranking exists for the auction UI. Feed/search read only final projected `promotion_slot_allocation` or its cache. They never read WebSocket events or Redis hot ranking to decide commercial placement.

### 9. Kafka decision replay window is 7 days

Kafka decision log retention for promotion auction decisions is 7 days. Projection replay, Redis rebuild, wallet repair, and allocation repair must complete from Kafka within that window.

If required decision facts are older than Kafka retention and no final MySQL projection facts exist, reconciliation marks the task failed/dead and exposes the gap. The system SHALL NOT recreate a MySQL per-decision table as a shadow backup for expired Kafka decisions.

## Risks / Trade-offs

- Kafka retention is fixed at 7 days because high-frequency process decisions are not copied to MySQL. Operational replay must happen inside that window.
- During active auctions, fanout can be ahead of MySQL. That is expected: auction UI reads decision events, feed/search read final allocation.
- If WebSocket delivery fails, clients recover through snapshot. The decision remains in Kafka.
- If final projection fails after `WINDOW_CLOSED`, replay Kafka decisions for that window and rerun final projection.
- If projection is missing after the 7-day Kafka replay window, reconciliation fails visibly instead of hiding the gap with a MySQL decision backup table.

## Migration Plan

1. Clean active code/API/test wording from paid boost / weight semantics.
2. Add `decisionVersion` to promotion auction decision payloads, generated by Redis Lua per window.
3. Add `WINDOW_CLOSED` decision emission from the close path.
4. Add Kafka fanout consumer group and WebSocket/STOMP publisher.
5. Delete the MySQL per-decision table path and change projection semantics so MySQL writes final allocation/settlement/checkpoint, not every bid decision payload.
6. Keep snapshot active-window reads on Redis and closed-window reads on MySQL final projection.
7. Add tests for fanout before projection, no per-decision MySQL write, final close projection, and feed/search final-allocation boundary.

## Open Questions

None. Current choice: Redis + Kafka + WebSocket for process; MySQL for final result.
