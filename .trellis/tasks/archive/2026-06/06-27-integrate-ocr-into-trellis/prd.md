# Integrate open-code-review into Trellis workflow

## Goal

Add Alibaba open-code-review (`ocr`) as a local AI review gate in the Trellis quality workflow, without replacing Trellis planning, specs, project checks, or human judgment.

The immediate value is to make “run an external diff review before finish/commit, fix findings, and re-review until clean” a repeatable Trellis loop instead of an ad hoc command the developer has to remember.

## Confirmed Facts

- `ocr` is installed locally as `open-code-review v1.6.4`.
- `ocr review` reviews staged, unstaged, and untracked workspace changes by default.
- `ocr review --audience agent` produces summary-style output intended for agent consumption.
- `ocr review --preview --audience agent` works in this repository and shows which changed files would be reviewed before any LLM call.
- `ocr review --format json` is available if later automation needs structured output.
- `ocr scan` can review whole files or paths, but it is broader and more expensive than a diff review.
- The current Codex Trellis flow runs inline: `trellis-before-dev` -> direct execution -> `trellis-check` -> `trellis-update-spec` -> commit -> finish.
- The main local check instruction is `.agents/skills/trellis-check/SKILL.md`; channel and Codex sub-agent check definitions also exist, but Codex is currently configured for inline work.

## Requirements

- Keep Trellis as the workflow owner. OCR must be a hard quality gate inside Trellis, not a second task system or replacement for `trellis-check`.
- Add a repeatable OCR step to the local quality workflow, preferably inside `trellis-check`.
- Default to reviewing diffs with `ocr review`, not scanning the whole repository.
- Require `ocr review --preview --audience agent` before a real OCR review, so the reviewer can see scope and avoid accidental large LLM runs.
- Use `ocr review --audience agent` for normal agent-facing review output.
- Let OCR decide which changed files are in its review scope.
- Treat OCR findings as evidence candidates. The AI must verify findings against actual code before changing priority, reporting them as defects, or making fixes.
- Treat verified OCR findings as blocking defects. If OCR produces verified findings, return to implementation/fix mode, then rerun the full Trellis check and OCR review.
- Record files excluded by OCR preview and cover them through normal Trellis checks; do not block only because OCR excluded unsupported files.
- Treat OCR execution failure as a blocked gate by default. The task cannot proceed to commit until OCR passes, unless the user explicitly overrides the gate for that task.
- Do not store API keys or provider secrets in the repository.
- Do not add wrapper scripts unless the workflow needs behavior that the CLI cannot express directly.

## Acceptance Criteria

- [ ] `.agents/skills/trellis-check/SKILL.md` tells the checker when and how to run OCR.
- [ ] The check flow includes `ocr review --preview --audience agent` before `ocr review --audience agent`.
- [ ] The check flow states that OCR findings must be verified against source code and task artifacts before being accepted.
- [ ] The check flow states that verified OCR findings block completion and send the task back to implementation/fix mode.
- [ ] The check flow states that OCR decides its review scope and excluded files are handled by normal Trellis checks.
- [ ] The check flow states that OCR execution failure blocks completion unless the user explicitly overrides the OCR gate for that task.
- [ ] `.trellis/workflow.md` remains consistent with the OCR-enhanced check flow.
- [ ] Any platform-specific check agent that can bypass `.agents/skills/trellis-check/SKILL.md` is either updated or explicitly left out of scope.
- [ ] The implementation is verified by checking `ocr --help`, `ocr review --help`, and `ocr review --preview --audience agent`.

## Notes

- This task changes local Trellis workflow instructions, not application Java code.
- OCR review is useful as a second-pass reviewer, but model output can be wrong. Trellis remains responsible for verifying what is actually actionable before blocking on it.
- In this task, OCR preview reviews the `.codex/agents/trellis-check.toml` change and excludes `.md` Trellis instruction files as `unsupported_ext`; those excluded files remain covered by normal Trellis checks.
