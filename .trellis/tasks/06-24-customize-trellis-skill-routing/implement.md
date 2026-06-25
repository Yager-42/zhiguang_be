# Implement Trellis + Aegis Integration

## Goal

Apply the approved integration design so Trellis planning hands execution to
Aegis and then resumes Trellis finish/governance stages after Aegis completes
task execution.

## Execution State

- Active step: none
- Completed steps:
  - Task 1 - Update local Trellis routing policy
  - Task 2 - Update Trellis workflow breadcrumbs
  - Task 3 - Add Aegis execution bridge skill
  - Task 4 - Align execution enhancers
  - Task 5 - Review integration wording
- Next candidate: integration dry run on a real task
- Notes: This convention exists so `trellis-aegis-execution` can identify the
  current top-level execution unit without guessing. The design slice for this
  task is complete; the next meaningful step is a real-task handoff trial.

## Execution Order

1. Update the local routing policy to define the new ownership split clearly.
2. Update Trellis workflow breadcrumbs so inline Codex sessions follow the new
   execution handoff.
3. Add a project-local Aegis execution bridge skill.
4. Wire the workflow text so Aegis replaces Trellis-owned implementation flow,
   while `trellis-before-dev` and Trellis finish stages stay intact.
5. Review for authority conflicts and wording drift.

## Tasks

### Task 1: Update local Trellis routing policy

Files:

- `.agents/skills/zhiguang-trellis-flow/SKILL.md`

Work:

- add the Trellis vs Aegis ownership split
- define that Trellis planning artifacts remain authoritative
- define that Aegis replaces Trellis execution ownership
- specify which current execution enhancers should migrate into Aegis execution

Validation:

- read the updated skill and confirm it states:
  - Trellis plans
  - Aegis executes
  - Trellis finishes

### Task 2: Update Trellis workflow breadcrumbs

Files:

- `.trellis/workflow.md`

Work:

- change inline in-progress guidance so it no longer describes Trellis as the
  default implementation engine
- preserve `trellis-before-dev`
- hand execution to the Aegis bridge
- preserve `trellis-check`, `trellis-update-spec`, commit, and finish-work

Validation:

- inspect `workflow-state:in_progress-inline`
- confirm Phase 2 ownership is Aegis execution

### Task 3: Add Aegis execution bridge skill

Files:

- new `.agents/skills/trellis-aegis-execution/SKILL.md`

Work:

- define bridge input:
  - current task
  - `prd.md`
  - `design.md`
  - `implement.md`
  - relevant `.trellis/spec/`
- define bridge rules:
  - `implement.md` is top-level authority
  - Aegis may refine but not expand top-level task scope
  - return to planning if `implement.md` is insufficient
- define route matrix for:
  - goal clarification
  - debugging
  - TDD
  - execution review / verification

Validation:

- read the bridge skill and confirm the authority boundary is explicit

### Task 4: Align execution enhancers

Files:

- `.agents/skills/zhiguang-trellis-flow/SKILL.md`
- `.trellis/workflow.md`
- bridge skill if needed

Work:

- document migration intent for current execution enhancers:
  - `ponytail`
  - `tdd`
  - `diagnosing-bugs`
- keep planning-only and finish-only enhancers in Trellis-owned stages

Validation:

- verify no workflow text still implies Trellis owns the default implementation
  loop

### Task 5: Review integration wording

Files:

- all modified workflow/routing/bridge files

Work:

- remove any duplicated plan authority
- remove any wording that lets Aegis replace Trellis finish stages
- ensure `trellis-before-dev` is still mandatory before execution

Validation:

- manual read-through of modified files

## Review Gate

Before considering implementation complete, confirm:

- `implement.md` remains the sole top-level execution authority
- Aegis is clearly an execution-layer engine, not project-task authority
- Trellis still owns planning artifacts and finish stages

## Final Validation

- inspect changed workflow text
- inspect changed routing skill text
- inspect bridge skill text
- ensure the resulting flow reads as:

```text
Trellis planning -> trellis-before-dev -> Aegis execution -> Trellis finish
```
