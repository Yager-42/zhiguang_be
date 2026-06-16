# publish-relation-architecture Specification

## Purpose
TBD - created by archiving change align-publish-relation-architecture. Update Purpose after archive.
## Requirements
### Requirement: Publish and relation chains SHALL use manager orchestration

The system SHALL route publish and relation use cases through explicit manager components that own orchestration, idempotency decisions, state transitions, fallback choices, and coordination between helpers, DAOs, publishers, and clients.

#### Scenario: Publish request enters the system

- **WHEN** a client submits a publish request
- **THEN** the controller delegates the use case to the publish manager
- **AND** the publish manager coordinates validation, idempotency, state transition, persistence, and event publication through narrower collaborators

#### Scenario: Relation write enters the system

- **WHEN** a client follows or unfollows another user
- **THEN** the controller delegates the use case to the relation manager
- **AND** the relation manager coordinates state checks, persistence, outbox/event work, cache behavior, and fallback through narrower collaborators

### Requirement: Controllers SHALL depend directly on managers

Publish and relation controllers SHALL depend on manager components directly, and the old publish/relation service entry abstractions MUST be removed or reduced so they do not duplicate manager orchestration responsibility.

#### Scenario: Controller dependencies are wired

- **WHEN** the Spring application context starts
- **THEN** publish and relation controllers are wired with manager dependencies
- **AND** no controller requires `KnowPostService` or `RelationService` for the migrated use cases

### Requirement: Publish API SHALL accept publish attempts asynchronously

The publish endpoint SHALL return `202 Accepted` with a `publishAttemptId` when a publish request is accepted into the publish state machine. `202 Accepted` SHALL NOT imply that the post has already reached `published`.

#### Scenario: Publish request is accepted

- **WHEN** an author submits a valid publish request for a draft
- **THEN** the response status is `202 Accepted`
- **AND** the response body contains `publishAttemptId`
- **AND** the post enters `publishing`
- **AND** final success or failure is exposed through publish status

### Requirement: Publish requests SHALL require client idempotency

Publish requests SHALL require a client-provided idempotency key. Repeated publish requests from the same author for the same post with the same key SHALL resolve to the same publish attempt rather than creating duplicate attempts or duplicate side effects.

#### Scenario: Duplicate publish request uses same key

- **WHEN** the same author submits the same post publish request with the same idempotency key more than once
- **THEN** the system returns the existing `publishAttemptId`
- **AND** the system does not create a duplicate publish attempt

#### Scenario: Publish request omits idempotency key

- **WHEN** a publish request does not include the required idempotency key
- **THEN** the system rejects the request before creating a publish attempt

### Requirement: Publish status and retry SHALL be exposed

The system SHALL expose publish status query and retry endpoints based on `publishAttemptId` and post state. Retry SHALL be allowed only when the previous publish attempt is in a retryable failed state.

#### Scenario: Client queries publish status

- **WHEN** a client queries publish status for a known attempt
- **THEN** the system returns the attempt status, post status, failed step when available, and retry eligibility

#### Scenario: Client retries failed publish

- **WHEN** a client retries a publish attempt that is in a retryable failed state
- **THEN** the system accepts the retry
- **AND** returns a `publishAttemptId` for the retry execution

### Requirement: Critical publish flow SHALL complete after acceptance

After a publish request is accepted, critical publish work SHALL run under the publish manager/executor and eventually transition the attempt and post to a final state.

#### Scenario: Critical publish succeeds

- **WHEN** an accepted attempt completes critical publish validation and persistence
- **THEN** the attempt becomes `succeeded`
- **AND** the post changes from `publishing` to `published`

#### Scenario: Critical publish fails

- **WHEN** an accepted attempt fails a critical fact write or validation
- **THEN** the attempt becomes `failed`
- **AND** the post changes from `publishing` to `publish_failed`
- **AND** the status response exposes the failed step and retry eligibility

### Requirement: Relation writes SHALL remain naturally idempotent

Follow and unfollow operations SHALL remain idempotent without requiring a client idempotency key. The system SHALL derive idempotency from actor, target, action, current relation state, and database constraints.

#### Scenario: Duplicate follow request

- **WHEN** a user follows the same target more than once
- **THEN** the relation state remains followed
- **AND** duplicate side effects are not emitted

#### Scenario: Duplicate unfollow request

- **WHEN** a user unfollows the same target more than once
- **THEN** the relation state remains unfollowed
- **AND** duplicate side effects are not emitted

### Requirement: Execution resources SHALL be isolated by chain

The system SHALL use separate named executors for publish work, relation event handling, Canal/Outbox consumption, and reconciliation work so that saturation in one chain does not exhaust the others.

#### Scenario: Relation event executor saturates

- **WHEN** relation event handling is saturated
- **THEN** publish work continues to use its own executor
- **AND** Canal/Outbox bridge work continues to use its own executor

#### Scenario: Canal bridge is wired

- **WHEN** Canal/Outbox bridge work starts
- **THEN** it runs on the Canal/Outbox executor rather than the generic application executor

### Requirement: External dependencies SHALL be protected by Sentinel-backed guard abstractions

Calls to external or failure-prone dependencies in the migrated chains SHALL be protected through local guard interfaces backed by Sentinel. Business managers SHALL depend on the local guard abstractions rather than Sentinel-specific APIs or annotations as their primary contract.

#### Scenario: Guarded dependency degrades

- **WHEN** a guarded dependency is rate-limited, circuit-broken, or degraded by Sentinel
- **THEN** the local guard returns the configured fallback outcome or raises a classified system failure
- **AND** manager code remains independent of Sentinel-specific implementation details

### Requirement: Business exceptions SHALL not be counted as system degradation failures

Expected business validation failures, such as missing permission, invalid state, duplicate relation state, or malformed input, SHALL not be counted as Sentinel system failures for circuit-breaking or degradation.

#### Scenario: Invalid publish state is rejected

- **WHEN** a publish request targets a post state that cannot be published
- **THEN** the system rejects the request as a business failure
- **AND** the resilience guard does not record it as a dependency or system failure

### Requirement: Fallback behavior SHALL separate critical facts from derivative tasks

The publish manager SHALL distinguish critical publish facts from derivative work. Critical failures SHALL prevent or fail the publish attempt; derivative failures SHALL create retry or reconciliation work without rolling back already-published content.

#### Scenario: Critical publish persistence fails

- **WHEN** a critical publish state transition or durable attempt record fails
- **THEN** the publish attempt is not reported as successful
- **AND** the publish status does not report successful publication for that transition

#### Scenario: Derived indexing task fails after publish

- **WHEN** content has already been marked published
- **AND** a derived task such as search indexing fails
- **THEN** the content remains published
- **AND** the system records retry or reconciliation work

