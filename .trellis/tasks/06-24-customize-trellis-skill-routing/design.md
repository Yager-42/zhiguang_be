# Trellis + Aegis Integration Design

## Goal

Integrate Aegis into the existing customized Trellis workflow by replacing
Trellis-owned task execution with an Aegis-owned execution layer, while keeping
Trellis as the sole owner of project governance, task artifacts, and spec
capture.

## Design Decision

Trellis remains the project workflow backbone. Aegis becomes the task execution
engine.

The resulting ownership split is:

- Trellis owns:
  - task lifecycle
  - `prd.md`, `design.md`, `implement.md`
  - `.trellis/spec/`
  - current-task/session state
  - post-implementation check, spec update, commit, and finish flow
- Aegis owns:
  - task execution routing
  - task-level implementation method
  - debugging method
  - TDD route selection
  - task-level completion verification discipline

## Workflow Shape

Target flow:

```text
user request
-> Trellis planning
-> task artifacts (`prd.md`, `design.md`, `implement.md`)
-> `trellis-before-dev`
-> Aegis execution bridge
-> Aegis task execution + task-level verification
-> Trellis finish stages
-> `trellis-check`
-> `trellis-update-spec`
-> commit
-> `trellis-finish-work`
```

Trellis Phase 2 no longer performs its own default implementation workflow.
Instead, it hands execution ownership to an Aegis bridge skill.

## Artifact Authority

### Upper-layer plan: `implement.md`

`implement.md` remains the only authoritative task execution plan.

It is responsible for:

- execution order
- file/module boundaries
- validation commands
- review gates
- rollback points
- dependency order between steps

It is intentionally coarser-grained and project-governance-oriented.

### Lower-layer plan: Aegis execution plan

Aegis may generate a finer-grained execution plan for the current task step or
slice, but that plan is subordinate to `implement.md`.

It is responsible for:

- current slice goal
- current slice non-goals
- exact edit sequence
- TDD step order when applicable
- exact verification for the slice
- canonical owner targeting
- repair/retirement decisions for the slice

Hard rules:

- Aegis execution plans may only refine `implement.md`.
- They may not expand task scope or invent new top-level steps.
- If `implement.md` is too vague to execute safely, execution must stop and the
  flow must return to Trellis planning to revise `implement.md`.

## `trellis-before-dev` Role

`trellis-before-dev` is retained.

Reason:

- it is not Trellis's implementation method
- it is the project authority/spec loader before code changes
- it injects project-local coding standards, package/layer guidance, and shared
  guides

In the integrated flow, `trellis-before-dev` runs before Aegis execution begins.

## Post-execution Trellis Stages

The following Trellis stages remain owned by Trellis:

- `trellis-check`
- `trellis-update-spec`
- commit / finish-work

Reason:

- these are project governance stages
- they decide reusable knowledge capture
- they close the project task lifecycle

Aegis may perform task-level `verification-before-completion`, but it does not
replace Trellis's project-level finish stages.

## Skill Migration Policy

### Stays in Trellis planning/governance layer

- `trellis-start`
- `trellis-brainstorm`
- `trellis-before-dev`
- `trellis-check`
- `trellis-update-spec`
- `trellis-finish-work`
- planning enhancers such as `grilling`, `domain-modeling`, `to-issues`,
  `junior-to-senior`

### Moves into Aegis execution layer

- task execution routing
- bug execution lane
- TDD route selection
- first-principles reconsideration during implementation
- repair/retirement governance during implementation

### Execution enhancers that should be evaluated for migration

- `ponytail`
- current `tdd`
- `diagnosing-bugs`

These should be mapped into the Aegis execution layer instead of staying as
Trellis-owned implementation behavior.

Initial migration decision:

- `ponytail`: keep, but move under Aegis execution as an implementation-style
  constraint
- current `tdd`: demote from Trellis-owned execution owner; prefer
  `aegis:test-driven-development`
- `diagnosing-bugs`: demote from Trellis-owned bug-execution owner; prefer
  `aegis:systematic-debugging`

### Remains available as cross-cutting review enhancers

- `alibaba-java-coding-guidelines-skill`
- `code-review-skill`

These may enhance Aegis execution and Trellis finish stages, but they do not
replace either backbone.

## Bridge Skill Contract

Introduce a project-local bridge skill:

`trellis-aegis-execution`

Its job is to:

1. read current Trellis task artifacts
2. load relevant `.trellis/spec/` guidance already prepared by
   `trellis-before-dev`
3. classify the execution lane
4. route into the correct Aegis execution skill(s)
5. keep Aegis subordinate to `implement.md`
6. return control to Trellis after task implementation and task-level
   verification

## Step State Convention

To avoid ambiguous current-step selection, Trellis tasks should carry a light
execution-status convention inside `implement.md`.

Recommended shape:

```text
Execution State:
- Active step:
- Completed steps:
- Next candidate:
- Notes:
```

Rules:

- `Active step` is the current top-level execution unit handed to Aegis
- `Completed steps` lists top-level steps already finished
- `Next candidate` is optional and only used when the next step is already
  known but not yet active
- if `Active step` is missing, the bridge may fall back to the first unfinished
  ordered task, but explicit state is preferred

This convention is intentionally document-level, not tool-level. It avoids
changing Trellis task scripts in the first integration slice.

## Initial Scope

First integration slice should cover:

- workflow ownership changes
- bridge skill introduction
- Aegis execution routing for:
  - `goal-framing`
  - `brainstorming` as execution-prep only when required by current task step
  - `systematic-debugging`
  - `test-driven-development`
  - `verification-before-completion`

Deferred until later:

- Aegis workspace persistence
- Aegis `docs/aegis/` project records
- full migration of every existing Trellis enhancer
- aggressive subagent orchestration changes

## Risks

- duplicated authority if Aegis is allowed to create peer plans beside
  `implement.md`
- blurred finish ownership if Aegis completion claims replace Trellis finish
  stages
- execution confusion if old Trellis implementation guidance remains active
  alongside the new bridge

## Guardrails

- Trellis remains the only project/task authority
- `implement.md` remains the only authoritative top-level execution plan
- `trellis-before-dev` remains mandatory before execution
- Aegis owns execution method only
- Trellis owns finish and spec capture
