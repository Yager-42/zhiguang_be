# Harden Trellis Aegis Execution Governance Implementation Plan

## Goal

Apply the approved governance hardening so zhiguang_be's local Trellis + Aegis
bridge becomes deterministic, auditable, user-gated at every slice, and
explicit about mandatory local enhancer/review gates.

## Execution State

- Active step: none
- Completed steps: none
- Next candidate: Task 1 - tighten local bridge authority

## Execution Order

1. Tighten the project-local bridge contract in `trellis-aegis-execution`.
2. Tighten the project-local routing policy in `zhiguang-trellis-flow`.
3. Tighten inline workflow breadcrumbs in `.trellis/workflow.md`.
4. Review whether `trellis-before-dev` needs a stronger explicit handoff statement.
5. Add or update project spec text only if a reusable local convention appears
   beyond task-local docs.
6. Cross-read all changed authority files for wording drift and owner conflicts.

## Tasks

### Task 1: Tighten Local Bridge Authority

Files:

- `.agents/skills/trellis-aegis-execution/SKILL.md`

Work:

- replace soft execution-plan wording with mandatory slice plan rules
- require `docs/aegis/plans/...` per slice
- require `docs/aegis/work/...` task-local execution evidence
- require fixed-order authority/context load before slice planning
- require CodeGraph-first code fact gathering
- require explicit user approval before slice execution
- define rejection routing:
  - slice-local rewrite
  - or return to Trellis planning for top-level boundary changes
- explicitly ban `Planless Slice Lane` in this repo
- explicitly ban execution-ownership claims without plan/work evidence

Validation:

- re-read the updated skill and confirm these hard gates are explicit:
  - durable plan
  - explicit approval
  - fixed context order
  - CodeGraph first
  - planless lane banned
  - rejection routing
  - no unsupported closeout claim

### Task 2: Tighten Project-Local Routing Policy

Files:

- `.agents/skills/zhiguang-trellis-flow/SKILL.md`

Work:

- update artifact policy so this repo keeps only `plans/` and `work`
- state that every slice pauses for explicit user approval
- state that project-local bridge policy overrides generic Aegis minimal
  artifact paths here
- forbid `specs/`, `baseline/`, `adr/`, and planless lane in the default local
  execution flow
- remove `to-issues` from the default planning enhancer list
- require `alibaba-java-coding-guidelines-skill` for matching Java/database
  execution work
- require `code-review-skill` (`codexreview`) in the review/check gate

Validation:

- re-read the skill and confirm:
  - Trellis owns `prd/design/implement/spec`
  - Aegis owns `plans/work`
  - `to-issues` is no longer part of the local default planning path
  - mandatory enhancer/review gates are explicit

### Task 3: Tighten Workflow Breadcrumbs

Files:

- `.trellis/workflow.md`

Work:

- update `workflow-state:in_progress-inline`
- preserve existing phase order
- add mandatory per-slice plan + approval semantics
- add CodeGraph-first execution-prep semantics
- make it impossible to read the inline workflow as “Aegis may silently proceed”
- make mandatory Java/database enhancer and changed-code review gates explicit

Validation:

- inspect `workflow-state:in_progress-inline`
- confirm the execution contract is stronger than the current bridge-neutral wording
- confirm workflow wording no longer frames Alibaba / code-review as optional
  enhancers where this repo now requires them

### Task 4: Check `trellis-before-dev` Handoff Boundary

Files:

- `.agents/skills/trellis-before-dev/SKILL.md` only if needed

Work:

- decide whether the current wording is sufficient
- if insufficient, add one explicit note that Aegis slice planning must cite
  the relevant spec outputs loaded here rather than treating
  `trellis-before-dev` as a fire-and-forget step

Validation:

- confirm the pre-dev loader plus bridge now read as one continuous authority chain

### Task 5: Review Drift and Authority Conflicts

Files:

- all files modified above

Work:

- remove duplicate or contradictory wording
- ensure no file implies Aegis may replace Trellis top-level planning
- ensure no file reopens disabled Aegis artifact families
- ensure no file leaves approval semantics ambiguous
- ensure `code-review-skill` refers to the existing skill of that exact name
- ensure no local planning text still routes through `to-issues`

Validation:

- manual cross-read of all modified authority files

## Validation Commands

Use these during implementation review:

```powershell
rg -n "Planless Slice Lane|Slice Card|explicit user approval|CodeGraph|docs/aegis/plans|docs/aegis/work|specs/|baseline/|adr/|to-issues|code-review-skill|alibaba-java-coding-guidelines-skill" .agents .trellis
```

```powershell
git diff -- .agents/skills/trellis-aegis-execution/SKILL.md .agents/skills/zhiguang-trellis-flow/SKILL.md .agents/skills/trellis-before-dev/SKILL.md .trellis/workflow.md
```

```powershell
python .trellis/scripts/task.py validate .trellis/tasks/06-26-trellis-aegis-governance-hardening
```

## Risky Files

- `.agents/skills/trellis-aegis-execution/SKILL.md`
- `.agents/skills/zhiguang-trellis-flow/SKILL.md`
- `.trellis/workflow.md`
- `.agents/skills/trellis-before-dev/SKILL.md`

## Review Gate Before `task.py start`

- [ ] `prd.md` contains the explicit user decisions from this planning session
- [ ] `design.md` clearly separates Trellis authority from Aegis authority
- [ ] `implement.md` only targets project-local files, not vendored Aegis defaults
- [ ] all mandatory hard gates are represented in the plan
- [ ] mandatory Alibaba / changed-code review gates are represented in local routing/workflow text

## Rollback Points

- if local bridge wording starts conflicting with `.trellis/workflow.md`, stop
  and fix text consistency before implementation starts
- if a rule requires changing vendored Aegis defaults to make local governance
  work, stop and return to planning rather than quietly expanding scope
