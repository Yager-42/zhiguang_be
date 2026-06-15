# Cassandra Text Storage Implementation Plan

> **For agentic workers:** REQUIRED: Use `superpowers:subagent-driven-development` if subagents are available, or `superpowers:executing-plans` in the current session. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Introduce Cassandra as the fact store for post and comment text while keeping MinIO for media and legacy text fallback.

**Architecture:** `TextStorageService` hides Cassandra repositories. Publish critical flow writes post text to Cassandra before `publishing -> published`; ES/RAG read text from Cassandra first and fall back to MinIO for old posts.

**Tech Stack:** Java 21, Spring Boot 3.2.4, Spring Data Cassandra, Cassandra 4.1, MinIO, Elasticsearch, Spring AI VectorStore, JUnit 5, Maven.

---

## Required Context

Read before editing:

- `openspec/11pdf-integration-matrix.md`
- `openspec/changes/add-cassandra-text-storage/proposal.md`
- `openspec/changes/add-cassandra-text-storage/design.md`
- `openspec/changes/add-cassandra-text-storage/specs/text-storage/spec.md`
- `openspec/changes/add-cassandra-text-storage/tasks.md`
- `src/main/java/com/tongji/search/index/SearchIndexService.java`
- `src/main/java/com/tongji/llm/rag/RagIndexService.java`
- `src/main/java/com/tongji/knowpost/manager/PublishManagerImpl.java`

Prerequisites: `add-leaf-id-service` and `align-publish-relation-architecture` complete.

## Command Setup

Run before Maven commands:

```powershell
$mvn = Join-Path $env:USERPROFILE 'Desktop\文档\.codex\tools\apache-maven-3.9.6\bin\mvn.cmd'
Test-Path $mvn
```

Expected: `True`.

## Current Design Decision: Business ID Text Keys

This plan follows the current `openspec/changes/add-cassandra-text-storage/design.md` decision: Cassandra lookup keys are existing business IDs. Use `post_id` for post text and `comment_id` for comment/reply text.

Do not add a MySQL `content_key` column for post or comment text. Keep `TextStorageService` keyed by `postId` and `commentId`: `savePostText(long postId, ...)`, `getPostText(long postId, ...)`, `saveCommentText(long commentId, ...)`, and `getCommentTexts(Collection<Long> commentIds)`.

OpenSpec is aligned on this point: do not implement `content_key`. Business IDs are the Cassandra lookup keys.

## Files

- Modify: `pom.xml`
- Modify: `docker-compose.yml`
- Create: `db/cassandra/init.cql`
- Modify: `src/main/resources/application.yml`
- Create: `src/main/java/com/tongji/storage/text/*`
- Modify: `src/main/java/com/tongji/knowpost/manager/PublishManagerImpl.java`
- Modify: `src/main/java/com/tongji/search/index/SearchIndexService.java`
- Modify: `src/main/java/com/tongji/llm/rag/RagIndexService.java`
- Test: `src/test/java/com/tongji/storage/text/*`

## Task 1: Environment and Schema

- [ ] Add `spring-boot-starter-data-cassandra` dependency.
- [ ] Add Cassandra service and `cassandra-init` service to `docker-compose.yml`.
- [ ] Create `db/cassandra/init.cql` with keyspace `zhiguang`, `post_text_by_post_id` keyed by `post_id`, and `comment_text_by_comment_id` keyed by `comment_id`.
- [ ] Add `spring.cassandra` config under the existing `spring:` block in `application.yml`; do not create a duplicate `spring:` root key.
- [ ] Set `schema-action: none` for normal runtime.
- [ ] Verify with `docker compose up cassandra -d`, `docker compose up cassandra-init`, and `docker exec zhiguang-cassandra cqlsh -e "DESCRIBE TABLES IN zhiguang;"`.

## Task 2: Text Storage API

**Files:**
- Create: `src/main/java/com/tongji/storage/text/TextStorageService.java`
- Create: `src/main/java/com/tongji/storage/text/TextStorageException.java`
- Create: `src/main/java/com/tongji/storage/text/TextWriteException.java`
- Create: `src/main/java/com/tongji/storage/text/TextReadException.java`

- [ ] Define `savePostText(long postId, String body, String sha256)`.
- [ ] Define `Optional<String> getPostText(long postId, String fallbackContentUrl)`.
- [ ] Define `saveCommentText(long commentId, String body)`.
- [ ] Define `Map<Long, String> getCommentTexts(Collection<Long> commentIds)`.
- [ ] Define hard-delete methods for post and comment text.
- [ ] Treat missing text as `Optional.empty()` or absent map entry, not an exception.
- [ ] Treat write failure as `TextWriteException`.
- [ ] Run `& $mvn -DskipTests compile`.

## Task 3: Cassandra Entities and Service

**Files:**
- Create: `src/main/java/com/tongji/storage/text/PostText.java`
- Create: `src/main/java/com/tongji/storage/text/CommentText.java`
- Create: `src/main/java/com/tongji/storage/text/PostTextRepository.java`
- Create: `src/main/java/com/tongji/storage/text/CommentTextRepository.java`
- Create: `src/main/java/com/tongji/storage/text/CassandraTextStorageService.java`
- Test: `src/test/java/com/tongji/storage/text/CassandraTextStorageServiceTest.java`

- [ ] Write unit tests first using mocked repositories and `RestTemplate`.
- [ ] Implement version increment on overwrite.
- [ ] Implement Cassandra-first read with MinIO fallback URL for legacy posts.
- [ ] Implement batch comment reads returning only existing rows.
- [ ] Implement hard deletes.
- [ ] Run `& $mvn -Dtest=CassandraTextStorageServiceTest test`.

## Task 4: Publish Critical Flow Integration

**Files:**
- Modify: `src/main/java/com/tongji/knowpost/manager/PublishManagerImpl.java`
- Test: `src/test/java/com/tongji/knowpost/manager/PublishManagerTextStorageTest.java`

- [ ] In the accepted publish critical flow, fetch text from existing `contentUrl` or object key.
- [ ] Call `textStorageService.savePostText(postId, body, contentSha256)` before completing `publishing -> published`.
- [ ] If Cassandra write fails, mark attempt failed and post `publish_failed`; keep the original HTTP `202` as acceptance only.
- [ ] Do not write Cassandra during draft creation or content confirm.
- [ ] Add tests proving text write failure fails the attempt and prevents `published`.
- [ ] Run `& $mvn -Dtest=*PublishManagerTextStorage* test`.

## Task 5: ES/RAG Cassandra-First Reads

**Files:**
- Modify: `src/main/java/com/tongji/search/index/SearchIndexService.java`
- Modify: `src/main/java/com/tongji/llm/rag/RagIndexService.java`
- Test: `src/test/java/com/tongji/storage/text/TextStorageSearchRagTest.java`

- [ ] Inject `TextStorageService`.
- [ ] Replace direct `contentUrl` fetch with `getPostText(postId, contentUrl)`.
- [ ] Keep description fallback if text is still missing.
- [ ] Add tests that Cassandra body is preferred and MinIO fallback remains available.
- [ ] Run `& $mvn -Dtest=*TextStorageSearchRag* test`.

## Task 6: Smoke Test and Documentation

**Files:**
- Create: `src/test/java/com/tongji/storage/text/TextStorageSmokeTest.java`
- Create or modify: `docs/cassandra.md`

- [ ] Add a Cassandra slice or Testcontainers smoke test for post save/read/delete and comment batch read.
- [ ] Document local Cassandra startup, schema verification, reset, and cqlsh commands.
- [ ] Run `& $mvn -Dtest=TextStorageSmokeTest test` if Docker is available.
- [ ] Run `& $mvn test`.
- [ ] Run `openspec status --change "add-cassandra-text-storage" --json`.
- [ ] Run `openspec validate add-cassandra-text-storage --strict` if supported.
- [ ] Before updating OpenSpec task checkboxes, verify the implementation did not add `content_key`; business IDs must remain the Cassandra lookup keys.
- [ ] Mark completed checkboxes in `openspec/changes/add-cassandra-text-storage/tasks.md` only after evidence exists.
