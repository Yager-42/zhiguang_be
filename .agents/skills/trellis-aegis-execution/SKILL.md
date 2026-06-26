---
name: trellis-aegis-execution
description: "Bridge Trellis task artifacts into Aegis task execution after Trellis planning is complete. Use when a Trellis task is in progress and execution ownership should move from Trellis to Aegis."
---

# Trellis Aegis Execution

Use this skill as the execution bridge between Trellis planning and Aegis task
execution.

## Execute

1. Read the active Trellis task and artifacts in the declared input order.
2. Identify the current top-level `implement.md` step to execute.
3. Check whether that step is execution-ready under the authority rules.
4. If it is not execution-ready, stop and return to Trellis planning with the
   smallest concrete reason.
5. If it is execution-ready, gather slice code facts with CodeGraph first.
6. Write a durable lower-layer Aegis slice plan for the current step only.
7. Present that slice plan to the user and pause.
8. Continue only after explicit user approval.
9. Route to the smallest correct Aegis execution skill(s) for that approved
   slice only.
10. Append durable execution evidence into task-local `docs/aegis/work/...`.
11. Complete task-level verification with
    `aegis:verification-before-completion`.
12. Return control to Trellis finish stages.

## Purpose

Trellis owns project governance and planning artifacts. Aegis owns task
execution method.

This bridge reads the active Trellis task context, keeps `implement.md` as the
authoritative top-level execution plan, and routes the current task into the
smallest correct Aegis execution workflow.

## Inputs

Read in this order:

1. current Trellis task path
2. `prd.md`
3. `design.md` if present
4. `implement.md` if present
5. relevant `.trellis/spec/` guidance already loaded through
   `trellis-before-dev`
6. CodeGraph facts for the current slice
7. only if CodeGraph is insufficient, the minimum necessary file search/read

If `implement.md` contains an `Execution State:` section, treat it as the
preferred source for current-step selection.

## Authority Rules

- `implement.md` is the only authoritative top-level execution plan.
- Aegis may refine the current step or slice.
- Aegis may not invent new top-level task scope, execution phases, or
  acceptance boundaries that conflict with `implement.md`.
- In this integrated workflow, Aegis durable artifact ownership is limited to:
  - `docs/aegis/plans/`
  - `docs/aegis/work/`
- Every slice must create one durable `docs/aegis/plans/...` file before any
  implementation begins.
- Every slice plan is a derived execution expansion of the active
  `implement.md` step, not a replacement project plan.
- Every task that enters Aegis execution must keep durable execution evidence
  under one task-local `docs/aegis/work/YYYY-MM-DD-<slug>/...` directory.
- Do not create `docs/aegis/specs/`, `docs/aegis/baseline/`, or
  `docs/aegis/adr/` in the default Trellis-integrated execution flow unless a
  project rule explicitly authorizes them.
- If `implement.md` is too vague to execute safely, stop and return to Trellis
  planning instead of improvising a peer plan.
- This repo disables `Planless Slice Lane`.
- Conversation-only `Slice Card` text is not an allowed substitute for a
  durable slice plan file.
- Aegis may not execute a slice without:
  - fixed-order context load
  - CodeGraph-first fact gathering
  - durable slice plan
  - explicit user approval
- Aegis may not claim execution ownership or successful handoff in closeout
  without durable plan/work evidence.

Return to Trellis planning when any of these is true:

- the current `implement.md` step does not identify a stable owner/file/module
  boundary
- the current step needs a new top-level task step
- the current step requires a changed acceptance boundary
- the current step forces a new architecture or contract decision not already
  captured by `design.md` or `implement.md`
- the current step cannot be refined into an executable slice without guessing

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

## Route Matrix

- Goal/stop-condition unclear for the current task or step:
  - use `aegis:goal-framing`
- Current work is a bug, regression, failure, or unexpected behavior:
  - use `aegis:systematic-debugging`
- Current step explicitly requires strict test-first execution or already
  carries a strict TDD route:
  - use `aegis:test-driven-development`
- Current implementation path shows repeated patching, owner confusion,
  fallback growth, or architecture doubt:
  - use `aegis:first-principles-review`
- Current step touches deletion of old paths, compatibility retention, fallback
  retirement, or source-of-truth transfer:
  - use `aegis:anti-entropy-governance`

Default rule:

- execute the current `implement.md` step using the smallest needed Aegis
  method

## Enhancer Ownership

Execution-layer enhancers belong under this bridge, not under Trellis-owned
implementation flow.

Apply them as follows:

- `ponytail`
  - use as the default implementation-style constraint during Aegis execution
- current `tdd`
  - do not keep as the primary execution owner; prefer
    `aegis:test-driven-development`
- `diagnosing-bugs`
  - do not keep as the primary bug-execution owner; prefer
    `aegis:systematic-debugging`
- `alibaba-java-coding-guidelines-skill`
  - mandatory for Java, Spring, MyBatis, Maven, MySQL, SQL, DTO, mapper, or
    database work in this repo
  - also keep available in Trellis finish/check stages

Planning-only and finish-only enhancers stay outside this bridge.

## Execution Plan Layering

Two layers are allowed:

- upper layer: Trellis `implement.md`
- lower layer: Aegis slice execution refinement

Durable Aegis execution artifacts are also allowed:

- `docs/aegis/plans/...md`
- `docs/aegis/work/YYYY-MM-DD-<slug>/...`

with these meanings:

- `plans/` = expanded execution detail for the active Trellis step
- `work/` = native Aegis execution trace and evidence trail

The lower layer may define:

- current slice goal
- current slice non-goals
- exact edit order
- exact test/verification order
- exact repair/retirement handling for the slice

The lower layer may not redefine:

- whole-task scope
- top-level step order
- final task acceptance boundary

Every slice plan must be durable, reviewable, and use this lower-layer shape:

```text
Aegis Slice Plan:
- Parent implement step:
- Slice goal:
- Slice non-goals:
- Authority refs:
- Relevant specs:
- Code facts / CodeGraph evidence:
- Files / owners:
- Edit order:
- Verification order:
- TDD route:
- Retirement / compatibility note:
- Stop condition:
```

If the bridge cannot fill this shape without guessing, return to Trellis
planning instead of executing.

Pause after writing the slice plan. Do not implement until the user gives
explicit approval semantics such as `允许`, `继续`, or equivalent direct
approval wording. Silence, soft agreement, or agent inference do not count.

## Current Step Selection

Choose the current step from `implement.md` using this order:

1. `Execution State -> Active step`, when present
2. the first not-yet-completed ordered task in `implement.md`
3. if the task document already identifies an active step elsewhere, use that
4. if the next step is ambiguous, stop and return to Trellis planning

Do not merge multiple top-level `implement.md` steps into one Aegis slice unless
`implement.md` already defines them as one bounded execution unit.

## Completion Rule

Before returning control to Trellis finish stages:

1. confirm the executed slice had a durable plan and explicit user approval
2. confirm task-local work evidence was appended
3. complete task-level implementation work for the current authorized step
4. run `aegis:verification-before-completion`
5. return control to:
   - `trellis-check`
   - `trellis-update-spec`
   - commit / finish flow

## Output Contract

When useful, report:

```text
Aegis Execution Handoff:
- Current task:
- Current implement step:
- Implement authority source:
- Authority refs loaded:
- Aegis route selected:
- Slice plan created:
- User approval received:
- Slice boundary:
- Work evidence path:
- Verification completed:
- Return to Trellis stage:
```
