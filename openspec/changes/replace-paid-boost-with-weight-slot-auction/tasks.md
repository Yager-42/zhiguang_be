## 0. Interface and Test Design Gate

- [x] 0.1 Produce the backend interface inventory before implementation: HTTP APIs, WebSocket/STOMP endpoint and destinations, Kafka topics/consumer groups, event DTOs, snapshot DTOs, and internal service entry points.
- [x] 0.2 Confirm each interface owner and authority boundary: Redis/Kafka/WebSocket for auction process, MySQL for final result, feed/search for final allocation only.
- [x] 0.3 Produce the test inventory before implementation, covering business behavior, usability/recovery, concurrency, boundary cases, errors, malformed input, duplicate/ out-of-order events, and focused end-to-end flow.
- [x] 0.4 Mark out-of-scope tests explicitly: no full frontend UI test, no long-running soak test, no full middleware stress suite inside this change.
- [x] 0.5 Update this task list if interface or test design reveals a missing implementation task.

## 1. Terminology and Contract Cleanup

- [x] 1.1 Search active code, tests, configs, docs, and UI copy for `paid boost`, `paidBoost`, `weight slot`, `boost effect`, and paid ranking weight references.
- [x] 1.2 Remove or rename remaining paid boost / weight-slot public API, DTO, service, config, and test surfaces that conflict with fixed advertising-slot auction semantics.
- [x] 1.3 Update promotion-facing labels and API documentation so `feed_top_slot` and `search_top_slot` are described as auctioned advertising-slot resources.
- [x] 1.4 Verify feed/search contracts still mark commercial content from fixed slot allocation and do not expose paid boost markers.

## 2. Realtime Event Contract

- [x] 2.1 Define promotion auction realtime event types for ranking update, bid confirmed, bid rejected, and window closed.
- [x] 2.2 Define public auction-window topic naming and creator-targeted private outcome channel naming.
- [x] 2.3 Define event payload fields: auctionWindowId, commandId, decisionId, decisionVersion, eventVersion, ranking snapshot, window status, bid amount, and rejection reason.
- [x] 2.4 Add client recovery rules: first load snapshot, apply monotonic decisionVersion events, reload snapshot on reconnect or version gap.
- [x] 2.5 Define `/ws/promotion-auction`, `/topic/promotion-auctions/{auctionWindowId}`, and `/user/queue/promotion-auction-outcomes` as the only realtime channels for this change.
- [x] 2.6 Define the Kafka decision envelope using the bytedance B' shape: outer `eventType=AUCTION_DECISION`, nested `decision.type` for `BID_ACCEPTED`, `BID_REJECTED`, and `WINDOW_CLOSED`, `decisionHash`, one topic `zhiguang.promotion.auction.decisions.v2`, and Kafka key `auctionWindowId`.
- [x] 2.7 Define `WINDOW_CLOSED` payload with final ranking, winners, clearing prices, wallet effects, allocation window, and final window status so projection does not read Redis as final settlement authority.
- [x] 2.8 Add shared Kafka decision support: envelope DTO, decision DTO payload shape, canonical hasher, and validator used by both projection and fanout consumers.

## 3. Kafka Decision Fanout

- [x] 3.1 Add promotion auction fanout consumer group config next to existing promotion B' Kafka decision config.
- [x] 3.2 Implement fanout consumer that reads `zhiguang.promotion.auction.decisions.v2` independently from `PromotionDecisionProjectionKafkaListener`.
- [x] 3.3 Ensure fanout consumer publishes from Kafka decision payload directly and does not query or wait for projection checkpoint before emitting realtime events.
- [x] 3.4 Make fanout idempotent by decisionId-derived eventId so duplicate Kafka delivery does not publish duplicate visible events.
- [x] 3.5 Ensure fanout failure does not rollback Kafka decision append or block MySQL projection.
- [x] 3.6 Add `WINDOW_CLOSED` decision handling so fanout publishes terminal window events from Kafka, not from the scheduler directly.

## 4. WebSocket/STOMP Delivery

- [x] 4.1 Add Spring WebSocket/STOMP configuration for `/ws/promotion-auction`.
- [x] 4.2 Implement public publisher for auction-window ranking and lifecycle events.
- [x] 4.3 Implement targeted publisher for creator bid confirmed and bid rejected events.
- [x] 4.4 Implement BD-style WebSocket principal resolution from `Authorization: Bearer` or `access_token` query parameter, using user id as `Principal.getName()` and guest principal for public access.
- [x] 4.5 Keep WebSocket delivery as display-only; no service may read WebSocket state as auction authority.
- [x] 4.6 Do not implement server-side complex coalescing in this change; rely on direct fanout, decisionId idempotency, and client decisionVersion handling.

## 5. Snapshot and Client Flow

- [x] 5.1 Ensure promotion auction snapshot returns current status, ranking, server time, and latest decisionVersion.
- [x] 5.2 Ensure active-window snapshot reads Redis hot ranking and closed-window snapshot reads MySQL final allocation/projection.
- [x] 5.3 Document creator client flow: load snapshot before subscribing, apply monotonic decisionVersion events, reload snapshot on reconnect or version gap.
- [x] 5.4 Verify connected clients can update ranking and bid outcome display from WebSocket events without polling after every event.
- [x] 5.5 Do not add backend `ownBidState` query in this change; own visible state comes from private outcome events and campaign presence in ranking.

## 6. Projection and Feed/Search Boundaries

- [x] 6.1 Update projection so MySQL stores final promotion auction results, not every bid decision payload.
- [x] 6.2 Verify feed insertion reads only projected `promotion_slot_allocation` or its cache, never realtime events or hot ranking.
- [x] 6.3 Verify search insertion reads only projected search slot allocations and preserves commercial labeling.
- [x] 6.4 Add guard tests proving realtime fanout can run before projection without changing feed/search allocation output.
- [x] 6.5 Ensure `WINDOW_CLOSED` decision projection writes final slot allocation, settlement/wallet effects, final window status, and checkpoint.
- [x] 6.6 Ensure accepted/rejected bid decisions do not create feed/search-visible MySQL allocation before window close.
- [x] 6.7 Remove `promotion_auction_decision` schema, mapper, projection-replay fallback, and tests that treat MySQL as a bid-decision fact store; Kafka decision log is the only bid-decision replay source.

## 7. Tests and Verification

- [x] 7.1 Add unit tests for fanout consumer mapping Kafka decisions to public and targeted realtime events.
- [x] 7.2 Add unit tests for duplicate decision fanout idempotency.
- [x] 7.3 Add tests proving fanout does not wait for projection checkpoint.
- [x] 7.4 Add WebSocket/STOMP controller or publisher tests for topic and private channel delivery.
- [x] 7.5 Add snapshot recovery tests for reconnect and version gap.
- [x] 7.6 Add tests proving MySQL is not used as the high-frequency per-decision payload store.
- [x] 7.7 Add tests proving `WINDOW_CLOSED` projection creates final allocation and feed/search only see final allocation.
- [x] 7.8 Add regression tests proving paid boost / weight workflow is absent or rejected.
- [x] 7.9 Run `openspec validate replace-paid-boost-with-weight-slot-auction --strict`.
- [x] 7.10 Run focused backend tests for promotion B' command, Kafka projection, realtime fanout, snapshot, feed, and search paths.
