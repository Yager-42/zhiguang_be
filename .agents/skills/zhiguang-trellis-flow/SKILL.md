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
- Aegis `plans/` are execution-layer expansions of the current Trellis
  `implement.md` step. They are derived plans, not authority-bearing project
  plans.
- Aegis `work/` records follow native Aegis lifecycle rules for checkpoints,
  evidence, drift checks, and proof bundles.
- Do not let Aegis create project-local `docs/aegis/specs/`, `baseline/`, or
  `adr/` artifacts as part of the normal Trellis-integrated execution flow
  unless a future project rule explicitly re-enables them.

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
- Use `to-issues` only for large work that should become independently verifiable child tasks.
- Use `junior-to-senior` only after a complex `design.md` or `implement.md` exists and needs hard review.

## Development Enhancers

Execution ownership belongs to Aegis. Trellis should hand task execution to
`trellis-aegis-execution` after `trellis-before-dev`.

Inside Aegis execution, prefer:

- `alibaba-java-coding-guidelines-skill` for Java, Spring, MyBatis, Maven, MySQL, SQL, logging, exception, transaction, DTO, mapper, or database work
- `ponytail` as an execution-style enhancer to reject unnecessary abstraction,
  fallback paths, and framework ceremony
- `aegis:test-driven-development` as the primary TDD route owner
- `aegis:systematic-debugging` as the primary bug-execution owner

Artifact rule inside execution:

- Let Aegis decide creation timing for `docs/aegis/plans/` and
  `docs/aegis/work/` using its own workflow thresholds.
- Preserve the authority rule:
  - Trellis `implement.md` = top-level task plan
  - Aegis `plans/` = more detailed execution expansion of the active
    `implement.md` step
  - Aegis `work/` = execution trace under native Aegis rules

Migration intent:

- the current Trellis-owned implementation use of `ponytail`, `tdd`, and
  `diagnosing-bugs` should move under the Aegis execution layer instead of
  remaining Trellis-owned implementation behavior

## Review Enhancers

- Keep `trellis-check` as the required review/check gate.
- Run `aegis:verification-before-completion` inside the Aegis execution layer
  before returning to Trellis finish stages.
- Use `code-review-skill` inside the check gate for changed code review: correctness, architecture, performance, tests, and maintainability.
- Use `alibaba-java-coding-guidelines-skill` inside the check gate for Java/Spring/MyBatis/SQL compliance.
- Use `review` only when the user asks to review work since a branch, commit, tag, or merge-base.

## Spec Capture

Use `trellis-update-spec` after each task. Update `.trellis/spec/` only when the task produced reusable knowledge:

- new or changed API, command, database, message, or environment contract
- repeated bug class or non-obvious failure mode
- stable project convention discovered in existing code
- testing rule required for future changes

Do not write one-off implementation notes into spec.
