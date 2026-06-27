# Fold Aegis Drift Discipline into Trellis Design

## Scope

This task changes the project workflow and agent-facing process files only. It
does not change Java backend product code.

In scope:

- `.trellis/workflow.md`
- `.agents/skills/zhiguang-trellis-flow/SKILL.md`
- `.agents/skills/trellis-before-dev/SKILL.md`
- `.agents/skills/trellis-check/SKILL.md`
- `.agents/skills/trellis-update-spec/SKILL.md` if wording needs tightening
- `.agents/skills/trellis-aegis-execution/`
- README / docs that describe project-local Aegis setup
- `.codex/` Aegis configuration, setup script, vendored method pack, and Aegis
  skill projections

Out of scope:

- archived Trellis task records under `.trellis/tasks/archive/`
- business feature docs unrelated to workflow setup
- user-level Codex or Aegis configuration outside this repository
- live data, database schema, or runtime persistence

## Design Decision

Remove Aegis as an active execution layer and keep Trellis as the only workflow
owner.

The useful Aegis discipline is kept as native Trellis checks:

- architecture drift check before implementation
- implementation drift check after implementation
- reusable learning capture in `.trellis/spec/`

No Aegis artifact family remains part of normal development. Trellis artifacts
stay authoritative:

- `prd.md` owns requirements and acceptance
- `design.md` owns technical design for complex tasks
- `implement.md` owns execution order and validation plan
- `.trellis/spec/` owns durable implementation contracts and conventions

External analysis tools such as Sentrux or Repowise can feed evidence into
these checks, but they should not own the checks. Their role is sensor/context
provider, not workflow authority.

## Drift Discipline Mapping

### Architecture Drift

Trellis should catch architecture drift before code changes start.

Add a compact check to `trellis-before-dev`:

```text
Architecture Drift Check:
- Owner: which module/service/file owns the behavior?
- Source of truth: where does the authoritative state or contract live?
- Boundary: what API/schema/event/cache/module boundary must not drift?
- Fallback/old path: is this adding or retaining a fallback, duplicate owner,
  compatibility branch, or stale path?
- Decision: proceed | revise design/implement | ask user
```

This is a thinking checkpoint, not a new artifact.

### Implementation Drift

Trellis should catch implementation drift after code changes.

Add a compact check to `trellis-check`:

```text
Implementation Drift Check:
- Requirement alignment: matches `prd.md` acceptance?
- Design alignment: matches `design.md` boundaries if present?
- Plan alignment: matches `implement.md` steps if present?
- Spec alignment: matches applicable `.trellis/spec/` rules?
- Result: aligned | design/spec defect | implementation drift | needs user decision
- Fix: update design/spec first | fix implementation | ask user
```

The important distinction is:

- `design/spec defect`: the written authority is wrong, missing, or stale.
- `implementation drift`: the written authority is still correct, but the code
  deviated from it.

### Spec Capture

`trellis-update-spec` remains the durable memory gate. It should not record
execution traces, failed attempts, or one-off reasoning. It should record only
stable rules future work should obey, such as:

- API, command, database, event, cache, or environment contracts
- repeated bug classes
- non-obvious failure modes
- stable conventions discovered in existing code
- testing rules needed for future changes

## Aegis Removal Strategy

This is internal code/process retirement, so the default path is delete-first.

Remove active Aegis surfaces:

- `.agents/skills/trellis-aegis-execution/`
- Aegis bridge references from `.trellis/workflow.md`
- Aegis ownership and artifact rules from `zhiguang-trellis-flow`
- Aegis-specific setup docs in README and `docs/aegis/`
- `.codex/aegis/`
- `.codex/aegis-config.toml`
- `.codex/scripts/setup-aegis.ps1`
- Aegis-projected directories under `.codex/skills/`

Preserve non-Aegis `.codex/skills/` directories, currently the OpenSpec skill
set:

- `openspec-apply-change`
- `openspec-archive-change`
- `openspec-bulk-archive-change`
- `openspec-continue-change`
- `openspec-explore`
- `openspec-ff-change`
- `openspec-new-change`
- `openspec-onboard`
- `openspec-propose`
- `openspec-sync-specs`
- `openspec-verify-change`

Do not edit archived Trellis tasks just to erase historical Aegis mentions.
They are evidence of past workflow decisions, not active runtime instructions.

## Active Workflow After Change

Codex inline execution should become:

```text
trellis-before-dev
-> direct Trellis execution
-> trellis-check
-> trellis-update-spec
-> commit
-> trellis-finish-work
```

Sub-agent-capable Trellis execution can remain:

```text
trellis-implement
-> trellis-check
-> trellis-update-spec
-> commit
-> trellis-finish-work
```

The difference is only execution mechanism. Both paths use the same Trellis
authority files and the same drift checks.

## Compatibility

The change intentionally breaks project-local Aegis setup. That is the goal.

Expected preserved behavior:

- Trellis task creation, planning, activation, checking, spec update, commit,
  and finish flow still work.
- Codex project hooks still inject Trellis workflow state.
- Trellis Codex sub-agents under `.codex/agents/` still work.
- OpenSpec skills remain available if they are still intentionally present.

Expected retired behavior:

- Aegis auto-routing
- Aegis TDD route ownership
- Aegis slice plans and work records
- project-local Aegis doctor/setup/update flow
- `docs/aegis/` as an active workspace

## Risks

The main risk is over-deleting `.codex/skills/` and removing non-Aegis project
skills. The implementation must compare `.codex/skills/` against
`.codex/aegis/skills/` and delete only matching Aegis projections.

The second risk is leaving a dangling Aegis reference in an active workflow file.
Verification must grep active surfaces and distinguish active references from
archived task history.

The third risk is making drift checks too heavy. The checks should stay compact
and conversational; they should not create new persistent files.

The fourth risk is replacing one process owner with another tool-shaped owner.
Sentrux-like structure drift analysis and Repowise-like repository context are
useful only if Trellis remains the place where drift is judged and acted on.
