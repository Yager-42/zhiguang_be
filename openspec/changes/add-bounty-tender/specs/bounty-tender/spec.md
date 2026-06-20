## ADDED Requirements

### Requirement: Bounty SHALL be a business object separate from knowpost and comment

The system SHALL model bounty as a distinct business object linked to a problem-oriented `knowpost`, and SHALL NOT reuse public `comment` entities as bids or submissions.

#### Scenario: Bounty is opened for problem post
- **WHEN** user opens bounty for eligible problem-oriented post
- **THEN** system creates bounty record linked to post
- **AND** bounty lifecycle is tracked independently from post lifecycle

### Requirement: Bounty bids SHALL be sealed structured records

The system SHALL store bounty bids as structured sealed records containing experience evidence, method path, milestone plan, quote, and system-generated reference signals. Bounty bids SHALL NOT expose full answer content before bounty lock.

#### Scenario: Bid is submitted before lock
- **WHEN** responder submits bounty bid
- **THEN** system stores bid as sealed structured record
- **AND** bid does not expose full answer content to requester before lock

### Requirement: Bounty submissions SHALL be separate from bounty bids

The system SHALL store final delivery content as a bounty submission object distinct from the original bounty bid.

#### Scenario: Winner submits final answer
- **WHEN** selected responder submits final delivery after bounty lock
- **THEN** system stores bounty submission separately from bounty bid
- **AND** submission is linked to winning bid and bounty

### Requirement: Tender award SHALL remain manual

The system SHALL require requester to manually select winning bid. Reverse-auction or quality-score computation MUST NOT automatically award the bounty.

#### Scenario: Quality score is available
- **WHEN** system computes bid quality score for submitted bids
- **THEN** requester can view score as reference
- **AND** bounty remains unawarded until requester manually selects winning bid

### Requirement: LLM signals SHALL be soft and fail-open

The system SHALL use LLM only for soft publication guidance and soft bid screening. LLM unavailability SHALL NOT block bounty publication, bid submission, or award workflow.

#### Scenario: LLM is unavailable during bounty publish
- **WHEN** requester submits bounty while LLM dependency is unavailable
- **THEN** system accepts bounty workflow without hard rejection
- **AND** records no irreversible decision from missing LLM output

### Requirement: Bounty settlement SHALL use lock-first no-dispute flow

The system SHALL settle bounty through escrow lock, winner delivery, requester confirmation or timeout auto-release, and runner default penalty. It SHALL NOT provide post-delivery arbitration refund in this phase.

#### Scenario: Requester confirms delivery
- **WHEN** winner submits delivery and requester confirms
- **THEN** system releases bounty escrow to winner
- **AND** returns eligible deposits for successful completion

#### Scenario: Requester times out after delivery
- **WHEN** winner submits delivery and requester does not act before configured timeout
- **THEN** system auto-releases bounty escrow to winner

#### Scenario: Winner defaults after lock
- **WHEN** winning responder fails to deliver within required deadline
- **THEN** system refunds bounty escrow to requester
- **AND** system forfeits winning responder deposit according to configured rule

### Requirement: Bounty bidding SHALL support responder deposit and platform subsidy

The system SHALL support responder deposit reservation for bids and platform-funded subsidy for configured qualifying bids or early-market incentive rules.

#### Scenario: Responder submits qualifying bid
- **WHEN** responder submits bounty bid requiring deposit
- **THEN** system reserves configured bid deposit
- **AND** system can append platform subsidy according to configured rule without changing requester bounty amount

### Requirement: Bounty reputation SHALL derive from completed bounty outcomes

The system SHALL record requester rating after terminal bounty completion and SHALL expose completed-bounty outcomes as reputation inputs for future bid quality evaluation.

#### Scenario: Completed bounty is rated
- **WHEN** bounty reaches successful terminal state
- **THEN** requester can rate winning responder
- **AND** resulting reputation signal becomes available to future bounty evaluation logic

### Requirement: Bounty content SHALL record timestamped proof hashes

The system SHALL persist timestamped content hashes for bids and submissions as proof records.

#### Scenario: Submission proof is stored
- **WHEN** responder submits bounty bid or final submission
- **THEN** system stores timestamped proof hash for submitted content
- **AND** proof record is linked to corresponding bounty object
