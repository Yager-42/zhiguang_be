## 1. Domain and Schema

- [ ] 1.1 Add paid boost campaign, budget, spend, and status tables.
- [ ] 1.2 Define campaign channels for recommendation ranking and follow delivery priority.
- [ ] 1.3 Reuse wallet reservation and settlement primitives without importing auction winner semantics.

## 2. Budget and Ledger Flow

- [ ] 2.1 Implement budget reservation when paid boost campaign activates.
- [ ] 2.2 Implement spend settlement and remaining-budget release on campaign close.
- [ ] 2.3 Add idempotent business references for campaign budget movements.

## 3. Feed Integration

- [ ] 3.1 Integrate paid boost weighting into recommendation ranking path.
- [ ] 3.2 Integrate paid boost priority into constrained follow-delivery selection logic.
- [ ] 3.3 Expose commercial promotion markers for boosted items in feed responses.

## 4. Verification

- [ ] 4.1 Add tests proving paid boost does not create winner or GSP semantics.
- [ ] 4.2 Add ranking tests for organic score plus boost effect behavior.
- [ ] 4.3 Add follow-delivery tests for constrained priority selection and non-constrained fallback.
- [ ] 4.4 Add wallet settlement tests for partial budget consumption and release.
