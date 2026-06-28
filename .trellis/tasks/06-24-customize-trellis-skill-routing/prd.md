# Customize Trellis skill routing

## Goal

Adapt the local Trellis workflow so daily work in this Java backend project uses project-relevant enhancement skills without forking or copying the upstream Trellis skills.

## Requirements

- Keep the Trellis workflow skeleton intact: task state, PRD/design/implement artifacts, spec loading, checks, spec update, and finish-work.
- Add a project-local routing skill that documents when to use Matt skills, Alibaba Java coding guidelines, code review guidance, TDD, ponytail, and brownfield spec bootstrap.
- Update local workflow-state guidance so Codex inline sessions know which enhancement skills to consider during planning and implementation.
- Treat enhancement skills as conditional triggers, not mandatory steps for every task.
- Avoid editing bundled Trellis skill bodies; local Trellis updates should remain maintainable across future `trellis update` runs.

## Acceptance Criteria

- [x] `.agents/skills/zhiguang-trellis-flow/SKILL.md` exists and describes the project-specific skill routing policy.
- [x] `.trellis/workflow.md` references `zhiguang-trellis-flow` from planning and in-progress inline workflow states.
- [x] Brownfield bootstrap guidance routes through `trellis-spec-bootstrap` plus Alibaba Java, codebase design, and domain modeling where applicable.
- [x] Daily task guidance keeps `trellis-before-dev`, `trellis-check`, `trellis-update-spec`, and `trellis-finish-work` as the Trellis backbone.
- [x] Workflow text makes clear that Alibaba and code-review skills enhance existing stages instead of becoming standalone mandatory phases.

## Notes

- This is a lightweight workflow customization. PRD-only planning is enough.
