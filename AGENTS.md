# Project Agent Rules

## Code Fact Gathering

Use CodeGraph first for code understanding in this repository. Before making claims
about code paths, symbols, ownership, call chains, or impact, run the relevant
CodeGraph command and treat its output as the primary code fact source:

- `codegraph status` to verify the index is usable.
- `codegraph explore <query>` for a feature/module investigation.
- `codegraph node <symbol-or-file>` for one symbol or file with related context.
- `codegraph callers <symbol>` and `codegraph callees <symbol>` for call chains.
- `codegraph impact <symbol>` for change impact.

Use `rg` only as a supplement for exact text, configuration, tests, docs, or when
CodeGraph cannot answer the question. Do not replace CodeGraph-backed fact
gathering with plain text search for source-code structure questions.

