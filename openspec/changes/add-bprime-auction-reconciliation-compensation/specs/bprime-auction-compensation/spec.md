## ADDED Requirements

### Requirement: B' compensation SHALL repair settled-window facts only
The system SHALL run B' promotion auction compensation only for `SETTLED` auction windows. This change SHALL repair settled projection, slot allocation, and wallet settlement facts, and SHALL NOT repair Redis hot state, WebSocket delivery, or in-flight bidding state.

#### Scenario: Settled window projection is missing
- **WHEN** an auction window has accepted Kafka decision facts
- **AND** projection checkpoint or projected bid facts are missing
- **THEN** system schedules promotion decision projection repair tasks
- **AND** each repair task remains idempotent by decision id

#### Scenario: Settled window allocation is fully missing
- **WHEN** a `SETTLED` auction window has enough durable settled facts to recompute winners and clearing prices
- **AND** no slot allocation row exists for that window
- **THEN** system schedules promotion allocation rebuild repair
- **AND** allocation rebuild recomputes winners from durable bid and window facts instead of trusting existing projected slot indexes or clearing prices

#### Scenario: Settled window allocation is partially present
- **WHEN** a `SETTLED` auction window has some slot allocation rows
- **AND** the row count, slot continuity, or winner set is inconsistent with recomputed durable facts
- **THEN** system marks the window reconciliation as `dead`
- **AND** does not try to auto-heal the partial allocation set

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
