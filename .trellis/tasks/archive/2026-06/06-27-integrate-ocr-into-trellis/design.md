# Design

## Summary

Integrate `ocr` as a hard external-review gate inside Trellis quality checking. The smallest reliable integration is documentation-driven: update the local Trellis check instructions so the checker previews OCR scope, runs a diff review, verifies OCR findings against the repository, fixes verified findings, and repeats the check loop until OCR and the normal Trellis checks are clean.

This avoids creating another workflow layer or storing secrets in the project. `ocr` keeps owning its provider configuration outside this repository, while Trellis keeps owning task lifecycle, specs, verification, commits, and finish flow.

## Workflow Shape

The loop belongs inside Phase 2, not in a new task status:

```text
Phase 2.1 Implement
-> Phase 2.2 Trellis check + OCR gate
   -> if lint/tests/spec/OCR fail: fix in Phase 2.1 and repeat Phase 2.2
   -> if all pass: continue to spec update, commit, and finish
```

This fits the existing Trellis rule that Phase 2.2 is repeatable: “If issues are found -> fix -> re-check, until green.” OCR becomes one more required green condition inside that loop.

## Integration Point

Primary integration target: `.agents/skills/trellis-check/SKILL.md`.

Reason: Codex is currently using inline dispatch, and the workflow-state block for `in_progress-inline` already routes post-edit verification through `trellis-check`. Updating the skill makes the instruction visible at the point where code review, lint, type-check, and tests are already required.

Secondary synchronization target: `.trellis/workflow.md`.

Reason: the inline workflow block currently describes the required check gate. It should mention OCR as a hard external review gate inside `trellis-check` so the high-level flow and detailed check skill do not diverge.

Platform check agents: `.trellis/agents/check.md` and `.codex/agents/trellis-check.toml`.

Reason: these can bypass the local skill in non-inline or channel flows. The minimum implementation can either update them with the same OCR rule or explicitly leave them out. For consistency, the preferred design updates them narrowly.

## OCR Command Contract

Use diff review by default:

```bash
ocr review --preview --audience agent
ocr review --audience agent
```

Use `ocr scan` only for a task that intentionally needs full-file or directory review, because it is broader and can spend more tokens.

Use JSON output later only if a script or automated parser is added:

```bash
ocr review --audience agent --format json
```

## Failure Handling

OCR is an external AI review, but this task intentionally makes it a hard gate for files OCR selects in preview. Missing installation, provider misconfiguration, model errors, network failures, and timeouts are reported as `OCR: failed`, with the command output summarized. Files excluded by OCR preview are recorded as outside OCR scope and covered by normal Trellis checks. The checker should continue running normal Trellis verification where possible, but the task must not proceed to commit while the OCR gate is failed.

The only bypass is an explicit user override for a specific task. The override must be mentioned in the final check report so the skipped gate is visible.

Current OCR limitation: Trellis instruction files such as `.agents/skills/trellis-check/SKILL.md`, `.trellis/workflow.md`, and `.trellis/agents/check.md` are excluded by OCR v1.6.4 as `unsupported_ext`. That is acceptable for this integration because OCR owns its own review scope; Trellis remains responsible for checking instruction and workflow files.

The normal Trellis check remains mandatory: task alignment, spec alignment, lint, type-check, tests, and changed-code review. OCR does not replace any of those checks.

## Finding Handling

OCR findings are not accepted blindly. The checker must verify each material OCR finding against source code, task artifacts, and specs before fixing or reporting it. False positives should be recorded as dismissed or non-actionable when relevant.

Verified OCR findings block the check. The task returns to implementation/fix work when a fix is possible, then the full check and OCR review run again. The loop ends only when there are no verified OCR findings and the normal Trellis checks pass.

This matches the existing Trellis guidance that AI review findings are evidence candidates, not final authority.

## Secret Handling

No API keys, provider URLs, or model credentials are written into the repository. Provider setup remains in the user's local `ocr config`.

## Out of Scope

- Building a wrapper script around `ocr`.
- Enforcing OCR as a CI gate outside Trellis.
- Parsing JSON output into Trellis task artifacts.
- Changing application source code.
- Committing OCR provider configuration.
