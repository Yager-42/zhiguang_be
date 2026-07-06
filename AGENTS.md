# CodeGraph — local code intelligence

Persistent semantic graph of your codebase. Resolves references — "who calls X?" is a single tool call, not multi-file grep.

**First time?** Run `codegraph_reindex_workspace` once (5-30s). Index persists across sessions.

## Top 5 tools (use these first)

| When you need | Call this | NOT this |
|---|---|---|
| Who calls function X? | `codegraph_get_callers` | grep for the function name |
| What breaks if I change X? | `codegraph_analyze_impact` | reading every importing file |
| Context for editing a file | `codegraph_get_edit_context` | reading 5+ files manually |
| Find a symbol by name | `codegraph_symbol_search` | grep -r across the project |
| Module structure overview | `codegraph_get_module_summary` | ls + reading each file |

**URI format**: `file:///absolute/path`. Use paths from `symbol_search` results directly.
**Compact mode**: pass `compact: true` for shorter output when scanning.

## Common workflows

- **PR review**: `pr_context` — one call: blast radius, test gaps, stale docs, reviewers
- **Refactoring**: `symbol_search` → `analyze_impact` → `get_edit_context`
- **Bug triage**: `search_by_error` → `get_callers` → `get_ai_context(intent: "debug")`
- **Onboarding**: `get_module_summary` → `find_entry_points` → `get_call_graph`

## Decision rule

1. Structural question? (callers, deps, impact) → **codegraph tools**
2. Text question? (exact string, regex) → **grep/read**
3. Not sure? → Try `codegraph_symbol_search` first. Empty → run `codegraph_reindex_workspace`.

## More tools

**Navigation**: `get_callees`, `get_call_graph`, `get_dependency_graph`, `traverse_graph`, `find_by_imports`, `find_entry_points`
**Quality**: `analyze_complexity`, `find_hot_paths`, `find_circular_deps`, `find_dead_imports`
**PR review**: `pr_context` (blast radius, test gaps, stale docs, commit hint, reviewers)
**Docs**: `index_markdown`, `search_docs`, `verify_design`, `design_gaps`, `generate_architecture_doc`
**Memory**: `memory_store` (pass `agentSource: "codex"`), `memory_search`, `memory_context`

## When NOT to use CodeGraph

Read/write files → Read/Edit. Git → git commands. Tests → Bash. Known file path → Read directly.