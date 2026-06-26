---
name: zhiguang-trellis-flow
description: "Project-local Trellis routing policy for zhiguang_be. Use when planning or executing Trellis tasks in this Java backend project, when deciding which enhancement skills to apply, or when bootstrapping/refining brownfield specs from existing code."
---

# Zhiguang Trellis Flow

Use this skill as the local routing layer around Trellis. Do not replace bundled Trellis skills. Keep Trellis as the project backbone, and let Aegis own task execution method after Trellis planning artifacts are ready.

## Backbone

Always preserve this flow:

```text
trellis-start
-> trellis-brainstorm
-> trellis-before-dev
-> aegis task execution
-> trellis-check
-> trellis-update-spec
-> trellis-finish-work
```

Ownership split:

- Trellis owns project governance, task lifecycle, `prd.md`, `design.md`,
  `implement.md`, `.trellis/spec/`, post-implementation check, spec update,
  commit, and finish flow.
- Aegis owns task execution method after Trellis planning is complete.
- `implement.md` remains the only authoritative top-level execution plan.
  Aegis may refine the current slice, but may not replace or expand the
  top-level Trellis task plan.
- In this project-local integrated mode, keep Aegis durable artifacts limited
  to:
  - `docs/aegis/plans/`
  - `docs/aegis/work/`
- Aegis `plans/` are mandatory per-slice execution-layer expansions of the
  current Trellis `implement.md` step. They are derived plans, not
  authority-bearing project plans.
- Aegis `work/` records are mandatory task-local execution evidence.
- Every slice must pause after its durable plan is written and wait for
  explicit user approval before implementation starts.
- Project-local bridge policy is authoritative in this repo: generic Aegis
  artifact minimalism does not override these local requirements.
- Do not let Aegis create project-local `docs/aegis/specs/`, `baseline/`, or
  `adr/` artifacts as part of the normal Trellis-integrated execution flow
  unless a future project rule explicitly re-enables them.
- Do not allow `Planless Slice Lane` in this repo.
- Do not allow conversation-only `Slice Card` execution in place of
  `docs/aegis/plans/...`.

## Brownfield Bootstrap

Use this path when `.trellis/spec/` is missing, stale, template-like, or no longer matches the Java backend code:

```text
trellis-spec-bootstrap
+ alibaba-java-coding-guidelines-skill
+ codebase-design
+ domain-modeling
```

Extract rules from real source code first. Write project-specific contracts into `.trellis/spec/`; do not invent idealized standards that the repository does not follow.

## Planning Enhancers

- Use `grilling` when user intent, scope, acceptance criteria, or risk tolerance is unclear.
- Use `domain-modeling` when business terms, entity ownership, state transitions, or ubiquitous language are unclear.
- Use `codebase-design` when module boundaries, interfaces, dependency direction, or test seams are the main risk.
- Use `junior-to-senior` only after a complex `design.md` or `implement.md` exists and needs hard review.

## Development Enhancers

Execution ownership belongs to Aegis. Trellis should hand task execution to
`trellis-aegis-execution` after `trellis-before-dev`.

Inside Aegis execution, prefer:

- `alibaba-java-coding-guidelines-skill` as a mandatory execution gate for
  Java, Spring, MyBatis, Maven, MySQL, SQL, logging, exception, transaction,
  DTO, mapper, or database work
- `ponytail` as an execution-style enhancer to reject unnecessary abstraction,
  fallback paths, and framework ceremony
- `aegis:test-driven-development` as the primary TDD route owner
- `aegis:systematic-debugging` as the primary bug-execution owner

Artifact rule inside execution:

- Every slice must create `docs/aegis/plans/...` before implementation.
- Every task must append execution evidence under `docs/aegis/work/...`.
- Every slice must read context in this order before plan writing:
  - current task path
  - `prd.md`
  - `design.md`
  - `implement.md`
  - relevant `.trellis/spec/` loaded by `trellis-before-dev`
  - CodeGraph facts
  - only if CodeGraph is insufficient, minimum extra file reads
- Every slice must pause for explicit user approval after the plan is written.
- Preserve the authority rule:
  - Trellis `implement.md` = top-level task plan
  - Aegis `plans/` = more detailed execution expansion of the active
    `implement.md` step
  - Aegis `work/` = execution trace and evidence for the task

Migration intent:

- the current Trellis-owned implementation use of `ponytail`, `tdd`, and
  `diagnosing-bugs` should move under the Aegis execution layer instead of
  remaining Trellis-owned implementation behavior

## Review Enhancers

- Keep `trellis-check` as the required review/check gate.
- Run `aegis:verification-before-completion` inside the Aegis execution layer
  before returning to Trellis finish stages.
- Require `code-review-skill` inside the check gate for changed code review:
  correctness, architecture, performance, tests, and maintainability.
- Require `alibaba-java-coding-guidelines-skill` inside the check gate for
  Java/Spring/MyBatis/SQL compliance.
- Use `review` only when the user asks to review work since a branch, commit, tag, or merge-base.

## Spec Capture

Use `trellis-update-spec` after each task. Update `.trellis/spec/` only when the task produced reusable knowledge:

- new or changed API, command, database, message, or environment contract
- repeated bug class or non-obvious failure mode
- stable project convention discovered in existing code
- testing rule required for future changes

Do not write one-off implementation notes into spec.
