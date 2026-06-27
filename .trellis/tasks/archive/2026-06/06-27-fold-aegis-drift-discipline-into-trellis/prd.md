# Fold Aegis drift discipline into Trellis

## Goal

Simplify the current Trellis + Aegis hybrid workflow by removing Aegis as an
execution layer while preserving the useful drift controls inside native
Trellis checkpoints.

## Requirements

- Trellis remains the sole workflow and project-management backbone.
- Aegis-specific execution artifacts and mandatory slice planning are removed
  from the normal project workflow.
- Aegis should be fully removed from the active project exposure surface, not
  merely disconnected from the current Trellis workflow.
- Remove active Aegis setup/discovery/configuration from README and `.codex/`.
- Remove Aegis-projected Codex skills while preserving non-Aegis project skills
  such as OpenSpec skills.
- Trellis must still guard against architecture drift: wrong owner,
  source-of-truth confusion, contract boundary drift, fallback growth,
  duplicate owners, and stale path retention.
- Trellis must still guard against implementation drift: code diverging from
  `prd.md`, `design.md`, `implement.md`, or `.trellis/spec/`.
- The drift checks should be lightweight enough to avoid recreating a
  Superpowers/Aegis-style process layer.
- `.trellis/spec/` remains the durable source for reusable implementation
  contracts and conventions.
- One-off execution notes, failed attempts, and temporary reasoning should not
  become durable spec content.

## Acceptance Criteria

- [x] Trellis workflow documentation no longer routes normal inline execution
      through Aegis.
- [x] Project-local Trellis skills no longer require Aegis slice plans,
      `docs/aegis/plans/`, or `docs/aegis/work/` for normal development.
- [x] `trellis-before-dev` or equivalent pre-development guidance includes a
      compact architecture-drift check.
- [x] `trellis-check` or equivalent quality gate includes a compact
      implementation-drift check and distinguishes design/spec defects from
      implementation drift.
- [x] `trellis-update-spec` guidance continues to capture only reusable
      project knowledge, not one-off execution traces.
- [x] References to Aegis that remain in the repo are either removed, clearly
      marked historical, or explicitly out of the active workflow.
- [x] The resulting workflow can be followed without reading Aegis skills.
- [x] Historical archived Trellis tasks may still mention Aegis as past work,
      but no active workflow, setup path, or Codex discovery surface depends on
      it.

## Notes

- User intent from discussion: keep the value of Aegis's ADD/TDD/reflection
  discipline, but not the token-heavy Aegis/Superpowers-like process.
- "ADD" value is interpreted here as architecture-drift prevention, not as
  importing Aegis artifacts or terminology wholesale.
- User confirmed the intended removal scope is "complete removal" from the
  project active surface, not only severing the Trellis workflow bridge.
- Sentrux and Repowise may be useful as optional codebase-analysis sensors, but
  they should not become the owner of Trellis drift governance in this task.
  The native Trellis checks remain the authoritative workflow rule.
