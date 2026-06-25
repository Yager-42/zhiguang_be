# Customize Trellis skill routing

## Goal

Adapt the local Trellis workflow so daily work in this Java backend project uses project-relevant enhancement skills without forking or copying the upstream Trellis skills.

Extend this customization so Trellis remains the project/task-governance owner,
while Aegis becomes the task execution owner after Trellis planning artifacts
are ready.

## Requirements

- Keep the Trellis workflow skeleton intact: task state, PRD/design/implement artifacts, spec loading, checks, spec update, and finish-work.
- Add a project-local routing skill that documents when to use Matt skills, Alibaba Java coding guidelines, code review guidance, TDD, ponytail, and brownfield spec bootstrap.
- Update local workflow-state guidance so Codex inline sessions know which enhancement skills to consider during planning and implementation.
- Treat enhancement skills as conditional triggers, not mandatory steps for every task.
- Avoid editing bundled Trellis skill bodies; local Trellis updates should remain maintainable across future `trellis update` runs.
- Preserve Trellis as the only owner of project governance, task lifecycle, and
  planning artifacts.
- Replace Trellis-owned task execution flow with an Aegis execution bridge.
- Keep `implement.md` as the authoritative top-level execution plan, while
  allowing Aegis to refine execution at the step/slice level only.
- Keep `trellis-before-dev`, `trellis-check`, `trellis-update-spec`, and
  `trellis-finish-work` in the final integrated flow.

## Acceptance Criteria

- [x] `.agents/skills/zhiguang-trellis-flow/SKILL.md` exists and describes the project-specific skill routing policy.
- [x] `.trellis/workflow.md` references `zhiguang-trellis-flow` from planning and in-progress inline workflow states.
- [x] Brownfield bootstrap guidance routes through `trellis-spec-bootstrap` plus Alibaba Java, codebase design, and domain modeling where applicable.
- [x] Daily task guidance keeps `trellis-before-dev`, `trellis-check`, `trellis-update-spec`, and `trellis-finish-work` as the Trellis backbone.
- [x] Workflow text makes clear that Alibaba and code-review skills enhance existing stages instead of becoming standalone mandatory phases.
- [x] Current task now includes `design.md` and `implement.md` defining the
  Trellis + Aegis ownership split.
- [x] A new bridge skill documents how Trellis planning artifacts hand
  execution to Aegis.
- [x] Inline in-progress workflow text now states `trellis-before-dev` ->
  Aegis execution -> Trellis finish stages.
- [x] Workflow text now makes clear that `implement.md` remains the
  authoritative top-level execution plan.

## Notes

- This task started as a lightweight workflow customization and expanded into a
  Trellis + Aegis execution ownership integration design.
