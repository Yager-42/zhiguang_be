# Comment System Implementation Plan

> **For agentic workers:** REQUIRED: Use `superpowers:subagent-driven-development` if subagents are available, or `superpowers:executing-plans` in the current session. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add two-level comments with asynchronous write semantics, Cassandra-backed text, MySQL metadata, pending status, soft delete, comment likes, and comment/reply counts.

**Architecture:** The API pre-generates the final `comment_id` with `IdService`, writes a pending status row, publishes a `comment-write` Kafka message, and returns `202 Accepted`. A consumer writes Cassandra text and MySQL metadata; MySQL unique `client_request_id` handles idempotency.

**Tech Stack:** Java 21, Spring Boot 3.2.4, MyBatis, MySQL, Kafka, Cassandra `TextStorageService`, `IdService`, existing counter services, JUnit 5, Maven.

---

## Required Context

Read before editing:

- `openspec/11pdf-integration-matrix.md`
- `openspec/changes/add-comment-system/proposal.md`
- `openspec/changes/add-comment-system/design.md`
- `openspec/changes/add-comment-system/specs/comment-system/spec.md`
- `openspec/changes/add-comment-system/tasks.md`
- `src/main/java/com/tongji/counter/service/CounterService.java`
- `src/main/java/com/tongji/counter/event/CounterEvent.java`
- `src/main/java/com/tongji/counter/event/CounterEventProducer.java`

Prerequisites: `add-leaf-id-service` and `add-cassandra-text-storage` complete.
ID imports must use `com.tongji.common.id.IdService` and `com.tongji.common.id.IdNamespace`.

## Command Setup

Run before Maven commands:

```powershell
$mvn = Join-Path $env:USERPROFILE 'Desktop\文档\.codex\tools\apache-maven-3.9.6\bin\mvn.cmd'
Test-Path $mvn
```

Expected: `True`.

## Current Design Decision: Async Submit and Comment ID Text Key

This plan follows the current `openspec/changes/add-comment-system/design.md` and `add-cassandra-text-storage/design.md` decisions: `pendingCommentId` is the final `comment_id`, and Cassandra comment text is looked up by that same `comment_id`.

Do not add a MySQL `comments.content_key` column. The `comments` table stores metadata and `client_request_id`; `TextStorageService.saveCommentText(commentId, body)` and `getCommentTexts(commentIds)` use `comment_id` directly as the Cassandra lookup key.

OpenSpec is aligned on this point: do not implement `content_key`. Business IDs are the Cassandra lookup keys.

Keep comment submit fully asynchronous: the controller/service returns `202 Accepted` after pending row creation and Kafka acceptance only. It must not synchronously persist final comment metadata to `comments` or final text to Cassandra on the request thread; those writes belong to the comment consumer.

## Files

- Modify: `db/schema.sql`
- Modify: `src/main/resources/application.yml`
- Create: `src/main/java/com/tongji/comment/model/*`
- Create: `src/main/java/com/tongji/comment/mapper/*`
- Create: `src/main/resources/mapper/CommentMapper.xml`
- Create: `src/main/resources/mapper/PendingCommentMapper.xml`
- Create: `src/main/java/com/tongji/comment/api/*`
- Create: `src/main/java/com/tongji/comment/api/dto/*`
- Create: `src/main/java/com/tongji/comment/service/*`
- Create: `src/main/java/com/tongji/comment/event/*`
- Create: `src/main/java/com/tongji/comment/consumer/*`
- Test: `src/test/java/com/tongji/comment/*`

## Task 1: Schema and Configuration

- [ ] Add `comments` table with `comment_id`, `post_id`, `root_id`, `parent_id`, `creator_id`, `client_request_id`, `status`, `like_count`, `reply_count`, timestamps, primary key, unique client request key, and pagination indexes.
- [ ] Do not add `content_key` to `comments`; `comment_id` is the Cassandra lookup key under the current design decision.
- [ ] Add `pending_comments` table with `pending_comment_id`, `creator_id`, `post_id`, `status`, timestamps.
- [ ] Add `comment.kafka.write-topic` and `comment.kafka.feedback-topic` to `application.yml`.
- [ ] Verify SQL by searching `rg -n "comments|pending_comments|uk_client_request_id" db/schema.sql`.

## Task 2: Models, Mappers, DTOs, Events

- [ ] Create `Comment` and `PendingComment` models.
- [ ] Create `CommentMapper` and XML for insert, top-level cursor page, reply cursor page, soft delete, find by id, and optional `listCommentIdsCursor` for reconciliation.
- [ ] Create `PendingCommentMapper` and XML for insert, update status, find by id.
- [ ] Create submit/status/page/item DTOs.
- [ ] Ensure submit response DTO contains both `clientRequestId` and `pendingCommentId`.
- [ ] Create `CommentWriteEvent` with final `commentId`, post/root/parent IDs, creator, `clientRequestId`, and body.
- [ ] Create `CommentFeedbackEvent` for `comment`, `delete`, `like`, `unlike`.
- [ ] Run `& $mvn -DskipTests compile`.

## Task 3: Comment Service API Path

**Files:**
- Create: `src/main/java/com/tongji/comment/service/CommentService.java`
- Create: `src/main/java/com/tongji/comment/service/impl/CommentServiceImpl.java`
- Test: `src/test/java/com/tongji/comment/service/CommentServiceImplTest.java`

- [ ] Write tests for submit returning both `clientRequestId` and final `commentId` as `pendingCommentId`.
- [ ] Generate IDs with `IdService.nextId(IdNamespace.COMMENT)` from `com.tongji.common.id`.
- [ ] Validate top-level vs reply: replies can only target top-level comments; deeper nesting is rejected.
- [ ] Insert `pending_comments` with `pending`.
- [ ] Publish `comment-write` Kafka event.
- [ ] Return an accepted response containing `clientRequestId` and `pendingCommentId` only after pending insert and Kafka publish are accepted.
- [ ] Do not insert final `comments` metadata or call `textStorageService.saveCommentText` in the submit path.
- [ ] Query status from pending table.
- [ ] Page comments from MySQL and batch-read text from Cassandra.
- [ ] For deleted comments, return placeholder text and preserve replies.
- [ ] Soft delete by owner and hard-delete Cassandra text.
- [ ] Run `& $mvn -Dtest=CommentServiceImplTest test`.

## Task 4: Kafka Consumer and DLT

**Files:**
- Create: `src/main/java/com/tongji/comment/config/CommentKafkaConfig.java`
- Create: `src/main/java/com/tongji/comment/consumer/CommentWriteConsumer.java`
- Test: `src/test/java/com/tongji/comment/consumer/CommentWriteConsumerTest.java`

- [ ] Use a dedicated Kafka listener container factory if retry topics are enabled. The existing manual ack configuration can loop forever with `@RetryableTopic`; the comment write listener should use record-level ack or an equivalent configuration that commits before retry/DLT routing.
- [ ] Consumer writes Cassandra text first, then MySQL metadata.
- [ ] Handle duplicate `client_request_id` as idempotent success and ack.
- [ ] On success, update pending status to `succeeded`.
- [ ] On exhausted retry or DLT, update pending status to `failed`.
- [ ] Publish counter events: top-level comment increments post comment count, reply increments root comment reply count.
- [ ] Add consumer tests for duplicate `client_request_id`, retryable exception propagation, and DLT updating pending status to `failed`.
- [ ] Run `& $mvn -Dtest=CommentWriteConsumerTest test`.

## Task 5: Controller and Interactions

**Files:**
- Create: `src/main/java/com/tongji/comment/api/CommentController.java`
- Create: `src/main/java/com/tongji/comment/event/CommentFeedbackProducer.java`
- Modify: `src/main/java/com/tongji/comment/service/impl/CommentServiceImpl.java`

- [ ] Add `POST /api/v1/posts/{postId}/comments` returning `202`.
- [ ] Return a body containing both `clientRequestId` and `pendingCommentId`; controller tests must assert both fields.
- [ ] Add `GET /api/v1/comments/{pendingCommentId}/status`.
- [ ] Add `GET /api/v1/posts/{postId}/comments`.
- [ ] Add `GET /api/v1/comments/{commentId}/replies`.
- [ ] Add `DELETE /api/v1/comments/{commentId}`.
- [ ] Add `POST /api/v1/comments/{commentId}/like` and `DELETE /api/v1/comments/{commentId}/like`.
- [ ] Use `CounterService.like("comment", String.valueOf(commentId), userId)` and `unlike`.
- [ ] Publish comment feedback events as fire-and-forget.
- [ ] Run `& $mvn -Dtest=*CommentController* test`.

## Task 6: Verification and OpenSpec Closure

- [ ] Add verification tests for client request idempotency, async consumer persistence, pagination, soft delete structure, comment likes, and count events.
- [ ] Run `& $mvn -Dtest="*Comment*" test`.
- [ ] Run `& $mvn test`.
- [ ] Run `openspec status --change "add-comment-system" --json`.
- [ ] Run `openspec validate add-comment-system --strict` if supported.
- [ ] Before updating OpenSpec task checkboxes, verify the implementation did not add `content_key`; business IDs must remain the Cassandra lookup keys.
- [ ] Mark completed checkboxes in `openspec/changes/add-comment-system/tasks.md` only after evidence exists.
