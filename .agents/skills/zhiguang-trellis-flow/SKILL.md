---
name: zhiguang-trellis-flow
description: "Project-local Trellis routing policy for zhiguang_be. Use when planning or executing Trellis tasks in this Java backend project, when deciding which enhancement skills to apply, or when bootstrapping/refining brownfield specs from existing code."
---

# Zhiguang Trellis Flow

Use this skill as the local routing layer around Trellis. Do not replace
bundled Trellis skills. Keep Trellis as the project backbone and the only
active workflow owner.

## Backbone

Always preserve this flow:

```text
trellis-start
-> trellis-brainstorm
-> trellis-before-dev
-> Trellis execution
-> trellis-check
-> trellis-update-spec
-> trellis-finish-work
```

Ownership:

- Trellis owns project governance, task lifecycle, `prd.md`, `design.md`,
  `implement.md`, `.trellis/spec/`, execution flow, post-implementation check,
  spec update, commit, and finish flow.
- `prd.md` remains the requirement and acceptance authority.
- `design.md` remains the technical design authority for complex tasks.
- `implement.md` remains the authoritative top-level execution plan.
- `.trellis/spec/` remains the durable source for reusable implementation
  contracts and conventions.

Do not create a second project-management or execution-artifact layer in normal
development. Task-local notes belong in Trellis task artifacts or the final
report; reusable knowledge belongs in `.trellis/spec/`.

## Drift Discipline

Trellis carries the lightweight drift checks for this repository:

- before editing, `trellis-before-dev` runs the Architecture Drift Check:
  owner, source of truth, boundary, fallback/old path, and decision
- after editing, `trellis-check` runs the Implementation Drift Check:
  requirement, design, plan, spec alignment, result, and fix path
- after the task, `trellis-update-spec` records only reusable project knowledge

If the check finds a design or spec defect, update the authority first. If the
authority is still correct but code diverged from it, fix the implementation.
If the difference changes scope or acceptance, return to Trellis planning.

## Brownfield Bootstrap

Use this path when `.trellis/spec/` is missing, stale, template-like, or no
longer matches the Java backend code:

```text
trellis-spec-bootstrap
+ alibaba-java-coding-guidelines-skill
+ codebase-design
+ domain-modeling
```

Extract rules from real source code first. Write project-specific contracts
into `.trellis/spec/`; do not invent idealized standards that the repository
does not follow.

## Planning Enhancers

- Use `grilling` when user intent, scope, acceptance criteria, or risk
  tolerance is unclear.
- Use `domain-modeling` when business terms, entity ownership, state
  transitions, or ubiquitous language are unclear.
- Use `codebase-design` when module boundaries, interfaces, dependency
  direction, or test seams are the main risk.
- Use `junior-to-senior` only after a complex `design.md` or `implement.md`
  exists and needs hard review.

## Development Guidance

- Load `trellis-before-dev` before source edits.
- Follow existing Java, Spring, MyBatis, Maven, MySQL, SQL, logging, exception,
  transaction, DTO, mapper, and database conventions from `.trellis/spec/`.
- Keep implementation scoped to the active Trellis task artifacts.
- If `implement.md` is too vague to execute safely, return to Trellis planning
  instead of creating a competing execution plan.
- Prefer the simplest stable implementation that preserves the stated owner,
  source of truth, and boundary.

## Review Guidance

- Keep `trellis-check` as the required review/check gate.
- Changed code review must cover correctness, architecture, performance, tests,
  maintainability, and applicable Java/database conventions.
- Treat review findings as evidence candidates. Verify them against the actual
  code before prioritizing.
- Use `review` only when the user asks to review work since a branch, commit,
  tag, or merge-base.

## Spec Capture

Use `trellis-update-spec` after each task. Update `.trellis/spec/` only when the
task produced reusable knowledge:

- new or changed API, command, database, message, cache, or environment contract
- repeated bug class or non-obvious failure mode
- stable project convention discovered in existing code
- testing rule required for future changes

Do not write one-off implementation notes, failed attempts, or temporary
execution reasoning into spec.
