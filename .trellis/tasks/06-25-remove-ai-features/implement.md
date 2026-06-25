# Remove AI features implementation plan

## Steps

1. Remove API-layer AI entrypoints.
   - Delete knowpost AI/RAG controllers and AI-only DTOs.
   - Remove security rules that mention deleted endpoints.

2. Remove AI service and execution code.
   - Delete `com.tongji.llm`.
   - Remove RAG calls from knowpost service flow.

3. Remove AI async and reconciliation paths.
   - Delete RAG Kafka consumer and reconciler.
   - Remove `rag_index` / `post_rag` from task types, scan types, scheduler,
     scan service, and rerun service logic.

4. Remove AI dependencies, config, tests, and current docs.
   - Remove Spring AI dependencies from `pom.xml`.
   - Remove AI config from `application.yml`.
   - Delete or update AI-only tests and user-facing docs.

5. Verify and repair.
   - Run targeted search to confirm AI identifiers are gone.
   - Run Maven tests for touched areas or full test suite if practical.

## Validation commands

- `rg -n -i "spring\\.ai|openai|siliconflow|rag_index|post_rag|qa/stream|description/suggest|KnowPostRagController|KnowPostAiController|com\\.tongji\\.llm" src/main src/test docs pom.xml src/main/resources`
- `mvn test`

## Execution State

- Active step: none
- Completed steps:
  - 1. Remove API-layer AI entrypoints
  - 2. Remove AI service and execution code
  - 3. Remove AI async and reconciliation paths
  - 4. Remove AI dependencies, config, tests, and current docs
  - 5. Verify and repair
- 6. Update reusable quality spec for capability retirement across layers
- Next candidate: Phase 3.4 commit -> trellis-finish-work
- Notes:
  - Keep Elasticsearch search support.
  - Delete AI behavior completely instead of leaving dead config or dead task types.
  - Full `mvn test` under JDK 21 starts successfully but is blocked by unrelated local infrastructure-dependent tests and long runtime.
  - Targeted verification for all touched reconciliation paths passed.
  - Current code-spec updated with a retirement rule: when deleting a backend capability, remove entrypoints, async/reconciliation wiring, config/dependencies, tests, and current docs in the same task.
