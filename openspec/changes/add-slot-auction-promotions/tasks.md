## 1. Domain and Schema

- [ ] 1.1 Add promotion campaign, auction window, promotion bid, and slot allocation tables.
- [ ] 1.2 Introduce zhiguang-native promotion resource enums for `feed_top_slot` and `search_top_slot`.
- [ ] 1.3 Adapt reusable `bytedance` auction and bidding core to new promotion terms and remove product/live-room assumptions.

## 2. Auction and Settlement

- [ ] 2.1 Implement window-based bid intake, ranking, and multi-slot GSP settlement flow.
- [ ] 2.2 Integrate wallet hold, clearing deduction, and excess release for promotion bids.
- [ ] 2.3 Implement reserve price, slot count, and allocation persistence rules.

## 3. Feed and Search Integration

- [ ] 3.1 Replace manual feed top behavior with allocation-driven promoted feed insertion.
- [ ] 3.2 Add search promoted slot insertion with commercial placement metadata.
- [ ] 3.3 Add cached lookup path for active slot allocations used by feed and search APIs.
- [ ] 3.4 Enforce promoted-slot count limits and commercial flags in response contracts.

## 4. Operations and Verification

- [ ] 4.1 Add jobs or handlers to close auction windows and refresh cached allocations.
- [ ] 4.2 Add tests for GSP pricing, tie handling, reserve floor, and wallet release behavior.
- [ ] 4.3 Add feed/search integration tests covering promoted insertion and organic fallback.
