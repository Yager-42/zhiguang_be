# Remove AI features design

## Scope boundary

This task removes product-facing AI capabilities and the internal execution
paths that exist only to support them.

Remove:

- `com.tongji.llm` package
- knowpost AI controllers and DTOs used only by those controllers
- RAG publish/index/query logic
- RAG-specific reconciliation task types, scan types, scheduler hook, and
  reconciler
- AI-specific Spring dependencies and configuration
- AI-only tests and current docs

Keep:

- Elasticsearch base client/config used by search
- Cassandra text storage
- MinIO storage
- search indexing and query paths unrelated to vectors/RAG
- generic reconciliation framework and non-AI task families

## Affected modules

- `knowpost` API layer
- `knowpost` service layer
- `recommendation` consumer layer
- `reconciliation` executor / scan / service / model
- Spring app configuration
- Maven dependency graph
- tests and docs

## Data and behavior changes

- AI endpoints disappear entirely.
- Published posts no longer trigger any RAG indexing attempt.
- Reconciliation rerun for post targets no longer creates `rag_index` tasks.
- Periodic reconciliation no longer scans `post_rag`.
- Existing text storage remains because search and publish flows still use it.

## Compatibility stance

This is a deliberate product contraction. Removing these endpoints and task
types is the intended user-visible change.

No compatibility shim will be kept for deleted AI behavior.

## Verification strategy

- Compile and test the backend after removal.
- Search the codebase for removed AI identifiers and config keys.
- Confirm remaining Elasticsearch/search code still compiles without Spring AI.
