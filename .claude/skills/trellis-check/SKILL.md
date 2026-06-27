---
name: trellis-check
description: "Comprehensive quality verification: spec compliance, lint, type-check, tests, cross-layer data flow, code reuse, and consistency checks. Use when code is written and needs quality verification, before committing changes, or to catch context drift during long sessions."
---

# Code Quality Check

Comprehensive quality verification for recently written code. Combines spec compliance, cross-layer safety, and pre-commit checks.

---

## Step 1: Identify What Changed

```bash
git diff --name-only HEAD
git status
```

## Step 2: Read Task Artifacts and Applicable Specs

Read the current task artifacts in order:

- `prd.md`
- `design.md` if present
- `implement.md` if present

```bash
python ./.trellis/scripts/get_context.py --mode packages
```

For each changed package/layer, read the spec index and follow its **Quality Check** section:

```bash
cat .trellis/spec/<package>/<layer>/index.md
```

Read the specific guideline files referenced — the index is a pointer, not the goal.

## Step 3: Run Project Checks

Run the project's lint, type-check, and test commands. Fix any failures before proceeding.

## Step 4: Run OCR Review Gate

Run Alibaba open-code-review as a hard external review gate for the current
diff. First preview the scope:

```bash
ocr review --preview --audience agent
```

OCR decides which changed files are reviewable. If the preview scope is
unexpectedly large or includes unrelated files, stop and resolve the diff scope
before spending LLM review calls. Files excluded by OCR preview are outside the
OCR gate; record them in the report and cover them through the normal Trellis
checks instead. If OCR previews reviewable files, run:

```bash
ocr review --audience agent
```

OCR findings are evidence candidates, not final authority. Verify each
material finding against the source code, task artifacts, and applicable specs
before fixing it or reporting it as a defect. Dismiss false positives
explicitly when they matter to the final decision.

Verified OCR findings are blocking. Fix the implementation, then rerun the
normal Trellis checks and the OCR gate until both are green.

OCR execution failure is also blocking by default. If `ocr` is missing,
misconfigured, times out, or cannot reach its model provider, report the exact
failure and keep the task out of commit/finish until the user explicitly
overrides the OCR gate for this task. If OCR previews no reviewable files,
mark the OCR gate as not applicable and continue the normal Trellis checks.
OCR never replaces lint, type-check, tests, task alignment, or spec review.

## Step 5: Review Against Checklist

### Implementation Drift

- [ ] Requirement alignment: code matches `prd.md` acceptance criteria?
- [ ] Design alignment: code matches `design.md` boundaries if present?
- [ ] Plan alignment: code follows `implement.md` steps if present?
- [ ] Spec alignment: code follows applicable `.trellis/spec/` rules?
- [ ] Result: aligned | design/spec defect | implementation drift | needs user decision
- [ ] Fix path: update design/spec first | fix implementation | ask user

Use `design/spec defect` when the written authority is wrong, missing, or
stale. Use `implementation drift` when the written authority is still correct
but the code diverged from it. Fix implementation drift directly. Return to
Trellis planning when the fix would change scope, acceptance, or design
boundaries.

### Code Quality

- [ ] Linter passes?
- [ ] Type checker passes (if applicable)?
- [ ] Tests pass?
- [ ] No debug logging left in?
- [ ] No suppressed warnings or type-safety bypasses?

### Test Coverage

- [ ] New function → unit test added?
- [ ] Bug fix → regression test added?
- [ ] Changed behavior → existing tests updated?

### Spec Sync

- [ ] Does `.trellis/spec/` need updates? (new patterns, conventions, lessons learned)

> "If I fixed a bug or discovered something non-obvious, should I document it so future me won't hit the same issue?" → If YES, update the relevant spec doc.

## Step 6: Cross-Layer Dimensions (if applicable)

Skip this step if your change is confined to a single layer.

### A. Data Flow (changes touch 3+ layers)

- [ ] Read flow traces correctly: Storage → Service → API → UI
- [ ] Write flow traces correctly: UI → API → Service → Storage
- [ ] Types/schemas correctly passed between layers?
- [ ] Errors properly propagated to caller?

### B. Code Reuse (modifying constants, creating utilities)

- [ ] Searched for existing similar code before creating new?
  ```bash
  grep -r "pattern" src/
  ```
- [ ] If 2+ places define same value → extracted to shared constant?
- [ ] After batch modification, all occurrences updated?

### C. Import/Dependency (creating new files)

- [ ] Correct import paths (relative vs absolute)?
- [ ] No circular dependencies?

### D. Same-Layer Consistency

- [ ] Other places using the same concept are consistent?

---

## Step 7: Report and Fix

Report violations found and fix them directly. Re-run project checks and the
OCR gate after fixes.
