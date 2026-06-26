# Trellis Aegis Governance Hardening Design

## Scope

This task changes only the project-local Trellis + Aegis governance layer in
`zhiguang_be`. It does not change vendored Aegis generic defaults.

In-scope owner surfaces:

- `.agents/skills/trellis-aegis-execution/SKILL.md`
- `.agents/skills/zhiguang-trellis-flow/SKILL.md`
- `.agents/skills/trellis-before-dev/SKILL.md` if the handoff wording is too weak
- `.trellis/workflow.md`
- `.trellis/spec/` only if a reusable local convention needs to be captured

Out of scope:

- `.codex/aegis/skills/...`
- vendored Aegis artifact semantics for every repo
- business feature code

## One-Sentence Design

Replace the current soft bridge between Trellis and Aegis with a hard
project-local execution contract: every slice must load fixed-order authority
context, gather CodeGraph-backed code facts, write a durable slice plan, pause
for explicit user approval, then execute and append task-local evidence.

## Authority Model

### Trellis Owns

- `prd.md`
- `design.md`
- `implement.md`
- `.trellis/spec/`
- task lifecycle
- finish stages:
  - `trellis-check`
  - `trellis-update-spec`
  - commit
  - `trellis-finish-work`

### Aegis Owns

- execution method
- slice-level refinement of the active `implement.md` step
- execution-time debugging / TDD / verification discipline

### Hard Boundary

- `implement.md` remains the only top-level execution authority.
- Aegis may refine but may not expand task scope, top-level phases, or final
  acceptance boundaries.
- Aegis may not start a slice without:
  - fixed-order context load
  - CodeGraph-first code fact gathering
  - durable slice plan in `docs/aegis/plans/`
  - explicit user approval
- Aegis may not claim execution ownership in closeout without plan/work
  evidence.

## Artifact Ownership

### Trellis Artifacts Kept As-Is

- `prd.md`: requirement authority
- `design.md`: technical design authority
- `implement.md`: top-level execution authority
- `.trellis/spec/`: project conventions and executable contracts

### Aegis Artifacts Kept In This Repo

- `docs/aegis/plans/`
  - one file per slice
  - derived from the active `implement.md` step only
  - the user review object before slice execution
- `docs/aegis/work/YYYY-MM-DD-<task-slug>/`
  - one directory per task
  - append slice checkpoints / evidence / completion trace into the same task
    record

### Aegis Artifact Families Disabled In This Repo

- `docs/aegis/specs/`
- `docs/aegis/baseline/`
- `docs/aegis/adr/`

Reason:

- `specs/` duplicates Trellis `prd.md` / `design.md`
- `baseline/` duplicates `.trellis/spec/` as project current-truth owner
- `adr/` would overlap with Trellis design/spec authority unless this repo
  later introduces a separate ADR system intentionally

## Slice Lifecycle

### Required Slice Sequence

Every slice must follow this order:

1. identify the active `implement.md` step
2. load authority/context in this exact order:
   - current task path
   - `prd.md`
   - `design.md`
   - `implement.md`
   - relevant `.trellis/spec/` already loaded by `trellis-before-dev`
   - CodeGraph facts for the slice
   - only if CodeGraph is insufficient, minimum necessary file search/read
3. derive a slice plan
4. save the slice plan to `docs/aegis/plans/...`
5. present the slice plan to the user
6. pause until explicit user approval
7. execute the slice
8. append task-local work evidence into `docs/aegis/work/YYYY-MM-DD-<task-slug>/...`
9. run task-level verification
10. return to Trellis finish stages when the authorized boundary is complete

### Explicit Approval Gate

Allowed continue condition:

- direct approval semantics such as `允许`, `继续`, or equivalent explicit
  approval wording

Not allowed:

- silence
- soft positive sentiment
- agent inference
- ambiguous wording that is not explicitly defined as approval

### Rejection Routing

If the user rejects a slice plan:

- if the rejection changes only slice-local implementation method:
  - stay in Aegis slice planning
  - rewrite the current slice plan
- if the rejection changes:
  - scope
  - acceptance
  - top-level `implement.md` step structure
  - design boundary
  then:
  - return to Trellis planning
  - revise `prd.md`, `design.md`, or `implement.md`

### Planless Lane Removal

This repo disables `Planless Slice Lane`.

Implication:

- no conversation-only `Slice Card`
- no tiny-slice exception
- every slice must have durable `docs/aegis/plans/...`

## Skill Routing Changes

### `trellis-aegis-execution`

Strengthen the bridge to require:

- durable slice plan creation
- explicit user approval before slice execution
- fixed-order authority/context load
- CodeGraph-first fact gathering
- explicit fallback to Trellis planning when rejection crosses top-level
  boundaries
- no execution-ownership claims without plan/work evidence
- no `Planless Slice Lane`

### `zhiguang-trellis-flow`

Strengthen the project-local routing layer to:

- restate that this repo keeps only `plans/` and `work`
- forbid planless slice execution
- define per-slice pause-for-user as local execution policy
- clarify that project-local bridge policy overrides generic Aegis artifact
  minimalism here
- remove `to-issues` as a default planning enhancer in this repo
- require `alibaba-java-coding-guidelines-skill` for matching Java / Spring /
  MyBatis / Maven / MySQL / SQL / DTO / mapper / database work
- require `code-review-skill` (`codexreview`) inside the review/check gate for
  changed code

### `.trellis/workflow.md`

Strengthen `workflow-state:in_progress-inline` so it still preserves:

- `trellis-before-dev`
- `trellis-aegis-execution`
- Aegis execution
- `trellis-check`
- `trellis-update-spec`
- commit
- `trellis-finish-work`

But now also states:

- slice plan is mandatory
- explicit user approval is mandatory
- CodeGraph-first evidence is mandatory
- planless lane is banned in zhiguang_be
- the Alibaba Java guideline skill is mandatory for matching Java/database work
- `code-review-skill` is mandatory in `trellis-check` for changed code review

### `trellis-before-dev`

Only change this if the current wording is too weak to make spec outputs a real
bridge input. If changed, add one explicit note that Aegis slice planning must
cite the relevant spec outputs loaded here instead of treating the step as a
fire-and-forget preamble.

## Compatibility Rules

Must preserve:

- Trellis remains sole owner of planning artifacts
- `implement.md` remains top-level authority
- `trellis-before-dev` remains mandatory before code changes
- Aegis still owns execution method, not project governance
- finish stages remain Trellis-owned

Must change:

- durable artifact creation becomes mandatory where this repo needs
  auditability
- generic “natural visibility only” no longer counts as enough for execution
  claims here
- local planning no longer routes through `to-issues` by default
- Java/database execution and changed-code review become mandatory local gates

## Risks

### Risk 1: Slower Local Flow

Every slice now pauses.

Decision:

- accept the slower path intentionally
- optimize for control and auditability over velocity

### Risk 2: Text Drift Across Owner Files

If bridge, routing, and workflow wording drift apart, the ambiguity returns.

Decision:

- update all owner surfaces in one task
- cross-read all changed files before `task.py start`

### Risk 3: Generic Aegis Still Mentions Broader Artifact Families

Vendored Aegis will still mention planless lanes and extra artifact families.

Decision:

- do not modify vendored defaults now
- make the project-local bridge and routing layers explicitly override them in
  this repo

## Verification Strategy

Planning-stage verification:

- read updated bridge / routing / workflow files together
- confirm no surviving text allows:
  - planless slice execution
  - non-explicit approval
  - missing durable plan
  - missing CodeGraph-first fact gathering
  - optional Alibaba/changed-code review gates where this repo now requires
    them
- confirm artifact owner boundaries remain non-overlapping:
  - Trellis owns `prd/design/implement/spec`
  - Aegis owns `plans/work` only

Execution-stage verification later:

- when trialing a real task, prove one slice produces:
  - a durable plan file
  - a pause for user approval
  - task-local work evidence
  - no execution before approval
