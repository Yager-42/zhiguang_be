## ADDED Requirements

### Requirement: B' compensation SHALL derive settled facts only from MySQL
The system SHALL run B' promotion auction compensation only for `SETTLED` windows and SHALL derive expected winner, first price, wallet effects, escrow closure, and allocation from the shared promotion settlement facts using MySQL window, bid, and escrow records. It SHALL NOT use Redis, Redis Stream history, WebSocket delivery, or historical commands to invent expected settlement.

#### Scenario: Settled window allocation is fully missing
- **WHEN** a `SETTLED` sold window has sufficient consistent MySQL settled facts
- **AND** no allocation row exists
- **THEN** the system schedules promotion allocation rebuild
- **AND** rebuild inserts only the expected allocation
- **AND** does not call wallet settlement or change bid, escrow, or window state

#### Scenario: Settled window allocation is partially present
- **WHEN** a `SETTLED` window has an incomplete or conflicting allocation
- **THEN** reconciliation marks the task `dead`
- **AND** does not auto-heal individual allocation rows

#### Scenario: Bid authorization fact is missing
- **WHEN** a settled bid has no corresponding escrow authorization fact
- **THEN** reconciliation marks the task `dead`
- **AND** does not substitute bid amount for authorization or read Redis/WebSocket state

### Requirement: B' compensation SHALL repair wallet effects idempotently
The system SHALL verify and repair only settled-window `CAPTURE` and `RELEASE` wallet effects. Repairs SHALL use wallet businessRef idempotency, SHALL create repair tasks per missing effect, and SHALL reject mismatched existing ledger facts.

#### Scenario: Missing winner capture is repaired
- **WHEN** a `SETTLED` auction window recomputes a winning bid with a clearing price
- **AND** wallet ledger has no matching capture businessRef
- **THEN** system schedules or executes one capture repair effect for that winner
- **AND** repeated repair does not duplicate ledger movement

#### Scenario: Missing loser or winner release is repaired
- **WHEN** a `SETTLED` auction window recomputes a required release amount
- **AND** wallet ledger has no matching release businessRef
- **THEN** system schedules or executes one release repair effect for that bid
- **AND** repeated repair does not duplicate ledger movement

#### Scenario: Existing wallet ledger conflicts
- **WHEN** a required B' wallet businessRef already exists with different owner, amount, reason, business type, or balance delta
- **THEN** system marks the repair task as `dead`
- **AND** does not append a compensating ledger movement that hides the mismatch

#### Scenario: Hold would be required to trust the result
- **WHEN** settled wallet repair cannot safely derive the expected settled effect without trusting a missing or inconsistent historical hold fact
- **THEN** system marks the repair task as `dead`
- **AND** does not auto-create a settled-phase hold repair

### Requirement: B' compensation SHALL remain eventual and non-blocking
The system SHALL run B' compensation as eventual reconciliation work. Normal bid submission, decision fanout, feed/search rendering, and snapshot reads SHALL NOT synchronously wait for deep compensation scans.

#### Scenario: Drift exists during user flow
- **WHEN** settled allocation or wallet drift is detected after a user bid flow has completed
- **THEN** system schedules or executes reconciliation asynchronously
- **AND** does not block unrelated feed/search requests
