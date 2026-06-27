# Fold Aegis Drift Discipline into Trellis Implementation Plan

## Preconditions

- User has approved the planning artifacts.
- Task is started with `task.py start` before implementation begins.
- `trellis-before-dev` is loaded before editing active workflow files.

## Execution State

- Active step: complete
- Completed steps: Steps 1-8
- Next candidate: final Trellis finish
- Notes: Aegis active surfaces removed; OpenSpec skills preserved.

## Anti-Entropy Declaration

- Deletion Class: code-retirement
- Old Path/Object: Aegis execution bridge, vendored Aegis method pack, Aegis
  setup/config/docs, and Aegis-projected Codex skills
- New Canonical Owner: Trellis workflow, Trellis skills, and `.trellis/spec/`
- Expected Preserved Behavior: Trellis planning, execution, check, spec update,
  commit, finish flow, Codex hooks, Trellis sub-agents, OpenSpec skills
- Expected Retired Behavior: Aegis routing, Aegis setup, Aegis slice plans,
  Aegis work records, Aegis skill discovery in this project
- External Boundary Touched: no
- Source-of-Truth Data Risk: none
- User Confirmation Required: no

## Step 1: Update Trellis Inline Workflow

Files:

- `.trellis/workflow.md`

Changes:

- Replace the `workflow-state:in_progress-inline` Aegis flow with native Trellis
  inline flow:
  `trellis-before-dev -> direct Trellis execution -> trellis-check -> trellis-update-spec -> commit -> finish-work`.
- Remove Aegis slice plan, CodeGraph-first Aegis handoff, explicit Aegis
  approval, and `aegis:verification-before-completion` requirements from the
  active inline breadcrumb.
- Keep the existing rule that `implement.md` is the top-level execution
  authority.
- Add a short note that inline execution must use the pre-development
  architecture drift check and post-development implementation drift check.

Verification:

```bash
rg -n "trellis-aegis-execution|Aegis task execution|docs/aegis|aegis:" .trellis/workflow.md
```

Expected: no active inline workflow references remain.

## Step 2: Rewrite Project-Local Trellis Routing

Files:

- `.agents/skills/zhiguang-trellis-flow/SKILL.md`

Changes:

- Change the backbone to native Trellis:
  `trellis-start -> trellis-brainstorm -> trellis-before-dev -> Trellis
  execution -> trellis-check -> trellis-update-spec -> trellis-finish-work`.
- Remove Aegis ownership split, artifact rules, mandatory slice plans, and Aegis
  execution enhancer routing.
- Add a `Drift Discipline` section describing:
  - architecture drift check before edits
  - implementation drift check during review
  - spec capture only for reusable knowledge
- Keep useful planning enhancers and Java/check review guidance if still
  available and not Aegis-specific.

Verification:

```bash
rg -n "Aegis|aegis|trellis-aegis|docs/aegis|Planless Slice|Slice Card" .agents/skills/zhiguang-trellis-flow/SKILL.md
```

Expected: no active Aegis routing remains.

## Step 3: Add Native Drift Checks to Trellis Skills

Files:

- `.agents/skills/trellis-before-dev/SKILL.md`
- `.agents/skills/trellis-check/SKILL.md`
- `.agents/skills/trellis-update-spec/SKILL.md` if needed

Changes:

- Add the compact Architecture Drift Check to `trellis-before-dev`.
- Add the compact Implementation Drift Check to `trellis-check`.
- In `trellis-update-spec`, tighten the wording that one-off execution notes,
  failed attempts, and temporary reasoning do not belong in `.trellis/spec/`.

Verification:

```bash
rg -n "Architecture Drift Check|Implementation Drift Check|design/spec defect|implementation drift|one-off" .agents/skills/trellis-before-dev/SKILL.md .agents/skills/trellis-check/SKILL.md .agents/skills/trellis-update-spec/SKILL.md
```

Expected: checks are present and compact.

## Step 4: Remove Aegis Bridge Skill

Files:

- `.agents/skills/trellis-aegis-execution/`

Changes:

- Delete the `trellis-aegis-execution` skill directory.
- Confirm no active Trellis workflow references it.

Verification:

```bash
test ! -e .agents/skills/trellis-aegis-execution
rg -n "trellis-aegis-execution" .agents .trellis README.md docs .codex 2>/dev/null
```

Expected: only archived task history may mention it.

## Step 5: Remove Project Aegis Setup and Vendored Pack

Files/directories:

- `.codex/aegis/`
- `.codex/aegis-config.toml`
- `.codex/README.aegis-local.md`
- `.codex/scripts/setup-aegis.ps1`
- `docs/aegis/`
- README Aegis setup section

Changes:

- Delete project-local Aegis method-pack and setup/config/docs.
- Remove README instructions for Aegis setup.
- Keep `.codex/config.toml`, `.codex/hooks.json`, `.codex/hooks/`, and
  `.codex/agents/` unless they contain only Aegis behavior.

Verification:

```bash
test ! -e .codex/aegis
test ! -e .codex/aegis-config.toml
test ! -e .codex/scripts/setup-aegis.ps1
test ! -e docs/aegis
rg -n "setup-aegis|aegis-config|\\.codex/aegis|docs/aegis" README.md .codex docs 2>/dev/null
```

Expected: no active setup references remain.

## Step 6: Remove Aegis-Projected Codex Skills, Preserve OpenSpec

Files/directories:

- `.codex/skills/<aegis-skill>/`

Delete only directories that also exist under `.codex/aegis/skills/` before
Step 5 deletion, currently:

- `anti-entropy-governance`
- `brainstorming`
- `communicating-concisely`
- `dispatching-parallel-agents`
- `establishing-project-context`
- `executing-plans`
- `finishing-a-development-branch`
- `first-principles-review`
- `goal-framing`
- `long-task-continuation`
- `receiving-code-review`
- `recording-architecture-decisions`
- `requesting-code-review`
- `subagent-driven-development`
- `systematic-debugging`
- `test-driven-development`
- `update-aegis`
- `using-aegis`
- `using-git-worktrees`
- `verification-before-completion`
- `writing-plans`
- `writing-skills`

Preserve OpenSpec skill directories:

- `openspec-*`

Verification:

```bash
find .codex/skills -mindepth 1 -maxdepth 1 -type d | sort
```

Expected: only intended non-Aegis project skills remain, currently OpenSpec.

## Step 7: Active Reference Sweep

Commands:

```bash
rg -n "Aegis|aegis|trellis-aegis|docs/aegis|setup-aegis|aegis-config|using-aegis|TDD Route|Design Defect|Implementation Drift" .agents .trellis README.md docs .codex 2>/dev/null
```

Expected:

- no active workflow/setup/discovery references remain
- archived task history may still mention Aegis
- new Trellis drift checks may mention implementation drift terms only if they
  are part of the native Trellis check wording

## Step 8: Validate Trellis Task and Workflow

Commands:

```bash
python3 ./.trellis/scripts/task.py validate .trellis/tasks/06-27-fold-aegis-drift-discipline-into-trellis
python3 ./.trellis/scripts/get_context.py --mode phase
python3 ./.trellis/scripts/get_context.py --mode packages
git diff -- .trellis/workflow.md .agents/skills README.md .codex docs/aegis .trellis/tasks/06-27-fold-aegis-drift-discipline-into-trellis
```

Expected:

- task validates
- phase context no longer instructs inline Codex to enter Aegis
- package/spec context still works
- diff shows only workflow/process cleanup and planning artifacts

## Rollback

If removal breaks Trellis startup or Codex hooks:

- restore `.trellis/workflow.md` and affected `.agents/skills/*` from git
- restore deleted `.codex` Aegis files only if the project explicitly chooses to
  return to the hybrid workflow
- do not partially restore Aegis setup while leaving Trellis workflow native
