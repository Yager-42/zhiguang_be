# Implementation Plan

## Checklist

1. Update `.agents/skills/trellis-check/SKILL.md`.
   - Add an OCR external-review step after changed files are identified and before final report.
   - Require `ocr review --preview --audience agent` before `ocr review --audience agent`.
   - State that verified OCR findings block completion and return the task to implementation/fix work.
   - State that OCR decides its own review scope and excluded files are covered by normal Trellis checks.
   - State that OCR execution failure blocks completion unless the user explicitly overrides the gate for that task.
   - State that OCR does not replace lint, type-check, tests, or spec review.
   - State that OCR findings must be verified before fixing or reporting.

2. Update `.trellis/workflow.md`.
   - In the Codex inline `in_progress-inline` block, mention that `trellis-check` includes the OCR diff-review gate.
   - Make clear that failed OCR review returns the flow to implementation/fix work, then Phase 2.2 repeats.
   - Keep Trellis as the owner of the quality gate.

3. Update platform check definitions for consistency.
   - Add the same OCR rule to `.trellis/agents/check.md`.
   - Add the same OCR rule to `.codex/agents/trellis-check.toml`.

4. Verify local OCR command availability.
   - `ocr --help`
   - `ocr review --help`
   - `ocr review --preview --audience agent`
   - Record OCR preview scope and excluded files.

5. Verify Trellis artifacts remain readable.
   - `python3 ./.trellis/scripts/get_context.py --mode phase --step 2.2`
   - `python3 ./.trellis/scripts/task.py current --source`

## Rollback

Revert the narrow instruction edits in:

- `.agents/skills/trellis-check/SKILL.md`
- `.trellis/workflow.md`
- `.trellis/agents/check.md`
- `.codex/agents/trellis-check.toml`

No data migration or application rollback is needed.
