## 0. Interface and Test Design Gate

- [ ] 0.1 Produce the backend interface inventory before implementation: HTTP APIs, WebSocket/STOMP endpoint and destinations, Kafka topics/consumer groups, event DTOs, snapshot DTOs, and internal service entry points.
- [ ] 0.2 Confirm each interface owner and authority boundary: Redis/Kafka/WebSocket for auction process, MySQL for final result, feed/search for final allocation only.
- [ ] 0.3 Produce the test inventory before implementation, covering business behavior, usability/recovery, concurrency, boundary cases, errors, malformed input, duplicate/ out-of-order events, and focused end-to-end flow.
- [ ] 0.4 Mark out-of-scope tests explicitly: no full frontend UI test, no long-running soak test, no full middleware stress suite inside this change.
- [ ] 0.5 Update this task list if interface or test design reveals a missing implementation task.

## 1. Terminology and Contract Cleanup

- [ ] 1.1 Search active code, tests, configs, docs, and UI copy for `paid boost`, `paidBoost`, `weight slot`, `boost effect`, and paid ranking weight references.
- [ ] 1.2 Remove or rename remaining paid boost / weight-slot public API, DTO, service, config, and test surfaces that conflict with fixed advertising-slot auction semantics.
- [ ] 1.3 Update promotion-facing labels and API documentation so `feed_top_slot` and `search_top_slot` are described as auctioned advertising-slot resources.
- [ ] 1.4 Verify feed/search contracts still mark commercial content from fixed slot allocation and do not expose paid boost markers.

## 2. Realtime Event Contract

- [ ] 2.1 Define promotion auction realtime event types for ranking update, bid confirmed, bid rejected, and window closed.
- [ ] 2.2 Define public auction-window topic naming and creator-targeted private outcome channel naming.
- [ ] 2.3 Define event payload fields: auctionWindowId, commandId, decisionId, decisionVersion, eventVersion, ranking snapshot, window status, bid amount, and rejection reason.
- [ ] 2.4 Add client recovery rules: first load snapshot, apply monotonic decisionVersion events, reload snapshot on reconnect or version gap.
- [ ] 2.5 Define `/ws/promotion-auction`, `/topic/promotion-auctions/{auctionWindowId}`, and `/user/queue/promotion-auction-outcomes` as the only realtime channels for this change.

## 3. Kafka Decision Fanout

- [ ] 3.1 Add promotion auction fanout consumer group config next to existing promotion B' Kafka decision config.
- [ ] 3.2 Implement fanout consumer that reads `zhiguang.promotion.auction.decisions.v2` independently from `PromotionDecisionProjectionKafkaListener`.
- [ ] 3.3 Ensure fanout consumer publishes from Kafka decision payload directly and does not query or wait for projection checkpoint before emitting realtime events.
- [ ] 3.4 Make fanout idempotent by decisionId-derived eventId so duplicate Kafka delivery does not publish duplicate visible events.
- [ ] 3.5 Ensure fanout failure does not rollback Kafka decision append or block MySQL projection.
- [ ] 3.6 Add `WINDOW_CLOSED` decision handling so fanout publishes terminal window events from Kafka, not from the scheduler directly.

## 4. WebSocket/STOMP Delivery

- [ ] 4.1 Add Spring WebSocket/STOMP configuration for `/ws/promotion-auction`.
- [ ] 4.2 Implement public publisher for auction-window ranking and lifecycle events.
- [ ] 4.3 Implement targeted publisher for creator bid confirmed and bid rejected events.
- [ ] 4.4 Implement BD-style WebSocket principal resolution from `Authorization: Bearer` or `access_token` query parameter, using user id as `Principal.getName()` and guest principal for public access.
- [ ] 4.5 Keep WebSocket delivery as display-only; no service may read WebSocket state as auction authority.
- [ ] 4.6 Do not implement server-side complex coalescing in this change; rely on direct fanout, decisionId idempotency, and client decisionVersion handling.

## 5. Snapshot and Client Flow

- [ ] 5.1 Ensure promotion auction snapshot returns current status, ranking, server time, and latest decisionVersion.
- [ ] 5.2 Ensure active-window snapshot reads Redis hot ranking and closed-window snapshot reads MySQL final allocation/projection.
- [ ] 5.3 Document creator client flow: load snapshot before subscribing, apply monotonic decisionVersion events, reload snapshot on reconnect or version gap.
- [ ] 5.4 Verify connected clients can update ranking and bid outcome display from WebSocket events without polling after every event.
- [ ] 5.5 Do not add backend `ownBidState` query in this change; own visible state comes from private outcome events and campaign presence in ranking.

## 6. Projection and Feed/Search Boundaries

- [ ] 6.1 Update projection so MySQL stores final promotion auction results, not every bid decision payload.
- [ ] 6.2 Verify feed insertion reads only projected `promotion_slot_allocation` or its cache, never realtime events or hot ranking.
- [ ] 6.3 Verify search insertion reads only projected search slot allocations and preserves commercial labeling.
- [ ] 6.4 Add guard tests proving realtime fanout can run before projection without changing feed/search allocation output.
- [ ] 6.5 Ensure `WINDOW_CLOSED` decision projection writes final slot allocation, settlement/wallet effects, final window status, and checkpoint.
- [ ] 6.6 Ensure accepted/rejected bid decisions do not create feed/search-visible MySQL allocation before window close.

## 7. Tests and Verification

- [ ] 7.1 Add unit tests for fanout consumer mapping Kafka decisions to public and targeted realtime events.
- [ ] 7.2 Add unit tests for duplicate decision fanout idempotency.
- [ ] 7.3 Add tests proving fanout does not wait for projection checkpoint.
- [ ] 7.4 Add WebSocket/STOMP controller or publisher tests for topic and private channel delivery.
- [ ] 7.5 Add snapshot recovery tests for reconnect and version gap.
- [ ] 7.6 Add tests proving MySQL is not used as the high-frequency per-decision payload store.
- [ ] 7.7 Add tests proving `WINDOW_CLOSED` projection creates final allocation and feed/search only see final allocation.
- [ ] 7.8 Add regression tests proving paid boost / weight workflow is absent or rejected.
- [ ] 7.9 Run `openspec validate replace-paid-boost-with-weight-slot-auction --strict`.
- [ ] 7.10 Run focused backend tests for promotion B' command, Kafka projection, realtime fanout, snapshot, feed, and search paths.
