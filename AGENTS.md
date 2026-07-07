# CodeGraph

Local code intelligence for this repository.

## Top tools

- `codegraph_get_callers`: who calls a function
- `codegraph_analyze_impact`: what breaks if a symbol changes
- `codegraph_get_edit_context`: local edit context for a file
- `codegraph_symbol_search`: symbol lookup
- `codegraph_get_module_summary`: module structure overview

## Decision rule

1. Structural question: use CodeGraph.
2. Text question: use `rg` and direct reads.
3. Unknown symbol: try `codegraph_symbol_search` first.

## Notes

- Reindex once with `codegraph_reindex_workspace` if CodeGraph returns empty unexpectedly.
- Use `file:///absolute/path` URIs from CodeGraph results directly.
- Do not use CodeGraph for file editing, Git operations, or test execution.
