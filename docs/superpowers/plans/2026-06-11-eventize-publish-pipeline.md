# eventize-publish-pipeline Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the synchronous 204 publish endpoint with an attempt-based async pipeline: `draft → publishing → published`, returning 202 with `attemptId`; derived tasks (ES, RAG, Feed, Counter) are triggered by a `content-published` Kafka event instead of the current Canal Outbox approach.

**Architecture:** `KnowPostServiceImpl.publish()` is refactored into a synchronous critical path (CAS state transition + validation + Cassandra text write + DB atomic publish) that returns 202. After the DB commits, a `content_published` Kafka event triggers independent consumers for ES indexing, RAG indexing, feed cache invalidation, and counter initialization. A `@Scheduled` task marks stuck `publishing` posts as `publish_failed` every 5 minutes.

**Tech Stack:** Spring Boot 3.2.4, MyBatis, Kafka (existing), Spring `@Scheduled`, `TextStorageService` (from `add-cassandra-text-storage`), `IdService` (from `add-leaf-id-service`).

**Prerequisites:** `add-cassandra-text-storage` and `add-leaf-id-service` changes must be implemented first. `TextStorageService` bean and `IdService` bean with `IdNamespace.PUBLISH_ATTEMPT` must be available.

**Key design decisions (from design.md):**
- Critical steps (validate + Cassandra + DB) run synchronously before 202 return
- Derived tasks use `content-published` Kafka topic (NOT Canal Outbox)
- `draft → publishing` via CAS SQL (`WHERE status='draft'`)
- Stuck `publishing` recovery: scheduled task every 5 min (NOT startup scan)
- Publish failure retry creates a **new** `publish_attempt`; old attempt preserved as history
- Derived task failures: log + write to `reconciliation_task` when available (see `add-data-reconciliation`)

---

## File Map

**New files:**
- `db/schema.sql` additions — `publish_attempt` table + `know_posts` schema extensions
- `src/main/java/com/tongji/knowpost/publish/PublishAttemptStatus.java` — enum: RUNNING, SUCCEEDED, FAILED
- `src/main/java/com/tongji/knowpost/publish/PublishAttempt.java` — model
- `src/main/java/com/tongji/knowpost/publish/PublishAttemptMapper.java` — MyBatis mapper
- `src/main/resources/mapper/PublishAttemptMapper.xml` — SQL
- `src/main/java/com/tongji/knowpost/publish/PublishValidationException.java` — critical validation failure
- `src/main/java/com/tongji/knowpost/publish/ContentPublishedEvent.java` — Kafka event payload
- `src/main/java/com/tongji/knowpost/publish/ContentPublishedProducer.java` — Kafka producer
- `src/main/java/com/tongji/knowpost/publish/PublishDerivedConsumer.java` — ES/RAG/Feed/Counter consumer
- `src/main/java/com/tongji/knowpost/publish/StuckPublishRecoveryTask.java` — scheduled recovery
- `src/test/java/com/tongji/knowpost/publish/PublishPipelineTest.java` — unit tests
- `src/test/java/com/tongji/knowpost/publish/PublishStateTest.java` — state machine tests

**Modified files:**
- `db/schema.sql` — add `publish_attempt` + alter `know_posts`
- `src/main/resources/application.yml` — add `content-published` topic config
- `src/main/java/com/tongji/knowpost/mapper/KnowPostMapper.java` — add pipeline SQL methods
- `src/main/resources/mapper/KnowPostMapper.xml` — add pipeline SQL
- `src/main/java/com/tongji/knowpost/api/KnowPostController.java` — 202 response + 2 new endpoints
- `src/main/java/com/tongji/knowpost/service/impl/KnowPostServiceImpl.java` — refactor publish()
- `src/main/java/com/tongji/storage/MinioStorageService.java` — add `doesObjectExist(key)`

---

## Task 1: DB Schema + application.yml

**Files:**
- Modify: `db/schema.sql`
- Modify: `src/main/resources/application.yml`

- [ ] **Step 1: Append publish_attempt table to db/schema.sql**

```sql
CREATE TABLE IF NOT EXISTS publish_attempt (
    attempt_id    BIGINT        NOT NULL,
    post_id       BIGINT        NOT NULL,
    creator_id    BIGINT        NOT NULL,
    status        VARCHAR(16)   NOT NULL DEFAULT 'running',  -- running/succeeded/failed
    failed_step   VARCHAR(64),
    error_message VARCHAR(512),
    retry_count   INT           NOT NULL DEFAULT 0,
    create_time   DATETIME(3)   NOT NULL,
    update_time   DATETIME(3)   NOT NULL,
    PRIMARY KEY (attempt_id),
    KEY idx_post_id (post_id),
    KEY idx_creator (creator_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

- [ ] **Step 2: Add columns to know_posts and extend status ENUM in db/schema.sql**

```sql
ALTER TABLE know_posts
    MODIFY COLUMN status ENUM(
        'draft', 'publishing', 'published', 'publish_failed', 'rejected', 'deleted'
    ) NOT NULL DEFAULT 'draft',
    ADD COLUMN publish_attempt_id    BIGINT        AFTER publish_time,
    ADD COLUMN publish_failed_reason VARCHAR(512)  AFTER publish_attempt_id;
```

> **Note:** MySQL ALTER TABLE on an existing DB may lock briefly. Safe for dev/local environments.

- [ ] **Step 3: Add content-published topic config to application.yml**

Add under the existing top-level `kafka:` section:

```yaml
knowpost:
  kafka:
    content-published-topic: content-published
```

- [ ] **Step 4: Apply schema to local DB**

```bash
cd /Users/huangyaokai/zhiguang_be
docker compose exec mysql mysql -uroot -proot zhiguang < db/schema.sql
docker compose exec mysql mysql -uroot -proot zhiguang \
  -e "DESCRIBE publish_attempt; SHOW COLUMNS FROM know_posts LIKE 'status';"
```

Expected: `publish_attempt` table exists; `know_posts.status` ENUM includes `publishing`, `publish_failed`.

- [ ] **Step 5: Commit**

```bash
git add db/schema.sql src/main/resources/application.yml
git commit -m "feat: add publish_attempt table and extend know_posts status enum"
```

---

## Task 2: PublishAttempt Model + Mapper

**Files:**
- Create: `src/main/java/com/tongji/knowpost/publish/PublishAttemptStatus.java`
- Create: `src/main/java/com/tongji/knowpost/publish/PublishAttempt.java`
- Create: `src/main/java/com/tongji/knowpost/publish/PublishAttemptMapper.java`
- Create: `src/main/resources/mapper/PublishAttemptMapper.xml`

- [ ] **Step 1: Create PublishAttemptStatus.java**

```java
package com.tongji.knowpost.publish;

public enum PublishAttemptStatus {
    RUNNING, SUCCEEDED, FAILED
}
```

- [ ] **Step 2: Create PublishAttempt.java**

```java
package com.tongji.knowpost.publish;

import java.time.LocalDateTime;

public class PublishAttempt {
    private long attemptId;
    private long postId;
    private long creatorId;
    private String status;       // running/succeeded/failed
    private String failedStep;
    private String errorMessage;
    private int retryCount;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public long getAttemptId() { return attemptId; }
    public void setAttemptId(long attemptId) { this.attemptId = attemptId; }
    public long getPostId() { return postId; }
    public void setPostId(long postId) { this.postId = postId; }
    public long getCreatorId() { return creatorId; }
    public void setCreatorId(long creatorId) { this.creatorId = creatorId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getFailedStep() { return failedStep; }
    public void setFailedStep(String failedStep) { this.failedStep = failedStep; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public int getRetryCount() { return retryCount; }
    public void setRetryCount(int retryCount) { this.retryCount = retryCount; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
}
```

- [ ] **Step 3: Create PublishAttemptMapper.java**

```java
package com.tongji.knowpost.publish;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface PublishAttemptMapper {

    void insert(PublishAttempt attempt);

    void succeed(@Param("attemptId") long attemptId);

    void fail(@Param("attemptId") long attemptId,
              @Param("failedStep") String failedStep,
              @Param("errorMessage") String errorMessage);

    PublishAttempt findById(@Param("attemptId") long attemptId);

    PublishAttempt findLatestByPostId(@Param("postId") long postId);
}
```

- [ ] **Step 4: Create PublishAttemptMapper.xml**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="com.tongji.knowpost.publish.PublishAttemptMapper">

    <insert id="insert">
        INSERT INTO publish_attempt
            (attempt_id, post_id, creator_id, status, retry_count, create_time, update_time)
        VALUES
            (#{attemptId}, #{postId}, #{creatorId}, 'running', #{retryCount},
             NOW(3), NOW(3))
    </insert>

    <update id="succeed">
        UPDATE publish_attempt
        SET status = 'succeeded', update_time = NOW(3)
        WHERE attempt_id = #{attemptId}
    </update>

    <update id="fail">
        UPDATE publish_attempt
        SET status       = 'failed',
            failed_step  = #{failedStep},
            error_message = #{errorMessage},
            update_time  = NOW(3)
        WHERE attempt_id = #{attemptId}
    </update>

    <select id="findById" resultType="com.tongji.knowpost.publish.PublishAttempt">
        SELECT attempt_id, post_id, creator_id, status, failed_step, error_message,
               retry_count, create_time, update_time
        FROM publish_attempt
        WHERE attempt_id = #{attemptId}
    </select>

    <select id="findLatestByPostId" resultType="com.tongji.knowpost.publish.PublishAttempt">
        SELECT attempt_id, post_id, creator_id, status, failed_step, error_message,
               retry_count, create_time, update_time
        FROM publish_attempt
        WHERE post_id = #{postId}
        ORDER BY create_time DESC
        LIMIT 1
    </select>

</mapper>
```

- [ ] **Step 5: Verify compilation**

```bash
mvn compile -q
```

Expected: `BUILD SUCCESS`

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/tongji/knowpost/publish/ \
        src/main/resources/mapper/PublishAttemptMapper.xml
git commit -m "feat: add PublishAttempt model and mapper"
```

---

## Task 3: KnowPostMapper — Pipeline SQL Methods

**Files:**
- Modify: `src/main/java/com/tongji/knowpost/mapper/KnowPostMapper.java`
- Modify: `src/main/resources/mapper/KnowPostMapper.xml`

- [ ] **Step 1: Add pipeline methods to KnowPostMapper.java**

Add to the existing interface:

```java
/** CAS: draft → publishing. Returns rows updated (0 if post not found / not draft / wrong owner). */
int startPublishing(@Param("id") long id,
                    @Param("creatorId") long creatorId,
                    @Param("attemptId") long attemptId);

/** CAS: publishing → published. Returns rows updated (0 if raced). */
int completePublish(@Param("id") long id, @Param("attemptId") long attemptId);

/** Set post to publish_failed from publishing state. */
int failPublish(@Param("id") long id,
                @Param("attemptId") long attemptId,
                @Param("reason") String reason);

/** Bulk-fail posts stuck in publishing longer than timeoutMinutes. */
int markStuckPublishingAsFailed(@Param("timeoutMinutes") int timeoutMinutes);

/** Fetch publish status info for status query API. */
PublishStatusRow findPublishStatus(@Param("id") long id);
```

- [ ] **Step 2: Create PublishStatusRow.java (inner result type)**

```java
package com.tongji.knowpost.mapper;

public class PublishStatusRow {
    private long id;
    private String status;
    private Long publishAttemptId;
    private String publishFailedReason;

    public long getId() { return id; }
    public void setId(long id) { this.id = id; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getPublishAttemptId() { return publishAttemptId; }
    public void setPublishAttemptId(Long publishAttemptId) { this.publishAttemptId = publishAttemptId; }
    public String getPublishFailedReason() { return publishFailedReason; }
    public void setPublishFailedReason(String r) { this.publishFailedReason = r; }
}
```

- [ ] **Step 3: Add SQL to KnowPostMapper.xml**

Add before the closing `</mapper>` tag:

```xml
<update id="startPublishing">
    UPDATE know_posts
    SET status             = 'publishing',
        publish_attempt_id = #{attemptId},
        update_time        = NOW(3)
    WHERE id = #{id}
      AND creator_id = #{creatorId}
      AND status = 'draft'
</update>

<update id="completePublish">
    UPDATE know_posts
    SET status       = 'published',
        publish_time = NOW(3),
        update_time  = NOW(3)
    WHERE id                = #{id}
      AND publish_attempt_id = #{attemptId}
      AND status            = 'publishing'
</update>

<update id="failPublish">
    UPDATE know_posts
    SET status                = 'publish_failed',
        publish_failed_reason = #{reason},
        update_time           = NOW(3)
    WHERE id                = #{id}
      AND publish_attempt_id = #{attemptId}
      AND status            = 'publishing'
</update>

<update id="markStuckPublishingAsFailed">
    UPDATE know_posts
    SET status      = 'publish_failed',
        publish_failed_reason = 'Timed out in publishing state',
        update_time = NOW(3)
    WHERE status      = 'publishing'
      AND update_time &lt; DATE_SUB(NOW(), INTERVAL #{timeoutMinutes} MINUTE)
</update>

<select id="findPublishStatus" resultType="com.tongji.knowpost.mapper.PublishStatusRow">
    SELECT id, status, publish_attempt_id, publish_failed_reason
    FROM know_posts
    WHERE id = #{id}
</select>
```

- [ ] **Step 4: Verify compilation**

```bash
mvn compile -q
```

Expected: `BUILD SUCCESS`

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/tongji/knowpost/mapper/ \
        src/main/resources/mapper/KnowPostMapper.xml
git commit -m "feat: add publish pipeline SQL methods to KnowPostMapper"
```

---

## Task 4: PublishValidationException + MinioStorageService.doesObjectExist

**Files:**
- Create: `src/main/java/com/tongji/knowpost/publish/PublishValidationException.java`
- Modify: `src/main/java/com/tongji/storage/MinioStorageService.java`

- [ ] **Step 1: Create PublishValidationException.java**

```java
package com.tongji.knowpost.publish;

public class PublishValidationException extends RuntimeException {

    private final String failedStep;

    public PublishValidationException(String failedStep, String message) {
        super(message);
        this.failedStep = failedStep;
    }

    public String getFailedStep() {
        return failedStep;
    }
}
```

- [ ] **Step 2: Add doesObjectExist to MinioStorageService.java**

In `MinioStorageService.java`, add after existing methods:

```java
public boolean doesObjectExist(String objectKey) {
    try {
        minioClient.statObject(
            io.minio.StatObjectArgs.builder()
                .bucket(bucketName)
                .object(objectKey)
                .build());
        return true;
    } catch (io.minio.errors.ErrorResponseException e) {
        if ("NoSuchKey".equals(e.errorResponse().code())) return false;
        throw new RuntimeException("MinIO stat failed for key: " + objectKey, e);
    } catch (Exception e) {
        throw new RuntimeException("MinIO stat failed for key: " + objectKey, e);
    }
}
```

The `minioClient` and `bucketName` fields are already present in `MinioStorageService`.

- [ ] **Step 3: Verify compilation**

```bash
mvn compile -q
```

Expected: `BUILD SUCCESS`

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/tongji/knowpost/publish/PublishValidationException.java \
        src/main/java/com/tongji/storage/MinioStorageService.java
git commit -m "feat: add PublishValidationException and MinioStorageService.doesObjectExist"
```

---

## Task 5: Publish Pipeline — Core Sync Path (TDD)

**Files:**
- Create: `src/test/java/com/tongji/knowpost/publish/PublishPipelineTest.java`
- Modify: `src/main/java/com/tongji/knowpost/service/impl/KnowPostServiceImpl.java`

The new `publish()` replaces the old synchronous 204 flow with:
1. Create `publish_attempt` record
2. CAS: `draft → publishing` (returns 0 if not draft/wrong owner → throw BusinessException)
3. Validate post fields (title, content URL, media objects)
4. Fetch text from MinIO → write to Cassandra via `TextStorageService`
5. CAS: `publishing → published`
6. Mark attempt as SUCCEEDED
7. Return `attemptId` (caller sends 202)
8. Async: send `content_published` Kafka event (fire-and-forget)

Also: a `retryPublish(creatorId, id)` method for the retry endpoint.

- [ ] **Step 1: Write failing tests**

```java
package com.tongji.knowpost.publish;

import com.tongji.common.exception.BusinessException;
import com.tongji.id.IdNamespace;
import com.tongji.id.IdService;
import com.tongji.knowpost.mapper.KnowPostMapper;
import com.tongji.knowpost.mapper.PublishStatusRow;
import com.tongji.knowpost.model.KnowPost;
import com.tongji.knowpost.service.impl.KnowPostServiceImpl;
import com.tongji.storage.MinioStorageService;
import com.tongji.storage.text.TextStorageService;
import com.tongji.storage.text.TextWriteException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PublishPipelineTest {

    @Mock KnowPostMapper mapper;
    @Mock PublishAttemptMapper attemptMapper;
    @Mock IdService idService;
    @Mock TextStorageService textStorageService;
    @Mock MinioStorageService storageService;
    @Mock RestTemplate restTemplate;
    @InjectMocks KnowPostServiceImpl service;

    private KnowPost draftPost(long id, long creatorId) {
        KnowPost p = new KnowPost();
        p.setId(id);
        p.setCreatorId(creatorId);
        p.setStatus("draft");
        p.setTitle("My Post");
        p.setContentObjectKey("posts/1/content.md");
        p.setContentUrl("http://minio/posts/1/content.md");
        return p;
    }

    @Test
    void publish_returnsAttemptId() {
        when(idService.nextId(IdNamespace.PUBLISH_ATTEMPT)).thenReturn(999L);
        when(mapper.findById(1L)).thenReturn(draftPost(1L, 10L));
        when(mapper.startPublishing(1L, 10L, 999L)).thenReturn(1);
        when(storageService.doesObjectExist("posts/1/content.md")).thenReturn(true);
        when(restTemplate.getForObject(anyString(), eq(String.class))).thenReturn("# Hello");
        when(mapper.completePublish(1L, 999L)).thenReturn(1);

        long attemptId = service.publish(10L, 1L);

        assertThat(attemptId).isEqualTo(999L);
        verify(textStorageService).savePostText(eq(1L), eq("# Hello"));
        verify(attemptMapper).succeed(999L);
    }

    @Test
    void publish_throwsWhenNotDraftOrWrongOwner() {
        when(idService.nextId(IdNamespace.PUBLISH_ATTEMPT)).thenReturn(100L);
        when(mapper.startPublishing(1L, 10L, 100L)).thenReturn(0); // CAS failed

        assertThatThrownBy(() -> service.publish(10L, 1L))
            .isInstanceOf(BusinessException.class);

        // Since CAS is now BEFORE insert, no attempt record should be created
        verify(attemptMapper, never()).insert(any());
        verify(textStorageService, never()).savePostText(anyLong(), anyString());
    }

    @Test
    void publish_marksFailedAndThrowsWhenCassandraFails() {
        when(idService.nextId(IdNamespace.PUBLISH_ATTEMPT)).thenReturn(200L);
        when(mapper.findById(1L)).thenReturn(draftPost(1L, 10L));
        when(mapper.startPublishing(1L, 10L, 200L)).thenReturn(1);
        when(storageService.doesObjectExist("posts/1/content.md")).thenReturn(true);
        when(restTemplate.getForObject(anyString(), eq(String.class))).thenReturn("# Hello");
        doThrow(new TextWriteException("Cassandra down", new RuntimeException()))
            .when(textStorageService).savePostText(eq(1L), anyString());

        assertThatThrownBy(() -> service.publish(10L, 1L))
            .isInstanceOf(TextWriteException.class);

        verify(mapper).failPublish(eq(1L), eq(200L), contains("Cassandra"));
        verify(attemptMapper).fail(eq(200L), anyString(), anyString());
        verify(mapper, never()).completePublish(anyLong(), anyLong());
    }

    @Test
    void publish_marksFailedWhenMediaMissing() {
        when(idService.nextId(IdNamespace.PUBLISH_ATTEMPT)).thenReturn(300L);
        when(mapper.findById(1L)).thenReturn(draftPost(1L, 10L));
        when(mapper.startPublishing(1L, 10L, 300L)).thenReturn(1);
        when(storageService.doesObjectExist("posts/1/content.md")).thenReturn(false);

        assertThatThrownBy(() -> service.publish(10L, 1L))
            .isInstanceOf(PublishValidationException.class)
            .satisfies(e -> assertThat(((PublishValidationException) e).getFailedStep())
                .isEqualTo("media_validation"));

        verify(mapper).failPublish(eq(1L), eq(300L), anyString());
    }
}
```

- [ ] **Step 2: Run tests — confirm they fail**

```bash
mvn test -Dtest=PublishPipelineTest -q 2>&1 | tail -5
```

Expected: compilation error — new methods don't exist yet in `KnowPostServiceImpl`

- [ ] **Step 3: Add publish pipeline dependencies to KnowPostServiceImpl**

Add new fields after existing ones. Note: `storageService` (`MinioStorageService`) and `idService` (`IdService`) are already present if `add-cassandra-text-storage` and `add-leaf-id-service` are done. **Do NOT add duplicate fields.** Only add fields that don't already exist:

```java
@Resource
private PublishAttemptMapper publishAttemptMapper;

// If IdService not yet injected (from add-leaf-id-service):
// @Resource private IdService idService;

// If TextStorageService not yet injected (from add-cassandra-text-storage):
// @Resource private TextStorageService textStorageService;

// If RestTemplate not yet injected (from add-cassandra-text-storage):
// @Resource private RestTemplate restTemplate;
```

> `MinioStorageService storageService` is already present as a final constructor-injected field. Use it directly as `storageService.doesObjectExist(...)` — do NOT add a second `MinioStorageService` field.

- [ ] **Step 4: Replace publish() with the new pipeline**

Replace the existing `publish(long creatorId, long id)` method with:

```java
/**
 * Attempt-based publish pipeline. Returns attemptId.
 * Caller should send HTTP 202 with the returned attemptId.
 *
 * NOT @Transactional: each DB operation auto-commits individually.
 * Reason: the catch block must persist failPublish() to set post → publish_failed.
 * If @Transactional were used, Spring would roll back failPublish() when the exception
 * propagates out of the method, leaving the post stuck in 'publishing' forever.
 */
public long publish(long creatorId, long id) {
    long attemptId = idService.nextId(IdNamespace.PUBLISH_ATTEMPT);

    // CAS: draft → publishing FIRST — before creating the attempt record.
    // If CAS fails (wrong owner or not draft), no attempt record is created (no orphan).
    int rows = mapper.startPublishing(id, creatorId, attemptId);
    if (rows == 0) {
        throw new BusinessException(ErrorCode.BAD_REQUEST, "草稿不存在或无权限");
    }

    // Create attempt record after CAS succeeds
    PublishAttempt attempt = new PublishAttempt();
    attempt.setAttemptId(attemptId);
    attempt.setPostId(id);
    attempt.setCreatorId(creatorId);
    attempt.setStatus("running");
    attempt.setRetryCount(0);
    publishAttemptMapper.insert(attempt);

    try {
        // Load post for validation
        KnowPost post = mapper.findById(id);
        validateForPublish(post, attemptId, id);

        // Fetch text from MinIO and write to Cassandra
        String body = fetchContentSafe(post.getContentUrl());
        if (body != null && !body.isBlank()) {
            textStorageService.savePostText(id, body);  // throws TextWriteException on failure
        }

        // CAS: publishing → published
        mapper.completePublish(id, attemptId);

        // Mark attempt succeeded
        publishAttemptMapper.succeed(attemptId);

        // Counter increment (fire-and-forget)
        try { userCounterService.incrementPosts(creatorId, 1); } catch (Exception ignored) {}

    } catch (PublishValidationException e) {
        mapper.failPublish(id, attemptId, e.getMessage());
        publishAttemptMapper.fail(attemptId, e.getFailedStep(), e.getMessage());
        throw e;
    } catch (Exception e) {
        String step = (e instanceof TextWriteException) ? "cassandra_write" : "pipeline";
        mapper.failPublish(id, attemptId, e.getMessage());
        publishAttemptMapper.fail(attemptId, step, e.getMessage());
        throw e;
    }

    return attemptId;
}

private void validateForPublish(KnowPost post, long attemptId, long postId) {
    if (post.getTitle() == null || post.getTitle().isBlank()) {
        throw new PublishValidationException("title_validation", "标题不能为空");
    }
    if (post.getContentObjectKey() == null || post.getContentObjectKey().isBlank()) {
        throw new PublishValidationException("content_validation", "正文未上传");
    }
    // storageService is the existing MinioStorageService field (constructor-injected)
    if (!storageService.doesObjectExist(post.getContentObjectKey())) {
        throw new PublishValidationException("media_validation", "内容对象在 MinIO 中不存在: " + post.getContentObjectKey());
    }
}

private String fetchContentSafe(String url) {
    if (url == null || url.isBlank()) return null;
    try {
        return restTemplate.getForObject(url, String.class);
    } catch (Exception e) {
        log.warn("Failed to fetch content from {}: {}", url, e.getMessage());
        return null;
    }
}

/**
 * Retry after publish_failed. Creates a new attempt.
 * NOT @Transactional for the same reason as publish() — failPublish must auto-commit.
 */
public long retryPublish(long creatorId, long id) {
    long newAttemptId = idService.nextId(IdNamespace.PUBLISH_ATTEMPT);

    // CAS: publish_failed → publishing FIRST (before creating attempt record)
    int rows = mapper.retryPublishing(id, creatorId, newAttemptId);
    if (rows == 0) {
        throw new BusinessException(ErrorCode.BAD_REQUEST, "帖子不在 publish_failed 状态或无权限");
    }

    // Create attempt record after CAS succeeds
    PublishAttempt prev = publishAttemptMapper.findLatestByPostId(id);
    int prevRetryCount = (prev != null) ? prev.getRetryCount() : 0;

    PublishAttempt attempt = new PublishAttempt();
    attempt.setAttemptId(newAttemptId);
    attempt.setPostId(id);
    attempt.setCreatorId(creatorId);
    attempt.setStatus("running");
    attempt.setRetryCount(prevRetryCount + 1);
    publishAttemptMapper.insert(attempt);

    try {
        KnowPost post = mapper.findById(id);
        validateForPublish(post, newAttemptId, id);
        String body = fetchContentSafe(post.getContentUrl());
        if (body != null && !body.isBlank()) {
            textStorageService.savePostText(id, body);
        }
        mapper.completePublish(id, newAttemptId);
        publishAttemptMapper.succeed(newAttemptId);
        try { userCounterService.incrementPosts(creatorId, 1); } catch (Exception ignored) {}
    } catch (PublishValidationException e) {
        mapper.failPublish(id, newAttemptId, e.getMessage());
        publishAttemptMapper.fail(newAttemptId, e.getFailedStep(), e.getMessage());
        throw e;
    } catch (Exception e) {
        String step = (e instanceof TextWriteException) ? "cassandra_write" : "pipeline";
        mapper.failPublish(id, newAttemptId, e.getMessage());
        publishAttemptMapper.fail(newAttemptId, step, e.getMessage());
        throw e;
    }
    return newAttemptId;
}
```

- [ ] **Step 5: Add retryPublishing SQL to KnowPostMapper**

Add method to `KnowPostMapper.java`:

```java
/** CAS: publish_failed → publishing for retry. */
int retryPublishing(@Param("id") long id,
                    @Param("creatorId") long creatorId,
                    @Param("attemptId") long attemptId);
```

Add to `KnowPostMapper.xml`:

```xml
<update id="retryPublishing">
    UPDATE know_posts
    SET status             = 'publishing',
        publish_attempt_id = #{attemptId},
        publish_failed_reason = NULL,
        update_time        = NOW(3)
    WHERE id = #{id}
      AND creator_id = #{creatorId}
      AND status = 'publish_failed'
</update>
```

- [ ] **Step 6: Run tests — all should pass**

```bash
mvn test -Dtest=PublishPipelineTest -q
```

Expected: `Tests run: 4, Failures: 0, Errors: 0`

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/tongji/knowpost/service/impl/KnowPostServiceImpl.java \
        src/main/java/com/tongji/knowpost/mapper/KnowPostMapper.java \
        src/main/resources/mapper/KnowPostMapper.xml \
        src/test/java/com/tongji/knowpost/publish/PublishPipelineTest.java
git commit -m "feat: implement attempt-based publish pipeline in KnowPostServiceImpl"
```

---

## Task 6: API Refactor (202 Response + Status + Retry Endpoints)

**Files:**
- Modify: `src/main/java/com/tongji/knowpost/api/KnowPostController.java`
- Create: `src/main/java/com/tongji/knowpost/publish/PublishAcceptedResponse.java`
- Create: `src/main/java/com/tongji/knowpost/publish/PublishStatusResponse.java`

- [ ] **Step 1: Create PublishAcceptedResponse.java**

```java
package com.tongji.knowpost.publish;

public class PublishAcceptedResponse {
    private final long attemptId;
    public PublishAcceptedResponse(long attemptId) { this.attemptId = attemptId; }
    public long getAttemptId() { return attemptId; }
}
```

- [ ] **Step 2: Create PublishStatusResponse.java**

```java
package com.tongji.knowpost.publish;

public class PublishStatusResponse {
    private long postId;
    private String postStatus;
    private Long attemptId;
    private String attemptStatus;   // running/succeeded/failed
    private String failedStep;
    private String errorMessage;
    private int retryCount;

    public long getPostId() { return postId; }
    public void setPostId(long postId) { this.postId = postId; }
    public String getPostStatus() { return postStatus; }
    public void setPostStatus(String postStatus) { this.postStatus = postStatus; }
    public Long getAttemptId() { return attemptId; }
    public void setAttemptId(Long attemptId) { this.attemptId = attemptId; }
    public String getAttemptStatus() { return attemptStatus; }
    public void setAttemptStatus(String attemptStatus) { this.attemptStatus = attemptStatus; }
    public String getFailedStep() { return failedStep; }
    public void setFailedStep(String failedStep) { this.failedStep = failedStep; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public int getRetryCount() { return retryCount; }
    public void setRetryCount(int retryCount) { this.retryCount = retryCount; }
}
```

- [ ] **Step 3: Update publish endpoint in KnowPostController.java**

Replace:

```java
@PostMapping("/{id}/publish")
public ResponseEntity<Void> publish(@PathVariable("id") long id,
                                    @AuthenticationPrincipal Jwt jwt) {
    long userId = jwtService.extractUserId(jwt);
    service.publish(userId, id);
    return ResponseEntity<Void>.noContent().build();
}
```

With:

```java
@PostMapping("/{id}/publish")
public ResponseEntity<PublishAcceptedResponse> publish(@PathVariable("id") long id,
                                                        @AuthenticationPrincipal Jwt jwt) {
    long userId = jwtService.extractUserId(jwt);
    long attemptId = service.publish(userId, id);
    return ResponseEntity.accepted().body(new PublishAcceptedResponse(attemptId));
}
```

- [ ] **Step 4: Add status query endpoint to KnowPostController.java**

Add after the publish endpoint:

```java
@GetMapping("/{id}/publish/status")
public ResponseEntity<PublishStatusResponse> publishStatus(@PathVariable("id") long id,
                                                            @AuthenticationPrincipal Jwt jwt) {
    long userId = jwtService.extractUserId(jwt);
    return ResponseEntity.ok(service.getPublishStatus(userId, id));
}

@PostMapping("/{id}/publish/retry")
public ResponseEntity<PublishAcceptedResponse> retryPublish(@PathVariable("id") long id,
                                                             @AuthenticationPrincipal Jwt jwt) {
    long userId = jwtService.extractUserId(jwt);
    long attemptId = service.retryPublish(userId, id);
    return ResponseEntity.accepted().body(new PublishAcceptedResponse(attemptId));
}
```

- [ ] **Step 5: Implement getPublishStatus in KnowPostServiceImpl**

Add to `KnowPostServiceImpl.java`:

```java
public PublishStatusResponse getPublishStatus(long creatorId, long postId) {
    PublishStatusRow row = mapper.findPublishStatus(postId);
    if (row == null) {
        throw new BusinessException(ErrorCode.NOT_FOUND, "帖子不存在");
    }
    PublishStatusResponse resp = new PublishStatusResponse();
    resp.setPostId(postId);
    resp.setPostStatus(row.getStatus());
    resp.setAttemptId(row.getPublishAttemptId());
    resp.setErrorMessage(row.getPublishFailedReason());

    if (row.getPublishAttemptId() != null) {
        PublishAttempt attempt = publishAttemptMapper.findById(row.getPublishAttemptId());
        if (attempt != null) {
            resp.setAttemptStatus(attempt.getStatus());
            resp.setFailedStep(attempt.getFailedStep());
            resp.setRetryCount(attempt.getRetryCount());
        }
    }
    return resp;
}
```

Check if `KnowPostService` interface exists:

```bash
grep -r "interface KnowPostService" src/main/java --include="*.java"
```

**If interface exists:** The existing `void publish(long creatorId, long id)` must change return type to `long`. Change it to:

```java
/** Was: void publish(...). Changed to return attemptId for 202 response. */
long publish(long creatorId, long id);

/** New method: retry after publish_failed. Returns new attemptId. */
long retryPublish(long creatorId, long id);

/** New method: publish status query. */
PublishStatusResponse getPublishStatus(long creatorId, long postId);
```

> Changing `void → long` is a breaking change. If any other class implements `KnowPostService` (e.g., a mock or test stub), it must be updated simultaneously.

**If no interface exists:** The controller injects `KnowPostServiceImpl` directly. No interface changes needed.

- [ ] **Step 6: Verify compilation**

```bash
mvn compile -q
```

Expected: `BUILD SUCCESS`

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/tongji/knowpost/api/KnowPostController.java \
        src/main/java/com/tongji/knowpost/publish/ \
        src/main/java/com/tongji/knowpost/service/impl/KnowPostServiceImpl.java
git commit -m "feat: change publish endpoint to 202, add status and retry endpoints"
```

---

## Task 7: ContentPublishedEvent + Kafka Producer

**Files:**
- Create: `src/main/java/com/tongji/knowpost/publish/ContentPublishedEvent.java`
- Create: `src/main/java/com/tongji/knowpost/publish/ContentPublishedProducer.java`
- Modify: `src/main/java/com/tongji/knowpost/service/impl/KnowPostServiceImpl.java`

- [ ] **Step 1: Create ContentPublishedEvent.java**

```java
package com.tongji.knowpost.publish;

import java.time.Instant;

public class ContentPublishedEvent {
    private long postId;
    private long creatorId;
    private long attemptId;
    private String contentUrl;      // for MinIO fallback in ES/RAG consumers
    private String contentObjectKey;
    private Instant publishedAt;

    public ContentPublishedEvent() {}

    public ContentPublishedEvent(long postId, long creatorId, long attemptId,
                                  String contentUrl, String contentObjectKey,
                                  Instant publishedAt) {
        this.postId = postId;
        this.creatorId = creatorId;
        this.attemptId = attemptId;
        this.contentUrl = contentUrl;
        this.contentObjectKey = contentObjectKey;
        this.publishedAt = publishedAt;
    }

    public long getPostId() { return postId; }
    public void setPostId(long postId) { this.postId = postId; }
    public long getCreatorId() { return creatorId; }
    public void setCreatorId(long creatorId) { this.creatorId = creatorId; }
    public long getAttemptId() { return attemptId; }
    public void setAttemptId(long attemptId) { this.attemptId = attemptId; }
    public String getContentUrl() { return contentUrl; }
    public void setContentUrl(String contentUrl) { this.contentUrl = contentUrl; }
    public String getContentObjectKey() { return contentObjectKey; }
    public void setContentObjectKey(String contentObjectKey) { this.contentObjectKey = contentObjectKey; }
    public Instant getPublishedAt() { return publishedAt; }
    public void setPublishedAt(Instant publishedAt) { this.publishedAt = publishedAt; }
}
```

- [ ] **Step 2: Create ContentPublishedProducer.java**

```java
package com.tongji.knowpost.publish;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;

@Component
public class ContentPublishedProducer {

    private static final Logger log = LoggerFactory.getLogger(ContentPublishedProducer.class);

    @Resource
    private KafkaTemplate<String, String> kafkaTemplate;

    @Resource
    private ObjectMapper objectMapper;

    @Value("${knowpost.kafka.content-published-topic:content-published}")
    private String topic;

    public void send(ContentPublishedEvent event) {
        try {
            String payload = objectMapper.writeValueAsString(event);
            kafkaTemplate.send(topic, String.valueOf(event.getPostId()), payload);
        } catch (Exception e) {
            log.error("Failed to send content_published event for postId={}: {}",
                event.getPostId(), e.getMessage());
        }
    }
}
```

- [ ] **Step 3: Inject producer into KnowPostServiceImpl and fire after successful publish**

Add field to `KnowPostServiceImpl`:

```java
@Resource
private ContentPublishedProducer contentPublishedProducer;
```

In `publish()`, after `publishAttemptMapper.succeed(attemptId)`, add:

```java
// Send content_published event (fire-and-forget; failures are logged, not fatal)
try {
    ContentPublishedEvent event = new ContentPublishedEvent(
        id, creatorId, attemptId,
        post.getContentUrl(), post.getContentObjectKey(),
        Instant.now());
    contentPublishedProducer.send(event);
} catch (Exception e) {
    log.warn("content_published event send failed for postId={}: {}", id, e.getMessage());
}
```

Add the same block to `retryPublish()` after its `publishAttemptMapper.succeed(newAttemptId)` line.

- [ ] **Step 4: Remove the old Outbox write from publish()**

The old publish flow wrote `outboxMapper.insert(...)` with `"KnowPostPublished"` payload. This is now replaced by the `content_published` Kafka event. Remove these lines from `publish()`:

```java
// DELETE these lines from publish():
long outId = idGen.nextId();
String payload = objectMapper.writeValueAsString(Map.of("entity", "knowpost", "op", "upsert", "id", id));
outboxMapper.insert(outId, "knowpost", id, "KnowPostPublished", payload);
```

> **Note:** The Outbox/Canal approach still handles metadata updates and deletes (`updateMetadata()` and `delete()` keep their Outbox writes). Only publish is migrated to direct Kafka.

- [ ] **Step 5: Compile**

```bash
mvn compile -q
```

Expected: `BUILD SUCCESS`

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/tongji/knowpost/publish/ \
        src/main/java/com/tongji/knowpost/service/impl/KnowPostServiceImpl.java
git commit -m "feat: add ContentPublishedEvent and Kafka producer, remove Outbox for publish"
```

---

## Task 8: PublishDerivedConsumer (ES / RAG / Feed / Counter)

**Files:**
- Create: `src/main/java/com/tongji/knowpost/publish/PublishDerivedConsumer.java`

This consumer listens to `content-published` and triggers ES indexing, RAG indexing, feed cache invalidation (invalidate `feed:item:{postId}` key), and counter initialization (already handled by `userCounterService.incrementPosts` in the pipeline — skip here). On any failure, log the error (reconciliation will be wired in by `add-data-reconciliation`).

- [ ] **Step 1: Create PublishDerivedConsumer.java**

```java
package com.tongji.knowpost.publish;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.llm.rag.RagIndexService;
import com.tongji.search.index.SearchIndexService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;

@Component
public class PublishDerivedConsumer {

    private static final Logger log = LoggerFactory.getLogger(PublishDerivedConsumer.class);

    @Resource private SearchIndexService searchIndexService;
    @Resource private RagIndexService ragIndexService;
    @Resource private StringRedisTemplate redis;
    @Resource private ObjectMapper objectMapper;

    @KafkaListener(
        topics = "${knowpost.kafka.content-published-topic:content-published}",
        groupId = "publish-derived-consumer"
    )
    public void onContentPublished(ConsumerRecord<String, String> record, Acknowledgment ack) {
        ContentPublishedEvent event;
        try {
            event = objectMapper.readValue(record.value(), ContentPublishedEvent.class);
        } catch (Exception e) {
            log.error("Failed to parse content_published event: {}", record.value(), e);
            ack.acknowledge();  // bad message — skip, don't block consumer
            return;
        }

        long postId = event.getPostId();

        // ES indexing
        try {
            searchIndexService.upsertKnowPost(postId);
        } catch (Exception e) {
            log.error("ES indexing failed for postId={}: {}", postId, e.getMessage());
            // TODO(add-data-reconciliation): write reconciliation_task(es_index, post, postId)
        }

        // RAG indexing
        try {
            ragIndexService.ensureIndexed(postId);
        } catch (Exception e) {
            log.error("RAG indexing failed for postId={}: {}", postId, e.getMessage());
            // TODO(add-data-reconciliation): write reconciliation_task(rag_index, post, postId)
        }

        // Feed cache invalidation: remove the cached item so next read fetches fresh
        try {
            redis.delete("feed:item:" + postId);
        } catch (Exception e) {
            log.warn("Feed cache invalidation failed for postId={}: {}", postId, e.getMessage());
        }

        ack.acknowledge();
    }
}
```

- [ ] **Step 2: Verify compilation**

```bash
mvn compile -q
```

Expected: `BUILD SUCCESS`

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/tongji/knowpost/publish/PublishDerivedConsumer.java
git commit -m "feat: add PublishDerivedConsumer for ES/RAG/feed on content-published events"
```

---

## Task 9: StuckPublishRecoveryTask

**Files:**
- Create: `src/main/java/com/tongji/knowpost/publish/StuckPublishRecoveryTask.java`

- [ ] **Step 1: Create StuckPublishRecoveryTask.java**

```java
package com.tongji.knowpost.publish;

import com.tongji.knowpost.mapper.KnowPostMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;

@Component
public class StuckPublishRecoveryTask {

    private static final Logger log = LoggerFactory.getLogger(StuckPublishRecoveryTask.class);
    private static final int STUCK_TIMEOUT_MINUTES = 5;

    @Resource
    private KnowPostMapper mapper;

    /**
     * Every 5 minutes: mark posts stuck in 'publishing' for >5 minutes as 'publish_failed'.
     * Covers: process crash mid-pipeline, timeout, unhandled exceptions.
     *
     * Race safety: uses CAS SQL WHERE status='publishing' AND update_time < NOW() - 5min.
     * Concurrent user retries use WHERE status='publish_failed' — different source state, no conflict.
     */
    @Scheduled(fixedDelay = 5 * 60 * 1000)
    public void recoverStuckPublishing() {
        try {
            int recovered = mapper.markStuckPublishingAsFailed(STUCK_TIMEOUT_MINUTES);
            if (recovered > 0) {
                log.warn("Recovered {} stuck-publishing posts → publish_failed", recovered);
            }
        } catch (Exception e) {
            log.error("StuckPublishRecoveryTask failed: {}", e.getMessage());
        }
    }
}
```

- [ ] **Step 2: Ensure @EnableScheduling is present in main app**

Check if `@EnableScheduling` exists in the main Spring Boot application class or any `@Configuration` class:

```bash
grep -r "@EnableScheduling" src/main/java --include="*.java"
```

If not found, add to the main `@SpringBootApplication` class:

```java
@EnableScheduling
@SpringBootApplication
public class ZhiguangApplication { ... }
```

- [ ] **Step 3: Verify compilation**

```bash
mvn compile -q
```

Expected: `BUILD SUCCESS`

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/tongji/knowpost/publish/StuckPublishRecoveryTask.java
git commit -m "feat: add scheduled stuck-publishing recovery task (5-min interval)"
```

---

## Task 10: Verification Tests

**Files:**
- Create: `src/test/java/com/tongji/knowpost/publish/PublishStateTest.java`

- [ ] **Step 1: Write state machine and derived task tests**

```java
package com.tongji.knowpost.publish;

import com.tongji.id.IdNamespace;
import com.tongji.id.IdService;
import com.tongji.knowpost.mapper.KnowPostMapper;
import com.tongji.knowpost.model.KnowPost;
import com.tongji.knowpost.service.impl.KnowPostServiceImpl;
import com.tongji.storage.MinioStorageService;
import com.tongji.storage.text.TextStorageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestTemplate;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PublishStateTest {

    @Mock KnowPostMapper mapper;
    @Mock PublishAttemptMapper attemptMapper;
    @Mock IdService idService;
    @Mock TextStorageService textStorageService;
    @Mock MinioStorageService storageService;
    @Mock RestTemplate restTemplate;
    @Mock ContentPublishedProducer contentPublishedProducer;
    @InjectMocks KnowPostServiceImpl service;

    private KnowPost draftPost(long id, long creatorId) {
        KnowPost p = new KnowPost();
        p.setId(id);
        p.setCreatorId(creatorId);
        p.setStatus("draft");
        p.setTitle("Test Post");
        p.setContentObjectKey("posts/1/content.md");
        p.setContentUrl("http://minio/posts/1/content.md");
        return p;
    }

    /**
     * tasks.md 5.1: State machine — publish transitions draft→publishing→published.
     */
    @Test
    void publishStateMachine_draftToPublished() {
        when(idService.nextId(IdNamespace.PUBLISH_ATTEMPT)).thenReturn(1L);
        when(mapper.findById(1L)).thenReturn(draftPost(1L, 10L));
        when(mapper.startPublishing(1L, 10L, 1L)).thenReturn(1);
        when(storageService.doesObjectExist("posts/1/content.md")).thenReturn(true);
        when(restTemplate.getForObject(anyString(), eq(String.class))).thenReturn("# body");
        when(mapper.completePublish(1L, 1L)).thenReturn(1);

        service.publish(10L, 1L);

        // Verify state transitions via SQL
        verify(mapper).startPublishing(1L, 10L, 1L);   // draft → publishing
        verify(mapper).completePublish(1L, 1L);         // publishing → published
        verify(attemptMapper).succeed(1L);
    }

    /**
     * tasks.md 5.2: Critical failure → publish_failed, NOT published.
     */
    @Test
    void criticalValidationFailure_postNeverReachesPublished() {
        when(idService.nextId(IdNamespace.PUBLISH_ATTEMPT)).thenReturn(2L);
        when(mapper.findById(1L)).thenReturn(draftPost(1L, 10L));
        when(mapper.startPublishing(1L, 10L, 2L)).thenReturn(1);
        when(storageService.doesObjectExist("posts/1/content.md")).thenReturn(false); // missing!

        try { service.publish(10L, 1L); } catch (PublishValidationException ignored) {}

        verify(mapper).failPublish(eq(1L), eq(2L), anyString());
        verify(mapper, never()).completePublish(anyLong(), anyLong());
        verify(attemptMapper).fail(eq(2L), eq("media_validation"), anyString());
    }

    /**
     * tasks.md 5.3: Derived task failure does NOT roll back the published state.
     * ES/RAG failures happen AFTER completePublish() and must NOT affect published status.
     */
    @Test
    void derivedTaskFailure_doesNotRollBackPublished() {
        when(idService.nextId(IdNamespace.PUBLISH_ATTEMPT)).thenReturn(3L);
        when(mapper.findById(1L)).thenReturn(draftPost(1L, 10L));
        when(mapper.startPublishing(1L, 10L, 3L)).thenReturn(1);
        when(storageService.doesObjectExist("posts/1/content.md")).thenReturn(true);
        when(restTemplate.getForObject(anyString(), eq(String.class))).thenReturn("# body");
        when(mapper.completePublish(1L, 3L)).thenReturn(1);
        // Producer failure (simulates ES/derived failure scenario)
        doThrow(new RuntimeException("Kafka down")).when(contentPublishedProducer).send(any());

        // publish() should still return successfully (derived failures are fire-and-forget)
        long attemptId = service.publish(10L, 1L);

        // Published state was committed
        verify(mapper).completePublish(1L, 3L);
        verify(attemptMapper).succeed(3L);
        // No rollback
        verify(mapper, never()).failPublish(anyLong(), anyLong(), anyString());
    }

    /**
     * tasks.md 2.3: Retry endpoint — retryPublish transitions publish_failed → published.
     */
    @Test
    void retryPublish_publishFailedToPublished() {
        when(idService.nextId(IdNamespace.PUBLISH_ATTEMPT)).thenReturn(10L);
        when(mapper.retryPublishing(1L, 10L, 10L)).thenReturn(1);  // CAS succeeds
        when(publishAttemptMapper.findLatestByPostId(1L)).thenReturn(null);  // first retry
        when(mapper.findById(1L)).thenReturn(draftPost(1L, 10L));
        when(storageService.doesObjectExist("posts/1/content.md")).thenReturn(true);
        when(restTemplate.getForObject(anyString(), eq(String.class))).thenReturn("# body");
        when(mapper.completePublish(1L, 10L)).thenReturn(1);

        long attemptId = service.retryPublish(10L, 1L);

        assertThat(attemptId).isEqualTo(10L);
        verify(mapper).retryPublishing(1L, 10L, 10L);   // publish_failed → publishing
        verify(mapper).completePublish(1L, 10L);          // publishing → published
        verify(attemptMapper).succeed(10L);
    }

    /**
     * tasks.md 5.4: Stuck publishing recovery — markStuckPublishingAsFailed is called.
     * Tested via StuckPublishRecoveryTask directly.
     */
    @Test
    void stuckRecovery_callsMarkStuckAsFailed() {
        StuckPublishRecoveryTask task = new StuckPublishRecoveryTask();
        // Inject mock via reflection (field name: mapper)
        try {
            java.lang.reflect.Field f = StuckPublishRecoveryTask.class.getDeclaredField("mapper");
            f.setAccessible(true);
            f.set(task, mapper);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        when(mapper.markStuckPublishingAsFailed(5)).thenReturn(2);

        task.recoverStuckPublishing();

        verify(mapper).markStuckPublishingAsFailed(5);
    }
}
```

- [ ] **Step 2: Run all verification tests**

```bash
mvn test -Dtest="PublishPipelineTest,PublishStateTest" -q
```

Expected: `Tests run: 9, Failures: 0, Errors: 0` (4 from PublishPipelineTest + 5 from PublishStateTest)

- [ ] **Step 3: Run full test suite**

```bash
mvn test -q
```

Expected: `BUILD SUCCESS` — no regressions

- [ ] **Step 4: Commit**

```bash
git add src/test/java/com/tongji/knowpost/publish/PublishStateTest.java
git commit -m "test: verify publish state machine, critical failures, and derived task isolation"
```

---

## Self-Review Checklist

**tasks.md coverage:**
- [x] 1.1 `publish_attempt` table (Task 1 + Task 2)
- [x] 1.2 `know_posts.status` extended with `publishing`, `publish_failed`, `rejected` (Task 1)
- [x] 1.3 `publish_attempt_id`, `publish_failed_reason` columns on `know_posts` (Task 1)
- [x] 2.1 Publish endpoint → 202 + `attemptId` (Task 6)
- [x] 2.2 Publish status query endpoint (Task 6)
- [x] 2.3 Publish retry endpoint (Task 5 `retryPublish` + Task 6)
- [x] 3.1 Attempt creation + `draft → publishing` CAS (Task 5)
- [x] 3.2 Validate permissions, state, title, content object (Task 5 `validateForPublish`)
- [x] 3.3 MinIO media object validation + Cassandra text write (Task 4 + Task 5)
- [x] 3.4 Content parsing (deferred to ES consumer — `SearchIndexService.upsertKnowPost` already does this in Task 8)
- [x] 3.5 Official publish DB atomic update via `completePublish` CAS (Task 5)
- [x] 4.1 ES indexing after publish (Task 8 `PublishDerivedConsumer`)
- [x] 4.2 RAG pre-indexing after publish (Task 8)
- [x] 4.3 Feed cache invalidation (Task 8 `redis.delete("feed:item:*")`)
- [x] 4.4 Counter init — `userCounterService.incrementPosts` called in pipeline (Task 5); removed from Outbox-driven flow
- [x] 4.5 `content_published` recommendation event (Task 7 producer; recommendation consumer is in `add-recommendation-and-follow-feed`)
- [x] 4.6 Derived task failure → reconciliation (Task 8 TODO comment; full wiring in `add-data-reconciliation`)
- [x] 5.1 State machine tests (Task 10 `publishStateMachine_draftToPublished`)
- [x] 5.2 Critical failure → `publish_failed` test (Task 10)
- [x] 5.3 Derived failure doesn't roll back published (Task 10)
- [x] 5.4 Stuck publishing recovery test (Task 10 `stuckRecovery_callsMarkStuckAsFailed`)
- [x] 2.3 Retry state transition tested (Task 10 `retryPublish_publishFailedToPublished`)

**No placeholders.**

**Bugs fixed during review:**
- **Critical:** Removed `@Transactional` from `publish()` and `retryPublish()` — with `@Transactional`, Spring rolls back the entire transaction on exception, including the `failPublish()` call in the catch block, leaving posts stuck in `publishing` forever. Without it, each SQL auto-commits immediately; `failPublish()` persists correctly.
- **Critical:** Moved `publishAttemptMapper.insert()` to AFTER the CAS check — if CAS fails (wrong owner/not draft), no orphaned `running` attempt is created.
- **Naming conflict:** Replaced `minioStorageService.doesObjectExist()` with `storageService.doesObjectExist()` — `storageService` (`MinioStorageService`) is already a final constructor-injected field; adding `minioStorageService` would duplicate it.
- **Placeholder removed:** Task 6 KnowPostService interface step now has actual method signatures.
- **Test updated:** `publish_throwsWhenNotDraftOrWrongOwner` now verifies `attemptMapper.insert()` is never called (since CAS is now first).

**Bugs fixed during review (round 2):**
- Task 6: Interface step now explicitly says `void → long` is a breaking change requiring interface update
- `PublishStateTest`: Removed unused `import PublishStatusRow`
- Task 10: Added `retryPublish_publishFailedToPublished` test to cover task 2.3 retry state transition

**Type consistency:**
- `PublishAttemptMapper.insert/succeed/fail/findById` used in Task 5 match Task 2 definitions
- `KnowPostMapper.startPublishing/completePublish/failPublish/retryPublishing` used in Tasks 5+10 match Task 3 definitions
- `ContentPublishedEvent` constructor in Task 7 matches fields used in Task 8 consumer
- `PublishValidationException(failedStep, message)` in Task 4 matches usage in Task 5 + assertions in Task 10
- `StuckPublishRecoveryTask.recoverStuckPublishing()` in Task 9 matches test in Task 10
