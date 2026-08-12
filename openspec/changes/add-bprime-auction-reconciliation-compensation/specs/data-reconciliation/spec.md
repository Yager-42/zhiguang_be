## MODIFIED Requirements

### Requirement: Reconciliation SHALL recover B' settlement from MySQL facts

The system SHALL repair settled B' allocation and wallet-effect drift by invoking the promotion settlement module's expected-facts action over MySQL `promotion_auction_window`, `promotion_bid`, and `promotion_bid_escrow` facts. Reconciliation SHALL retain task creation, actual-fact comparison, retry, and dead-task orchestration. Redis, Redis Stream history, WebSocket state, and historical command records SHALL NOT be expected-settlement inputs.

#### Scenario: Durable facts conflict
- **WHEN** a settled winner, first price, bid, or escrow authorization cannot be derived consistently
- **THEN** reconciliation marks the task `dead`
- **AND** does not infer missing authorization or reconstruct settlement from Redis or WebSocket state

#### Scenario: Slot allocation is fully missing after settled window
- **WHEN** a sold position auction window is `SETTLED`
- **AND** allocation is wholly absent
- **AND** MySQL settled facts derive one expected allocation
- **THEN** reconciliation inserts only that allocation
- **AND** does not execute wallet effects or mutate bid, escrow, or window state

#### Scenario: Slot allocation is partially present after settled window
- **WHEN** a `SETTLED` window has partial or conflicting allocation
- **THEN** reconciliation marks the task `dead`
- **AND** does not attempt automatic partial-row repair

#### Scenario: Historical command is absent
- **WHEN** a settled window has sufficient MySQL settled facts
- **AND** its historical command is absent
- **THEN** reconciliation derives expected settlement without that command
- **AND** does not treat the command as settlement authority

#### Scenario: Wallet effect is missing
- **WHEN** a recomputed settled result requires capture or release wallet movement
- **AND** the matching wallet businessRef is missing
- **THEN** reconciliation schedules or executes wallet effect repair
- **AND** repeated repair remains idempotent

### Requirement: Reconciliation SHALL preserve the deep settlement boundary

Deep repair SHALL remain scoped to B' settled facts and SHALL NOT become a full-platform reconciliation rewrite. Expected settlement derivation belongs to promotion; task and ledger/allocation comparison belongs to reconciliation.

#### Scenario: Wallet repair runs from shared facts
- **WHEN** wallet ledger repair is triggered for a settled window
- **THEN** reconciliation consumes expected `CAPTURE` / `RELEASE` effects from shared settlement facts
- **AND** does not add settled-phase `HOLD` repair

#### Scenario: Non-B' target is encountered
- **WHEN** a B' deep compensation task receives a target type outside Kafka promotion decision id or promotion auction window
- **THEN** system rejects the task
- **AND** does not run generic full-platform repair
