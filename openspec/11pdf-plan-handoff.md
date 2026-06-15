# 11.pdf Plan Integration Handoff

Last updated: 2026-06-15 15:36 +08:00

## Current User Intent

The user asked to stop the current discussion and leave a handoff document for another Codex instance.

The active topic was: how to use `11.pdf` to guide implementation plans under the current `openspec/changes`, after the changes had already been revised once to absorb `11.pdf`.

## Important User Preferences

- Use Chinese in user-facing discussion.
- Do not over-design.
- Do not treat developers as unable to reason.
- Do not nitpick in reviews.
- If there are semantic conflicts, ask the user before changing direction.
- Current `openspec/changes` should be treated as the authoritative version that already absorbed `11.pdf`; do not assume changes need another broad rewrite.

## Skills / Process Context

The user invoked:

- `$using-superpowers`
- `$grill-me`

Relevant grill-me rule: ask one question at a time, and if a question can be answered from the repo, inspect the repo instead of asking.

The current grill flow is intentionally paused at the user's request.

## Confirmed Decisions

Recorded in `openspec/grillme-decisions.md`:

- Q35: Use a cross-change `11.pdf` mapping/constraint document first, then lightly align implementation plans from that mapping.
- The mapping document should not replace `openspec/changes`.
- `openspec/changes` remains the source of truth.
- `11.pdf` is architecture inspiration/context.
- plans are execution scripts for subagents.

Correction from the user:

- The changes had already been revised according to `11.pdf`.
- Therefore the next step is not "rewrite changes from 11.pdf" by default.
- The better next step is to document how `11.pdf` is already reflected in current changes, then use that to guide plans.

## Current Repo State

Observed with `git status --short`:

```text
 M openspec/grillme-decisions.md
?? 11.pdf
?? docs/superpowers/plans/2026-06-11-add-cassandra-text-storage.md
?? docs/superpowers/plans/2026-06-11-add-comment-system.md
?? docs/superpowers/plans/2026-06-11-add-data-reconciliation.md
?? docs/superpowers/plans/2026-06-11-add-recommendation-and-follow-feed.md
?? docs/superpowers/plans/2026-06-11-eventize-publish-pipeline.md
?? docs/superpowers/plans/2026-06-11-split-to-microservices.md
```

Notes:

- `11.pdf` is untracked.
- Six non-leaf old `2026-06-11` plans were restored as untracked files.
- `2026-06-11-add-leaf-id-service.md` was intentionally not restored.
- `2026-06-09` plans were intentionally ignored.
- The current leaf ID plan is the single `docs/superpowers/plans/2026-06-15-add-leaf-id-service.md` file. The older split `2026-06-15-leaf-id-service-plan-*` files were removed to avoid duplicate execution entries.

## Files To Read First

Read these before continuing:

- `openspec/grillme-decisions.md`
- `openspec/changes/execution-order.md`
- `openspec/changes/align-publish-relation-architecture/design.md`
- `openspec/changes/add-leaf-id-service/design.md`
- `openspec/changes/add-cassandra-text-storage/design.md`
- `openspec/changes/add-comment-system/design.md`
- `openspec/changes/add-recommendation-and-follow-feed/design.md`
- `openspec/changes/add-data-reconciliation/design.md`
- `openspec/changes/split-to-microservices/design.md`
- `docs/superpowers/plans/2026-06-15-add-leaf-id-service.md`

## Current Understanding Of 11.pdf Mapping

Current changes already reflect these `11.pdf` ideas:

| 11.pdf idea | Current OpenSpec landing place |
|---|---|
| Manager orchestration, Helper/DAO/Publisher/Client boundaries | `align-publish-relation-architecture` |
| async publish acceptance, status query, retry | `align-publish-relation-architecture` |
| idempotency | publish in `align-publish-relation-architecture`, comments in `add-comment-system`, relation by natural key |
| chain-level executor isolation | `align-publish-relation-architecture`, with reconciliation executor implications |
| rate limit / circuit breaker / fallback | `align-publish-relation-architecture` using Sentinel behind local guard interfaces |
| Leaf-style unified ID generation | `add-leaf-id-service` |
| text fact source | `add-cassandra-text-storage` |
| async comment write and pending status | `add-comment-system` |
| derived tasks not blocking the critical publish path | `add-recommendation-and-follow-feed` and `add-data-reconciliation` |
| compensation, retry, dead task, stuck running recovery | `add-data-reconciliation` |
| service boundaries, Adapter, event communication, no cross-boundary JOIN | `split-to-microservices` as architecture-only constraints |

## Recommended Next Work

If the user asks to continue:

1. Create a matrix document, likely:
   - `openspec/11pdf-integration-matrix.md`
2. Make it a tracking/constraint document, not a new source of truth.
3. For each current change, document:
   - which `11.pdf` ideas are already reflected
   - which ideas are intentionally out of scope
   - which implementation plans should cite that mapping
4. Only modify `openspec/changes` if a clear gap is found and the gap does not change an existing decision.
5. If any gap changes semantics, ask the user first.

## Suggested Next Grill Question

Ask only one question:

```text
问题 3：这份 `11pdf-integration-matrix.md` 你希望它只作为“已吸收情况追踪”，还是也允许列出“当前 changes 的缺口/待修正项”？

我的推荐答案：允许列出缺口，但默认不直接改 changes；只有缺口明确、不改变既有决策时才轻量修正。涉及语义冲突时先问你。
```

## Caution

Do not restart the old discussion as if `11.pdf` has not yet been absorbed.

Do not broadly rewrite all changes from `11.pdf`.

Do not treat old 6.11 plans as authoritative. They are reference material only unless the user explicitly promotes one back into the execution path.
