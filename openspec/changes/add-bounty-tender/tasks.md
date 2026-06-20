## 1. Schema and Domain

- [ ] 1.1 Add bounty, bounty bid, bounty submission, bounty rating, subsidy, and proof-hash tables.
- [ ] 1.2 Bind bounty to eligible `knowpost` while keeping separate lifecycle and state machine.
- [ ] 1.3 Keep comment system separate from bounty bid and submission entities.

## 2. Bid and Award Flow

- [ ] 2.1 Implement sealed structured bid submission with deposit reservation.
- [ ] 2.2 Adapt reusable reverse-auction scoring code to produce advisory bid quality score only.
- [ ] 2.3 Implement manual award, lock, and winner-selection workflow.

## 3. Delivery and Settlement

- [ ] 3.1 Implement final submission flow separate from bid content.
- [ ] 3.2 Integrate bounty escrow lock, release, refund, timeout auto-release, and default forfeiture.
- [ ] 3.3 Implement platform subsidy flow for qualifying bids.

## 4. Signals and Proof

- [ ] 4.1 Integrate LLM soft publish guidance and soft bid screening with fail-open behavior.
- [ ] 4.2 Store timestamped proof hashes for bids and submissions through storage integration.
- [ ] 4.3 Aggregate completed-bounty ratings into responder reputation inputs.

## 5. Verification

- [ ] 5.1 Add tests proving bid score does not auto-award bounty.
- [ ] 5.2 Add lifecycle tests for pre-lock cancel, lock, delivery, timeout release, refund, and forfeiture.
- [ ] 5.3 Add tests proving comments remain public discussion objects and are not reused as bids or submissions.
- [ ] 5.4 Add proof-hash and subsidy integration tests.
