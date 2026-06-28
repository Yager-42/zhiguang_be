---
name: zhiguang-trellis-flow
description: "Project-local Trellis routing policy for zhiguang_be. Use when planning or executing Trellis tasks in this Java backend project, when deciding which enhancement skills to apply, or when bootstrapping/refining brownfield specs from existing code."
---

# Zhiguang Trellis Flow

Use this skill as the local routing layer around Trellis. Do not replace bundled Trellis skills. Keep Trellis as the backbone and apply enhancement skills only when their trigger is present.

## Backbone

Always preserve this flow:

```text
trellis-start
-> trellis-brainstorm
-> trellis-before-dev
-> implement
-> trellis-check
-> trellis-update-spec
-> trellis-finish-work
```

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

- Use `alibaba-java-coding-guidelines-skill` for Java, Spring, MyBatis, Maven, MySQL, SQL, logging, exception, transaction, DTO, mapper, or database work.
- Use `ponytail` during implementation to reject unnecessary abstraction, fallback paths, and framework ceremony.
- Use `tdd` when there is a clear behavior seam and the test can drive the change without excessive setup.
- Use `diagnosing-bugs` when failures are unclear, repeated, or cross-layer.

## Review Enhancers

- Keep `trellis-check` as the required review/check gate.
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
