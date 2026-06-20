# escrow-lifecycle Specification

## Purpose
TBD - created by archiving change add-wallet-and-escrow. Update Purpose after archive.
## Requirements
### Requirement: Escrow SHALL be modeled as a business-referenced ledger object

The system SHALL model escrow as an explicit object linked to a business reference, payer user, payee subject, platform ledger subject, amount, status, and expiry metadata.

#### Scenario: Escrow is created for business flow
- **WHEN** business module opens escrow-backed transaction
- **THEN** system creates escrow record linked to business reference
- **AND** escrow record stores payer, payee, amount, and current status

### Requirement: Escrow SHALL support explicit lifecycle states

The system SHALL support explicit escrow lifecycle states covering creation, lock, release, refund, forfeiture, and cancellation.

#### Scenario: Escrow is locked
- **WHEN** business workflow reaches irreversible commit point
- **THEN** system transitions escrow from `created` to `locked`
- **AND** escrow can no longer be cancelled by payer

#### Scenario: Escrow is released
- **WHEN** business workflow confirms successful fulfillment
- **THEN** system transitions escrow to `released`
- **AND** funds move from escrowed balance to payee balance

#### Scenario: Escrow is refunded
- **WHEN** business workflow authorizes refund before successful fulfillment
- **THEN** system transitions escrow to `refunded`
- **AND** funds move back to payer available balance

#### Scenario: Escrow is forfeited
- **WHEN** business workflow authorizes forfeiture such as deposit penalty
- **THEN** system transitions escrow to `forfeited`
- **AND** forfeited amount is transferred to configured counterparty such as platform ledger subject

### Requirement: Escrow transitions SHALL be idempotent and state-guarded

The system SHALL reject invalid escrow state transitions and SHALL treat repeated valid requests with same business idempotency reference as idempotent.

#### Scenario: Duplicate release retry arrives
- **WHEN** release operation is retried with same business idempotency reference
- **THEN** system returns existing release outcome
- **AND** system does not duplicate balance transfer

#### Scenario: Invalid transition is requested
- **WHEN** caller requests refund after escrow is already released
- **THEN** system rejects transition
- **AND** escrow state remains unchanged

### Requirement: Escrow expiry SHALL support timeout-driven resolution

The system SHALL support expiry metadata so that business modules can resolve overdue escrows through timeout rules such as auto-release or auto-refund.

#### Scenario: Overdue escrow is resolved by timeout workflow
- **WHEN** escrow passes configured expiry point and business timeout policy applies
- **THEN** system resolves escrow through allowed terminal transition
- **AND** resulting ledger movement is recorded

