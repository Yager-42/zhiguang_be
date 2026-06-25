# Remove AI features

## Goal

Remove all AI-facing product capabilities from this backend project.

## Requirements

- Remove knowpost AI description generation API and implementation.
- Remove knowpost RAG Q&A API and manual RAG reindex API.
- Remove AI execution code under `com.tongji.llm`.
- Remove publish-time and reconciliation-time RAG indexing paths.
- Remove Spring AI dependencies and AI-specific application configuration.
- Remove tests that only validate AI functionality.
- Update current user-facing docs that advertise these AI capabilities.
- Keep non-AI foundational capabilities intact:
  - MinIO/storage upload
  - Cassandra text storage
  - Elasticsearch search
  - reconciliation for non-AI task types
  - knowpost publish/detail/feed flows unrelated to AI

## Acceptance Criteria

- [ ] Project source contains no runtime usage of Spring AI, OpenAI-compatible chat client, vector store, or `com.tongji.llm` package.
- [ ] `/api/v1/knowposts/description/suggest`, `/api/v1/knowposts/{id}/qa/stream`, and `/api/v1/knowposts/{id}/rag/reindex` are removed from backend code and current API docs.
- [ ] RAG-specific reconciliation task/scan types, scheduler entry, reconciler, and Kafka consumer are removed.
- [ ] `pom.xml` and `application.yml` no longer declare AI-specific dependencies or AI-specific config.
- [ ] Remaining non-AI tests compile and pass for the touched areas.

## Notes

- Keep `prd.md` focused on requirements, constraints, and acceptance criteria.
- Lightweight tasks can remain PRD-only.
- For complex tasks, add `design.md` for technical design and `implement.md` for execution planning before `task.py start`.
