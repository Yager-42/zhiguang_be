# add-comment-system Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement a two-level comment system (top-level + replies) with async write semantics (202 + pendingCommentId), comment text stored in Cassandra, metadata in MySQL, and idempotent Kafka-based persistence with dead-letter failure handling.

**Architecture:** The submit API pre-generates a Snowflake `commentId` (= `pendingCommentId`), writes to Kafka's `comment-write` topic, and returns 202. A consumer batch-writes text to Cassandra and metadata to MySQL; `client_request_id` unique index handles idempotency. Failures go to `comment-write.DLT` via `@RetryableTopic`, which updates `pending_comments.status = failed`. Comment likes reuse the existing `CounterService` with `entityType="comment"`. Post comment counts and reply counts use `CounterEventProducer` with `idx=3`.

**Tech Stack:** Spring Boot 3.2.4, MyBatis, Kafka (`@RetryableTopic`), `TextStorageService` (from `add-cassandra-text-storage`), `IdService` (from `add-leaf-id-service`), `CounterService` + `CounterEventProducer` (existing).

**Prerequisites:** `add-cassandra-text-storage` and `add-leaf-id-service` must be implemented first.

**Key design decisions (from design.md):**
- `pendingCommentId` = final `comment_id` (same Snowflake ID, no mapping)
- Kafka topic: `comment-write` (independent, not Canal Outbox)
- Pagination: pure MySQL cursor (no Redis cache)
- Soft delete: placeholder text "该评论已删除", hard-delete Cassandra row
- Idempotency: MySQL unique index on `client_request_id`
- Dead letter: `@RetryableTopic` → `comment-write.DLT` → update status to `failed`
- Counter: reuse existing SDS with `entityType="comment"` for likes; `idx=3` for comment/reply counts

---

## File Map

**New files:**
- `db/schema.sql` — add `comments` and `pending_comments` tables
- `src/main/java/com/tongji/comment/model/Comment.java`
- `src/main/java/com/tongji/comment/model/PendingComment.java`
- `src/main/java/com/tongji/comment/mapper/CommentMapper.java` + XML
- `src/main/java/com/tongji/comment/mapper/PendingCommentMapper.java` + XML
- `src/main/java/com/tongji/comment/dto/` — request/response DTOs
- `src/main/java/com/tongji/comment/event/CommentWriteEvent.java`
- `src/main/java/com/tongji/comment/event/CommentFeedbackEvent.java`
- `src/main/java/com/tongji/comment/event/CommentFeedbackProducer.java`
- `src/main/java/com/tongji/comment/config/CommentKafkaConfig.java`
- `src/main/java/com/tongji/comment/service/CommentService.java`
- `src/main/java/com/tongji/comment/service/impl/CommentServiceImpl.java`
- `src/main/java/com/tongji/comment/consumer/CommentWriteConsumer.java`
- `src/main/java/com/tongji/comment/api/CommentController.java`
- `src/test/java/com/tongji/comment/service/CommentServiceImplTest.java`
- `src/test/java/com/tongji/comment/consumer/CommentWriteConsumerTest.java`
- `src/test/java/com/tongji/comment/CommentVerificationTest.java`

**Modified files:**
- `db/schema.sql`
- `src/main/resources/application.yml`

---

## Task 1: DB Schema + application.yml

**Files:**
- Modify: `db/schema.sql`
- Modify: `src/main/resources/application.yml`

- [ ] **Step 1: Append comments table to db/schema.sql**

```sql
CREATE TABLE IF NOT EXISTS comments (
    comment_id        BIGINT        NOT NULL,
    post_id           BIGINT        NOT NULL,
    root_id           BIGINT        NOT NULL DEFAULT 0,  -- 0 = top-level comment
    parent_id         BIGINT        NOT NULL DEFAULT 0,  -- 0 = top-level comment
    creator_id        BIGINT        NOT NULL,
    client_request_id VARCHAR(64)   NOT NULL,
    status            TINYINT       NOT NULL DEFAULT 0,  -- 0=normal, 1=deleted
    like_count        INT           NOT NULL DEFAULT 0,
    reply_count       INT           NOT NULL DEFAULT 0,
    create_time       DATETIME(3)   NOT NULL,
    update_time       DATETIME(3)   NOT NULL,
    PRIMARY KEY (comment_id),
    UNIQUE KEY uk_client_request_id (client_request_id),
    KEY idx_post_comments (post_id, root_id, comment_id),
    KEY idx_root_replies  (root_id, comment_id),
    KEY idx_creator       (creator_id, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS pending_comments (
    pending_comment_id BIGINT      NOT NULL,
    creator_id         BIGINT      NOT NULL,
    post_id            BIGINT      NOT NULL,
    status             VARCHAR(16) NOT NULL DEFAULT 'pending',  -- pending/succeeded/failed
    create_time        DATETIME(3) NOT NULL,
    update_time        DATETIME(3) NOT NULL,
    PRIMARY KEY (pending_comment_id),
    KEY idx_creator_time (creator_id, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

- [ ] **Step 2: Add comment Kafka topic config to application.yml**

Add as a new top-level key (not nested under `spring:`):

```yaml
comment:
  kafka:
    write-topic: comment-write
    feedback-topic: comment-feedback
```

- [ ] **Step 3: Apply schema to local DB**

```bash
cd /Users/huangyaokai/zhiguang_be
docker compose exec mysql mysql -uroot -proot zhiguang < db/schema.sql
docker compose exec mysql mysql -uroot -proot zhiguang \
  -e "DESCRIBE comments; DESCRIBE pending_comments;"
```

Expected: both tables exist with all columns.

- [ ] **Step 4: Commit**

```bash
git add db/schema.sql src/main/resources/application.yml
git commit -m "feat: add comments and pending_comments schema"
```

---

## Task 2: Comment + PendingComment Models + Mappers

**Files:**
- Create: `src/main/java/com/tongji/comment/model/Comment.java`
- Create: `src/main/java/com/tongji/comment/model/PendingComment.java`
- Create: `src/main/java/com/tongji/comment/mapper/CommentMapper.java`
- Create: `src/main/resources/mapper/CommentMapper.xml`
- Create: `src/main/java/com/tongji/comment/mapper/PendingCommentMapper.java`
- Create: `src/main/resources/mapper/PendingCommentMapper.xml`

- [ ] **Step 1: Create Comment.java**

```java
package com.tongji.comment.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Comment {
    private Long commentId;
    private Long postId;
    private Long rootId;           // 0 = top-level
    private Long parentId;         // 0 = top-level
    private Long creatorId;
    private String clientRequestId;
    private Integer status;        // 0=normal, 1=deleted
    private Integer likeCount;
    private Integer replyCount;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
```

- [ ] **Step 2: Create PendingComment.java**

```java
package com.tongji.comment.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PendingComment {
    private Long pendingCommentId;
    private Long creatorId;
    private Long postId;
    private String status;         // pending/succeeded/failed
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
```

- [ ] **Step 3: Create CommentMapper.java**

```java
package com.tongji.comment.mapper;

import com.tongji.comment.model.Comment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface CommentMapper {

    void insert(Comment comment);

    /** Cursor-paginated top-level comments for a post. cursor=null for first page. */
    List<Comment> findTopLevel(@Param("postId") long postId,
                               @Param("cursor") Long cursor,
                               @Param("limit") int limit);

    /** Cursor-paginated replies under a root comment. */
    List<Comment> findReplies(@Param("rootId") long rootId,
                              @Param("cursor") Long cursor,
                              @Param("limit") int limit);

    /** Soft-delete: returns rows updated (0 if not found or wrong owner). */
    int softDelete(@Param("commentId") long commentId,
                   @Param("creatorId") long creatorId);

    Comment findById(@Param("commentId") long commentId);
}
```

- [ ] **Step 4: Create CommentMapper.xml**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="com.tongji.comment.mapper.CommentMapper">

    <insert id="insert">
        INSERT INTO comments
            (comment_id, post_id, root_id, parent_id, creator_id,
             client_request_id, status, like_count, reply_count,
             create_time, update_time)
        VALUES
            (#{commentId}, #{postId}, #{rootId}, #{parentId}, #{creatorId},
             #{clientRequestId}, 0, 0, 0,
             NOW(3), NOW(3))
    </insert>

    <select id="findTopLevel" resultType="com.tongji.comment.model.Comment">
        SELECT comment_id, post_id, root_id, parent_id, creator_id,
               client_request_id, status, like_count, reply_count,
               create_time, update_time
        FROM comments
        WHERE post_id = #{postId}
          AND root_id = 0
          <if test="cursor != null">AND comment_id &gt; #{cursor}</if>
        ORDER BY comment_id ASC
        LIMIT #{limit}
    </select>

    <select id="findReplies" resultType="com.tongji.comment.model.Comment">
        SELECT comment_id, post_id, root_id, parent_id, creator_id,
               client_request_id, status, like_count, reply_count,
               create_time, update_time
        FROM comments
        WHERE root_id = #{rootId}
          <if test="cursor != null">AND comment_id &gt; #{cursor}</if>
        ORDER BY comment_id ASC
        LIMIT #{limit}
    </select>

    <update id="softDelete">
        UPDATE comments
        SET status = 1, update_time = NOW(3)
        WHERE comment_id = #{commentId}
          AND creator_id = #{creatorId}
          AND status = 0
    </update>

    <select id="findById" resultType="com.tongji.comment.model.Comment">
        SELECT comment_id, post_id, root_id, parent_id, creator_id,
               client_request_id, status, like_count, reply_count,
               create_time, update_time
        FROM comments
        WHERE comment_id = #{commentId}
    </select>

</mapper>
```

- [ ] **Step 5: Create PendingCommentMapper.java**

```java
package com.tongji.comment.mapper;

import com.tongji.comment.model.PendingComment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface PendingCommentMapper {

    void insert(PendingComment pending);

    void updateStatus(@Param("pendingCommentId") long pendingCommentId,
                      @Param("status") String status);

    PendingComment findById(@Param("pendingCommentId") long pendingCommentId);
}
```

- [ ] **Step 6: Create PendingCommentMapper.xml**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="com.tongji.comment.mapper.PendingCommentMapper">

    <insert id="insert">
        INSERT INTO pending_comments
            (pending_comment_id, creator_id, post_id, status, create_time, update_time)
        VALUES
            (#{pendingCommentId}, #{creatorId}, #{postId}, 'pending', NOW(3), NOW(3))
    </insert>

    <update id="updateStatus">
        UPDATE pending_comments
        SET status = #{status}, update_time = NOW(3)
        WHERE pending_comment_id = #{pendingCommentId}
    </update>

    <select id="findById" resultType="com.tongji.comment.model.PendingComment">
        SELECT pending_comment_id, creator_id, post_id, status, create_time, update_time
        FROM pending_comments
        WHERE pending_comment_id = #{pendingCommentId}
    </select>

</mapper>
```

- [ ] **Step 7: Verify compilation**

```bash
mvn compile -q
```

Expected: `BUILD SUCCESS`

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/tongji/comment/model/ \
        src/main/java/com/tongji/comment/mapper/ \
        src/main/resources/mapper/CommentMapper.xml \
        src/main/resources/mapper/PendingCommentMapper.xml
git commit -m "feat: add comment models and mappers"
```

---

## Task 3: DTOs + Events + CommentService Interface

**Files:**
- Create: `src/main/java/com/tongji/comment/dto/CommentSubmitRequest.java`
- Create: `src/main/java/com/tongji/comment/dto/CommentSubmitResponse.java`
- Create: `src/main/java/com/tongji/comment/dto/CommentStatusResponse.java`
- Create: `src/main/java/com/tongji/comment/dto/CommentPageResponse.java`
- Create: `src/main/java/com/tongji/comment/dto/CommentItem.java`
- Create: `src/main/java/com/tongji/comment/event/CommentWriteEvent.java`
- Create: `src/main/java/com/tongji/comment/event/CommentFeedbackEvent.java`
- Create: `src/main/java/com/tongji/comment/service/CommentService.java`

- [ ] **Step 1: Create request/response DTOs**

```java
// CommentSubmitRequest.java
package com.tongji.comment.dto;

public class CommentSubmitRequest {
    private long postId;
    private Long parentCommentId;  // null for top-level
    private String clientRequestId;
    private String body;

    public long getPostId() { return postId; }
    public void setPostId(long postId) { this.postId = postId; }
    public Long getParentCommentId() { return parentCommentId; }
    public void setParentCommentId(Long parentCommentId) { this.parentCommentId = parentCommentId; }
    public String getClientRequestId() { return clientRequestId; }
    public void setClientRequestId(String clientRequestId) { this.clientRequestId = clientRequestId; }
    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }
}
```

```java
// CommentSubmitResponse.java
package com.tongji.comment.dto;

public class CommentSubmitResponse {
    private final long pendingCommentId;
    private final String clientRequestId;
    public CommentSubmitResponse(long pendingCommentId, String clientRequestId) {
        this.pendingCommentId = pendingCommentId;
        this.clientRequestId = clientRequestId;
    }
    public long getPendingCommentId() { return pendingCommentId; }
    public String getClientRequestId() { return clientRequestId; }
}
```

```java
// CommentStatusResponse.java
package com.tongji.comment.dto;

public class CommentStatusResponse {
    private long pendingCommentId;
    private String status;  // pending/succeeded/failed
    public long getPendingCommentId() { return pendingCommentId; }
    public void setPendingCommentId(long pendingCommentId) { this.pendingCommentId = pendingCommentId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
```

```java
// CommentItem.java
package com.tongji.comment.dto;

import java.time.LocalDateTime;

public class CommentItem {
    private long commentId;
    private long postId;
    private long rootId;
    private long parentId;
    private long creatorId;
    private String body;          // null if deleted (use placeholder in API layer)
    private boolean deleted;      // true if status=1
    private int likeCount;
    private int replyCount;
    private LocalDateTime createTime;

    public long getCommentId() { return commentId; }
    public void setCommentId(long commentId) { this.commentId = commentId; }
    public long getPostId() { return postId; }
    public void setPostId(long postId) { this.postId = postId; }
    public long getRootId() { return rootId; }
    public void setRootId(long rootId) { this.rootId = rootId; }
    public long getParentId() { return parentId; }
    public void setParentId(long parentId) { this.parentId = parentId; }
    public long getCreatorId() { return creatorId; }
    public void setCreatorId(long creatorId) { this.creatorId = creatorId; }
    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }
    public boolean isDeleted() { return deleted; }
    public void setDeleted(boolean deleted) { this.deleted = deleted; }
    public int getLikeCount() { return likeCount; }
    public void setLikeCount(int likeCount) { this.likeCount = likeCount; }
    public int getReplyCount() { return replyCount; }
    public void setReplyCount(int replyCount) { this.replyCount = replyCount; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
}
```

```java
// CommentPageResponse.java
package com.tongji.comment.dto;

import java.util.List;

public class CommentPageResponse {
    private List<CommentItem> items;
    private Long nextCursor;    // null if no more pages
    private boolean hasMore;

    public List<CommentItem> getItems() { return items; }
    public void setItems(List<CommentItem> items) { this.items = items; }
    public Long getNextCursor() { return nextCursor; }
    public void setNextCursor(Long nextCursor) { this.nextCursor = nextCursor; }
    public boolean isHasMore() { return hasMore; }
    public void setHasMore(boolean hasMore) { this.hasMore = hasMore; }
}
```

- [ ] **Step 2: Create CommentWriteEvent.java**

```java
package com.tongji.comment.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CommentWriteEvent {
    private long commentId;       // = pendingCommentId (same Snowflake ID)
    private long postId;
    private long rootId;          // 0 for top-level
    private long parentId;        // 0 for top-level
    private long creatorId;
    private String clientRequestId;
    private String body;
}
```

- [ ] **Step 3: Create CommentFeedbackEvent.java**

```java
package com.tongji.comment.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CommentFeedbackEvent {
    private String action;        // "comment", "delete", "like", "unlike"
    private long commentId;
    private long postId;
    private long userId;
}
```

- [ ] **Step 4: Create CommentService.java**

```java
package com.tongji.comment.service;

import com.tongji.comment.dto.*;

public interface CommentService {

    /**
     * Async submit. Returns 202 response with pendingCommentId = final commentId.
     */
    CommentSubmitResponse submit(long creatorId, CommentSubmitRequest request);

    CommentStatusResponse getStatus(long pendingCommentId);

    /** Cursor-paginated top-level comments for a post. */
    CommentPageResponse getTopLevel(long postId, Long cursor, int limit);

    /** Cursor-paginated replies for a root comment. */
    CommentPageResponse getReplies(long rootCommentId, Long cursor, int limit);

    /**
     * Soft-delete. Returns false if not found or wrong owner.
     */
    boolean deleteComment(long creatorId, long commentId);

    boolean likeComment(long userId, long commentId);

    boolean unlikeComment(long userId, long commentId);
}
```

- [ ] **Step 5: Verify compilation**

```bash
mvn compile -q
```

Expected: `BUILD SUCCESS`

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/tongji/comment/
git commit -m "feat: add comment DTOs, events, and service interface"
```

---

## Task 4: CommentServiceImpl (TDD)

**Files:**
- Create: `src/test/java/com/tongji/comment/service/CommentServiceImplTest.java`
- Create: `src/main/java/com/tongji/comment/service/impl/CommentServiceImpl.java`

- [ ] **Step 1: Write failing tests**

```java
package com.tongji.comment.service;

import com.tongji.comment.dto.*;
import com.tongji.comment.event.CommentWriteEvent;
import com.tongji.comment.mapper.CommentMapper;
import com.tongji.comment.mapper.PendingCommentMapper;
import com.tongji.comment.model.Comment;
import com.tongji.comment.model.PendingComment;
import com.tongji.comment.service.impl.CommentServiceImpl;
import com.tongji.counter.service.CounterService;
import com.tongji.id.IdNamespace;
import com.tongji.id.IdService;
import com.tongji.storage.text.TextStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CommentServiceImplTest {

    @Mock IdService idService;
    @Mock CommentMapper commentMapper;
    @Mock PendingCommentMapper pendingCommentMapper;
    @Mock KafkaTemplate<String, String> kafkaTemplate;
    @Mock CounterService counterService;
    @Mock TextStorageService textStorageService;
    // Needed after Task 7 adds feedbackProducer; null-safe because submit() wraps send() in try-catch
    @Mock com.tongji.comment.event.CommentFeedbackProducer feedbackProducer;
    @InjectMocks CommentServiceImpl service;

    @BeforeEach
    void setUp() {
        // @Value fields are NOT injected by @InjectMocks (no Spring context).
        // Set the Kafka topic name so kafkaTemplate.send() receives a non-null topic.
        org.springframework.test.util.ReflectionTestUtils.setField(service, "writeTopic", "comment-write");
    }

    @Test
    void submit_returnsAttemptIdEqualToCommentId() {
        when(idService.nextId(IdNamespace.COMMENT)).thenReturn(777L);
        CommentSubmitRequest req = new CommentSubmitRequest();
        req.setPostId(1L);
        req.setClientRequestId("req-abc");
        req.setBody("Great post!");

        CommentSubmitResponse resp = service.submit(10L, req);

        assertThat(resp.getPendingCommentId()).isEqualTo(777L);
        assertThat(resp.getClientRequestId()).isEqualTo("req-abc");
        verify(pendingCommentMapper).insert(argThat(p ->
            p.getPendingCommentId() == 777L && "pending".equals(p.getStatus())));
        verify(kafkaTemplate).send(anyString(), eq("777"), anyString());
    }

    @Test
    void submit_topLevel_setsRootAndParentToZero() {
        when(idService.nextId(IdNamespace.COMMENT)).thenReturn(1L);
        CommentSubmitRequest req = new CommentSubmitRequest();
        req.setPostId(10L);
        req.setParentCommentId(null);  // top-level
        req.setClientRequestId("c1");
        req.setBody("hi");

        service.submit(99L, req);

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(anyString(), anyString(), payloadCaptor.capture());
        String payload = payloadCaptor.getValue();
        assertThat(payload).contains("\"rootId\":0").contains("\"parentId\":0");
    }

    @Test
    void submit_reply_setsRootIdFromParent() {
        when(idService.nextId(IdNamespace.COMMENT)).thenReturn(2L);
        // Parent comment (root_id=0 means parent IS the root)
        Comment parent = Comment.builder()
            .commentId(50L).postId(10L).rootId(0L).build();
        when(commentMapper.findById(50L)).thenReturn(parent);

        CommentSubmitRequest req = new CommentSubmitRequest();
        req.setPostId(10L);
        req.setParentCommentId(50L);
        req.setClientRequestId("c2");
        req.setBody("reply");

        service.submit(99L, req);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(anyString(), anyString(), captor.capture());
        // rootId should be the parent's commentId (since parent is top-level, rootId=0 means itself is root)
        assertThat(captor.getValue()).contains("\"rootId\":50");
    }

    @Test
    void getStatus_returnsPendingStatus() {
        PendingComment pc = PendingComment.builder()
            .pendingCommentId(100L).status("succeeded").build();
        when(pendingCommentMapper.findById(100L)).thenReturn(pc);

        CommentStatusResponse resp = service.getStatus(100L);

        assertThat(resp.getPendingCommentId()).isEqualTo(100L);
        assertThat(resp.getStatus()).isEqualTo("succeeded");
    }

    @Test
    void deleteComment_softDeletesAndReturnsTrue() {
        when(commentMapper.softDelete(200L, 10L)).thenReturn(1);
        assertThat(service.deleteComment(10L, 200L)).isTrue();
        verify(commentMapper).softDelete(200L, 10L);
    }

    @Test
    void deleteComment_returnsFalseWhenNotFound() {
        when(commentMapper.softDelete(200L, 10L)).thenReturn(0);
        assertThat(service.deleteComment(10L, 200L)).isFalse();
    }

    @Test
    void likeComment_delegatesToCounterService() {
        when(counterService.like("comment", "300", 10L)).thenReturn(true);
        assertThat(service.likeComment(10L, 300L)).isTrue();
        verify(counterService).like("comment", "300", 10L);
    }

    @Test
    void unlikeComment_delegatesToCounterService() {
        when(counterService.unlike("comment", "300", 10L)).thenReturn(true);
        assertThat(service.unlikeComment(10L, 300L)).isTrue();
        verify(counterService).unlike("comment", "300", 10L);
    }
}
```

- [ ] **Step 2: Run tests — confirm they fail**

```bash
mvn test -Dtest=CommentServiceImplTest -q 2>&1 | tail -5
```

Expected: compilation error — `CommentServiceImpl` doesn't exist yet

- [ ] **Step 3: Create CommentServiceImpl.java**

```java
package com.tongji.comment.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.comment.dto.*;
import com.tongji.comment.event.CommentWriteEvent;
import com.tongji.comment.mapper.CommentMapper;
import com.tongji.comment.mapper.PendingCommentMapper;
import com.tongji.comment.model.Comment;
import com.tongji.comment.model.PendingComment;
import com.tongji.comment.service.CommentService;
import com.tongji.counter.service.CounterService;
import com.tongji.id.IdNamespace;
import com.tongji.id.IdService;
import com.tongji.storage.text.TextStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

@Service
public class CommentServiceImpl implements CommentService {

    private static final Logger log = LoggerFactory.getLogger(CommentServiceImpl.class);
    private static final String DELETED_PLACEHOLDER = "该评论已删除";
    private static final int DEFAULT_PAGE_SIZE = 20;

    @Resource private IdService idService;
    @Resource private CommentMapper commentMapper;
    @Resource private PendingCommentMapper pendingCommentMapper;
    @Resource private KafkaTemplate<String, String> kafkaTemplate;
    @Resource private CounterService counterService;
    @Resource private TextStorageService textStorageService;
    @Resource private ObjectMapper objectMapper;

    @Value("${comment.kafka.write-topic:comment-write}")
    private String writeTopic;

    @Override
    public CommentSubmitResponse submit(long creatorId, CommentSubmitRequest request) {
        long commentId = idService.nextId(IdNamespace.COMMENT);

        // Determine root/parent IDs
        long rootId = 0L;
        long parentId = 0L;
        if (request.getParentCommentId() != null) {
            Comment parent = commentMapper.findById(request.getParentCommentId());
            if (parent != null) {
                parentId = parent.getCommentId();
                // rootId = parent's rootId if it's a reply, or parent itself if top-level
                rootId = (parent.getRootId() == 0) ? parent.getCommentId() : parent.getRootId();
            }
        }

        // Insert pending record
        pendingCommentMapper.insert(PendingComment.builder()
            .pendingCommentId(commentId)
            .creatorId(creatorId)
            .postId(request.getPostId())
            .status("pending")
            .build());

        // Send to Kafka
        CommentWriteEvent event = CommentWriteEvent.builder()
            .commentId(commentId)
            .postId(request.getPostId())
            .rootId(rootId)
            .parentId(parentId)
            .creatorId(creatorId)
            .clientRequestId(request.getClientRequestId())
            .body(request.getBody())
            .build();

        try {
            String payload = objectMapper.writeValueAsString(event);
            kafkaTemplate.send(writeTopic, String.valueOf(commentId), payload);
        } catch (Exception e) {
            log.error("Failed to send comment write event for commentId={}: {}", commentId, e.getMessage());
            // Pending status stays 'pending'; client can poll and retry
        }

        return new CommentSubmitResponse(commentId, request.getClientRequestId());
    }

    @Override
    public CommentStatusResponse getStatus(long pendingCommentId) {
        PendingComment pc = pendingCommentMapper.findById(pendingCommentId);
        CommentStatusResponse resp = new CommentStatusResponse();
        resp.setPendingCommentId(pendingCommentId);
        resp.setStatus(pc != null ? pc.getStatus() : "not_found");
        return resp;
    }

    @Override
    public CommentPageResponse getTopLevel(long postId, Long cursor, int limit) {
        int pageLimit = Math.min(limit, DEFAULT_PAGE_SIZE);
        List<Comment> comments = commentMapper.findTopLevel(postId, cursor, pageLimit + 1);
        return buildPageResponse(comments, pageLimit);
    }

    @Override
    public CommentPageResponse getReplies(long rootCommentId, Long cursor, int limit) {
        int pageLimit = Math.min(limit, DEFAULT_PAGE_SIZE);
        List<Comment> comments = commentMapper.findReplies(rootCommentId, cursor, pageLimit + 1);
        return buildPageResponse(comments, pageLimit);
    }

    private CommentPageResponse buildPageResponse(List<Comment> comments, int pageLimit) {
        boolean hasMore = comments.size() > pageLimit;
        List<Comment> page = hasMore ? comments.subList(0, pageLimit) : comments;

        // Batch read text from Cassandra for non-deleted
        List<Long> nonDeletedIds = new ArrayList<>();
        for (Comment c : page) {
            if (c.getStatus() == 0) nonDeletedIds.add(c.getCommentId());
        }
        Map<Long, String> bodies = nonDeletedIds.isEmpty()
            ? Map.of()
            : textStorageService.getCommentTexts(nonDeletedIds);

        List<CommentItem> items = new ArrayList<>();
        for (Comment c : page) {
            CommentItem item = new CommentItem();
            item.setCommentId(c.getCommentId());
            item.setPostId(c.getPostId());
            item.setRootId(c.getRootId());
            item.setParentId(c.getParentId());
            item.setCreatorId(c.getCreatorId());
            item.setLikeCount(c.getLikeCount());
            item.setReplyCount(c.getReplyCount());
            item.setCreateTime(c.getCreateTime());
            boolean deleted = c.getStatus() == 1;
            item.setDeleted(deleted);
            item.setBody(deleted ? DELETED_PLACEHOLDER : bodies.get(c.getCommentId()));
            items.add(item);
        }

        CommentPageResponse resp = new CommentPageResponse();
        resp.setItems(items);
        resp.setHasMore(hasMore);
        resp.setNextCursor(hasMore ? page.get(page.size() - 1).getCommentId() : null);
        return resp;
    }

    @Override
    public boolean deleteComment(long creatorId, long commentId) {
        int rows = commentMapper.softDelete(commentId, creatorId);
        if (rows > 0) {
            // Hard-delete text from Cassandra
            try { textStorageService.deleteCommentText(commentId); } catch (Exception ignored) {}
        }
        return rows > 0;
    }

    @Override
    public boolean likeComment(long userId, long commentId) {
        return counterService.like("comment", String.valueOf(commentId), userId);
    }

    @Override
    public boolean unlikeComment(long userId, long commentId) {
        return counterService.unlike("comment", String.valueOf(commentId), userId);
    }
}
```

- [ ] **Step 4: Run tests — all should pass**

```bash
mvn test -Dtest=CommentServiceImplTest -q
```

Expected: `Tests run: 8, Failures: 0, Errors: 0`

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/tongji/comment/service/impl/CommentServiceImpl.java \
        src/test/java/com/tongji/comment/service/CommentServiceImplTest.java
git commit -m "feat: implement CommentServiceImpl with async Kafka submit and pagination"
```

---

## Task 5: CommentWriteConsumer + @RetryableTopic (TDD)

**Files:**
- Create: `src/main/java/com/tongji/comment/config/CommentKafkaConfig.java`
- Create: `src/test/java/com/tongji/comment/consumer/CommentWriteConsumerTest.java`
- Create: `src/main/java/com/tongji/comment/consumer/CommentWriteConsumer.java`

> **Why a dedicated container factory?** The global Kafka config uses `ack-mode: manual`. `@RetryableTopic` requires `AckMode.RECORD` to work correctly — it needs to commit the offset before routing to a retry topic. If the consumer throws while in `manual` mode without calling `ack.acknowledge()`, the offset is never committed and the message loops forever instead of moving to the retry topic. Providing a dedicated factory with `RECORD` mode fixes this, and removes the need for an `Acknowledgment` parameter entirely.

- [ ] **Step 0: Create CommentKafkaConfig.java**

```java
package com.tongji.comment.config;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;

@Configuration
public class CommentKafkaConfig {

    /**
     * Dedicated factory for comment-write consumer.
     * Uses RECORD ack mode so @RetryableTopic can commit offsets before
     * routing failed messages to retry topics — incompatible with global manual ack-mode.
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> commentListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
            new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        return factory;
    }
}
```

The consumer:
1. Deserializes `CommentWriteEvent` from JSON
2. Writes text to Cassandra via `TextStorageService.saveCommentText()`
3. Inserts comment metadata to MySQL (unique index on `client_request_id` handles idempotency)
4. Updates `pending_comments.status = succeeded`
5. Publishes counter event to increment post comment count (if top-level) or reply count (if reply)

On `DuplicateKeyException`: ack silently (idempotent duplicate).
On other failures: rethrow → `@RetryableTopic` retries 3x with backoff → sends to `comment-write.DLT`.
`@DltHandler` method updates `pending_comments.status = failed`.

- [ ] **Step 1: Write failing tests**

```java
package com.tongji.comment.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.comment.event.CommentWriteEvent;
import com.tongji.comment.mapper.CommentMapper;
import com.tongji.comment.mapper.PendingCommentMapper;
import com.tongji.counter.event.CounterEvent;
import com.tongji.counter.event.CounterEventProducer;
import com.tongji.storage.text.TextStorageService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

// No Acknowledgment needed — RECORD mode auto-acks; consumer method has no Acknowledgment param
@ExtendWith(MockitoExtension.class)
class CommentWriteConsumerTest {

    @Mock TextStorageService textStorageService;
    @Mock CommentMapper commentMapper;
    @Mock PendingCommentMapper pendingCommentMapper;
    @Mock CounterEventProducer counterEventProducer;

    CommentWriteConsumer consumer;
    ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        consumer = new CommentWriteConsumer(
            textStorageService, commentMapper, pendingCommentMapper,
            counterEventProducer, objectMapper);
    }

    private ConsumerRecord<String, String> record(CommentWriteEvent event) throws Exception {
        return new ConsumerRecord<>("comment-write", 0, 0L, "key",
            objectMapper.writeValueAsString(event));
    }

    private CommentWriteEvent topLevelEvent() {
        return CommentWriteEvent.builder()
            .commentId(1L).postId(10L).rootId(0L).parentId(0L)
            .creatorId(5L).clientRequestId("req-1").body("Great post!")
            .build();
    }

    @Test
    void onWrite_writesTextAndMetadata() throws Exception {
        consumer.onWrite(record(topLevelEvent()));

        verify(textStorageService).saveCommentText(1L, "Great post!");
        verify(commentMapper).insert(argThat(c ->
            c.getCommentId() == 1L &&
            c.getPostId() == 10L &&
            "req-1".equals(c.getClientRequestId())));
        verify(pendingCommentMapper).updateStatus(1L, "succeeded");
    }

    @Test
    void onWrite_topLevel_incrementsPostCommentCount() throws Exception {
        consumer.onWrite(record(topLevelEvent()));

        ArgumentCaptor<CounterEvent> captor = ArgumentCaptor.forClass(CounterEvent.class);
        verify(counterEventProducer).publish(captor.capture());
        CounterEvent e = captor.getValue();
        assertThat(e.getEntityType()).isEqualTo("knowpost");
        assertThat(e.getEntityId()).isEqualTo("10");
        assertThat(e.getIdx()).isEqualTo(3);
        assertThat(e.getDelta()).isEqualTo(1);
    }

    @Test
    void onWrite_reply_incrementsRootReplyCount() throws Exception {
        CommentWriteEvent reply = CommentWriteEvent.builder()
            .commentId(2L).postId(10L).rootId(50L).parentId(50L)
            .creatorId(5L).clientRequestId("req-2").body("Reply!")
            .build();

        consumer.onWrite(record(reply));

        ArgumentCaptor<CounterEvent> captor = ArgumentCaptor.forClass(CounterEvent.class);
        verify(counterEventProducer).publish(captor.capture());
        CounterEvent e = captor.getValue();
        assertThat(e.getEntityType()).isEqualTo("comment");
        assertThat(e.getEntityId()).isEqualTo("50");
        assertThat(e.getIdx()).isEqualTo(3);
    }

    @Test
    void onWrite_duplicateKey_doesNotThrow() throws Exception {
        doThrow(new DuplicateKeyException("dup")).when(commentMapper).insert(any());

        consumer.onWrite(record(topLevelEvent()));  // must NOT throw (RECORD mode auto-acks on return)

        verify(pendingCommentMapper).updateStatus(1L, "succeeded");
    }

    @Test
    void onWrite_cassandraFailure_rethrows() throws Exception {
        doThrow(new RuntimeException("Cassandra down"))
            .when(textStorageService).saveCommentText(anyLong(), anyString());

        // @RetryableTopic expects the exception to propagate so it can route to retry topic
        assertThatThrownBy(() -> consumer.onWrite(record(topLevelEvent())))
            .isInstanceOf(RuntimeException.class);
    }

    @Test
    void onDlt_updatesStatusToFailed() throws Exception {
        ConsumerRecord<String, String> dlt = record(topLevelEvent());
        consumer.onDlt(dlt);
        verify(pendingCommentMapper).updateStatus(1L, "failed");
    }
}
```

- [ ] **Step 2: Run tests — confirm they fail**

```bash
mvn test -Dtest=CommentWriteConsumerTest -q 2>&1 | tail -5
```

Expected: compilation error — `CommentWriteConsumer` not found

- [ ] **Step 3: Create CommentWriteConsumer.java**

```java
package com.tongji.comment.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.comment.event.CommentWriteEvent;
import com.tongji.comment.mapper.CommentMapper;
import com.tongji.comment.mapper.PendingCommentMapper;
import com.tongji.comment.model.Comment;
import com.tongji.counter.event.CounterEvent;
import com.tongji.counter.event.CounterEventProducer;
import com.tongji.storage.text.TextStorageService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.retrytopic.TopicSuffixingStrategy;
import org.springframework.retry.annotation.Backoff;
import org.springframework.stereotype.Component;

@Component
public class CommentWriteConsumer {

    private static final Logger log = LoggerFactory.getLogger(CommentWriteConsumer.class);

    private final TextStorageService textStorageService;
    private final CommentMapper commentMapper;
    private final PendingCommentMapper pendingCommentMapper;
    private final CounterEventProducer counterEventProducer;
    private final ObjectMapper objectMapper;

    public CommentWriteConsumer(TextStorageService textStorageService,
                                 CommentMapper commentMapper,
                                 PendingCommentMapper pendingCommentMapper,
                                 CounterEventProducer counterEventProducer,
                                 ObjectMapper objectMapper) {
        this.textStorageService = textStorageService;
        this.commentMapper = commentMapper;
        this.pendingCommentMapper = pendingCommentMapper;
        this.counterEventProducer = counterEventProducer;
        this.objectMapper = objectMapper;
    }

    // containerFactory points to the RECORD-ack factory defined in CommentKafkaConfig.
    // Do NOT add Acknowledgment as a parameter — RECORD mode auto-acks on return.
    @RetryableTopic(
        attempts = "4",   // 1 original + 3 retries
        backoff = @Backoff(delay = 1000L, multiplier = 2.0),
        topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE,
        dltTopicSuffix = ".DLT"
    )
    @KafkaListener(
        topics = "${comment.kafka.write-topic:comment-write}",
        groupId = "comment-write-consumer",
        containerFactory = "commentListenerContainerFactory"
    )
    public void onWrite(ConsumerRecord<String, String> record) {
        CommentWriteEvent event;
        try {
            event = objectMapper.readValue(record.value(), CommentWriteEvent.class);
        } catch (Exception e) {
            log.error("Unparseable comment write event, skipping: {}", record.value());
            return;  // RECORD mode auto-acks on normal return
        }

        try {
            // 1. Write text to Cassandra
            textStorageService.saveCommentText(event.getCommentId(), event.getBody());

            // 2. Write metadata to MySQL (unique index on client_request_id = idempotent)
            commentMapper.insert(Comment.builder()
                .commentId(event.getCommentId())
                .postId(event.getPostId())
                .rootId(event.getRootId())
                .parentId(event.getParentId())
                .creatorId(event.getCreatorId())
                .clientRequestId(event.getClientRequestId())
                .status(0)
                .build());

        } catch (DuplicateKeyException e) {
            // Idempotent: comment already persisted from a previous delivery
            log.info("Duplicate comment write ignored for commentId={}", event.getCommentId());
            pendingCommentMapper.updateStatus(event.getCommentId(), "succeeded");
            return;  // auto-acked
        }
        // 3. Update pending status
        pendingCommentMapper.updateStatus(event.getCommentId(), "succeeded");

        // 4. Increment count: post comment count (top-level) or reply count (reply)
        String entityType = event.getRootId() == 0 ? "knowpost" : "comment";
        String entityId   = event.getRootId() == 0
            ? String.valueOf(event.getPostId())
            : String.valueOf(event.getRootId());

        counterEventProducer.publish(CounterEvent.builder()
            .entityType(entityType)
            .entityId(entityId)
            .metric("comment")
            .idx(3)
            .userId(0L)
            .delta(1)
            .build());
        // RECORD mode auto-acks when method returns normally
    }

    @DltHandler
    public void onDlt(ConsumerRecord<String, String> record) {
        log.error("Comment write DLT: {}", record.value());
        try {
            CommentWriteEvent event = objectMapper.readValue(record.value(), CommentWriteEvent.class);
            pendingCommentMapper.updateStatus(event.getCommentId(), "failed");
        } catch (Exception e) {
            log.error("DLT handler failed to parse event: {}", record.value());
        }
    }
}
```

- [ ] **Step 4: Check if `CounterEvent` has a `@Builder` annotation**

```bash
grep -n "@Builder\|@Data" /Users/huangyaokai/zhiguang_be/src/main/java/com/tongji/counter/event/CounterEvent.java
```

If `CounterEvent` does NOT use Lombok `@Builder`, create the event using setters instead:

```java
// Alternative without @Builder:
CounterEvent ce = new CounterEvent();
ce.setEntityType(entityType);
ce.setEntityId(entityId);
ce.setMetric("comment");
ce.setIdx(3);
ce.setUserId(0L);
ce.setDelta(1);
counterEventProducer.publish(ce);
```

- [ ] **Step 5: Run tests — all should pass**

```bash
mvn test -Dtest=CommentWriteConsumerTest -q
```

Expected: `Tests run: 6, Failures: 0, Errors: 0`

- [ ] **Step 6: Compile full project**

```bash
mvn compile -q
```

Expected: `BUILD SUCCESS`

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/tongji/comment/config/CommentKafkaConfig.java \
        src/main/java/com/tongji/comment/consumer/CommentWriteConsumer.java \
        src/test/java/com/tongji/comment/consumer/CommentWriteConsumerTest.java
git commit -m "feat: add CommentKafkaConfig, CommentWriteConsumer with RetryableTopic and DLT handler"
```

---

## Task 6: CommentController API Endpoints

**Files:**
- Create: `src/main/java/com/tongji/comment/api/CommentController.java`

- [ ] **Step 1: Create CommentController.java**

```java
package com.tongji.comment.api;

import com.tongji.comment.dto.*;
import com.tongji.comment.service.CommentService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.Resource;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1")
public class CommentController {

    @Resource
    private CommentService commentService;

    @Resource
    private com.tongji.auth.service.JwtService jwtService;

    /**
     * POST /api/v1/posts/{postId}/comments
     * Submit a comment or reply. Returns 202 with pendingCommentId.
     */
    @PostMapping("/posts/{postId}/comments")
    public ResponseEntity<CommentSubmitResponse> submit(
            @PathVariable long postId,
            @RequestBody CommentSubmitRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        long userId = jwtService.extractUserId(jwt);
        request.setPostId(postId);
        CommentSubmitResponse resp = commentService.submit(userId, request);
        return ResponseEntity.accepted().body(resp);
    }

    /**
     * GET /api/v1/comments/{pendingCommentId}/status
     */
    @GetMapping("/comments/{pendingCommentId}/status")
    public ResponseEntity<CommentStatusResponse> getStatus(
            @PathVariable long pendingCommentId,
            @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(commentService.getStatus(pendingCommentId));
    }

    /**
     * GET /api/v1/posts/{postId}/comments?cursor=&limit=
     */
    @GetMapping("/posts/{postId}/comments")
    public ResponseEntity<CommentPageResponse> getTopLevel(
            @PathVariable long postId,
            @RequestParam(required = false) Long cursor,
            @RequestParam(defaultValue = "20") int limit,
            @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(commentService.getTopLevel(postId, cursor, limit));
    }

    /**
     * GET /api/v1/comments/{commentId}/replies?cursor=&limit=
     */
    @GetMapping("/comments/{commentId}/replies")
    public ResponseEntity<CommentPageResponse> getReplies(
            @PathVariable long commentId,
            @RequestParam(required = false) Long cursor,
            @RequestParam(defaultValue = "20") int limit,
            @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(commentService.getReplies(commentId, cursor, limit));
    }

    /**
     * DELETE /api/v1/comments/{commentId}
     */
    @DeleteMapping("/comments/{commentId}")
    public ResponseEntity<Void> deleteComment(
            @PathVariable long commentId,
            @AuthenticationPrincipal Jwt jwt) {
        long userId = jwtService.extractUserId(jwt);
        boolean deleted = commentService.deleteComment(userId, commentId);
        return deleted ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    /**
     * POST /api/v1/comments/{commentId}/like
     */
    @PostMapping("/comments/{commentId}/like")
    public ResponseEntity<Void> likeComment(
            @PathVariable long commentId,
            @AuthenticationPrincipal Jwt jwt) {
        long userId = jwtService.extractUserId(jwt);
        commentService.likeComment(userId, commentId);
        return ResponseEntity.ok().build();
    }

    /**
     * DELETE /api/v1/comments/{commentId}/like
     */
    @DeleteMapping("/comments/{commentId}/like")
    public ResponseEntity<Void> unlikeComment(
            @PathVariable long commentId,
            @AuthenticationPrincipal Jwt jwt) {
        long userId = jwtService.extractUserId(jwt);
        commentService.unlikeComment(userId, commentId);
        return ResponseEntity.ok().build();
    }
}
```

> **Note:** The `JwtService` class path (`com.tongji.auth.service.JwtService`) is based on the existing codebase pattern. Verify with:
> ```bash
> grep -r "class JwtService" src/main/java --include="*.java"
> ```
> Adjust the import if the class is in a different package.

- [ ] **Step 2: Verify compilation**

```bash
mvn compile -q
```

Expected: `BUILD SUCCESS`

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/tongji/comment/api/CommentController.java
git commit -m "feat: add CommentController with submit/status/pagination/delete/like endpoints"
```

---

## Task 7: Recommendation Event Delivery

**Files:**
- Create: `src/main/java/com/tongji/comment/event/CommentFeedbackProducer.java`
- Modify: `src/main/java/com/tongji/comment/service/impl/CommentServiceImpl.java`

The recommendation system (`add-recommendation-and-follow-feed`) will consume `comment-feedback` events. We publish them here; the consumer is in the other change.

- [ ] **Step 1: Create CommentFeedbackProducer.java**

```java
package com.tongji.comment.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;

@Component
public class CommentFeedbackProducer {

    private static final Logger log = LoggerFactory.getLogger(CommentFeedbackProducer.class);

    @Resource private KafkaTemplate<String, String> kafkaTemplate;
    @Resource private ObjectMapper objectMapper;

    @Value("${comment.kafka.feedback-topic:comment-feedback}")
    private String topic;

    public void send(CommentFeedbackEvent event) {
        try {
            kafkaTemplate.send(topic, String.valueOf(event.getUserId()),
                objectMapper.writeValueAsString(event));
        } catch (Exception e) {
            log.warn("Failed to send comment feedback event: {}", e.getMessage());
        }
    }
}
```

- [ ] **Step 2: Inject producer into CommentServiceImpl and fire events**

Add field:
```java
@Resource
private CommentFeedbackProducer feedbackProducer;
```

In `submit()`, after Kafka write success, add fire-and-forget:
```java
try {
    feedbackProducer.send(CommentFeedbackEvent.builder()
        .action("comment").commentId(commentId)
        .postId(request.getPostId()).userId(creatorId)
        .build());
} catch (Exception ignored) {}
```

In `deleteComment()`, after successful soft-delete:
```java
if (rows > 0) {
    try {
        feedbackProducer.send(CommentFeedbackEvent.builder()
            .action("delete").commentId(commentId)
            .postId(0L).userId(creatorId)
            .build());
    } catch (Exception ignored) {}
}
```

In `likeComment()` / `unlikeComment()`, after counter call:
```java
try {
    feedbackProducer.send(CommentFeedbackEvent.builder()
        .action("like").commentId(commentId).userId(userId).postId(0L)
        .build());
} catch (Exception ignored) {}
```

- [ ] **Step 3: Compile**

```bash
mvn compile -q
```

Expected: `BUILD SUCCESS`

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/tongji/comment/event/CommentFeedbackProducer.java \
        src/main/java/com/tongji/comment/service/impl/CommentServiceImpl.java
git commit -m "feat: add comment feedback events for recommendation system"
```

---

## Task 8: Verification Tests

**Files:**
- Create: `src/test/java/com/tongji/comment/CommentVerificationTest.java`

- [ ] **Step 1: Write verification tests covering all tasks.md requirements**

```java
package com.tongji.comment;

import com.tongji.comment.consumer.CommentWriteConsumer;
import com.tongji.comment.dto.CommentPageResponse;
import com.tongji.comment.dto.CommentSubmitRequest;
import com.tongji.comment.dto.CommentSubmitResponse;
import com.tongji.comment.event.CommentWriteEvent;
import com.tongji.comment.mapper.CommentMapper;
import com.tongji.comment.mapper.PendingCommentMapper;
import com.tongji.comment.model.Comment;
import com.tongji.comment.model.PendingComment;
import com.tongji.comment.service.impl.CommentServiceImpl;
import com.tongji.counter.event.CounterEventProducer;
import com.tongji.counter.service.CounterService;
import com.tongji.id.IdNamespace;
import com.tongji.id.IdService;
import com.tongji.storage.text.TextStorageService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.kafka.core.KafkaTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CommentVerificationTest {

    @Mock IdService idService;
    @Mock CommentMapper commentMapper;
    @Mock PendingCommentMapper pendingCommentMapper;
    @Mock KafkaTemplate<String, String> kafkaTemplate;
    @Mock CounterService counterService;
    @Mock TextStorageService textStorageService;
    @Mock CounterEventProducer counterEventProducer;
    @Mock com.tongji.comment.event.CommentFeedbackProducer feedbackProducer;

    @InjectMocks CommentServiceImpl service;
    CommentWriteConsumer consumer;
    ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        // Fix @Value not injected by @InjectMocks
        org.springframework.test.util.ReflectionTestUtils.setField(service, "writeTopic", "comment-write");
        consumer = new CommentWriteConsumer(
            textStorageService, commentMapper, pendingCommentMapper,
            counterEventProducer, objectMapper);
    }

    /**
     * tasks.md 5.1: Submit idempotency — same clientRequestId twice must not throw.
     */
    @Test
    void submit_idempotency_duplicateClientRequestId() throws Exception {
        when(idService.nextId(IdNamespace.COMMENT)).thenReturn(1L).thenReturn(2L);

        // First submission
        CommentSubmitRequest req = new CommentSubmitRequest();
        req.setPostId(10L); req.setClientRequestId("dup-req"); req.setBody("hello");
        service.submit(5L, req);

        // Simulate consumer receiving it twice — second time hits duplicate key
        CommentWriteEvent event = CommentWriteEvent.builder()
            .commentId(1L).postId(10L).rootId(0L).parentId(0L)
            .creatorId(5L).clientRequestId("dup-req").body("hello").build();
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
            "comment-write", 0, 0L, "1", objectMapper.writeValueAsString(event));

        doThrow(new DuplicateKeyException("dup")).when(commentMapper).insert(any());
        consumer.onWrite(record);   // must NOT throw (RECORD mode auto-acks on return)

        verify(pendingCommentMapper, atLeastOnce()).updateStatus(1L, "succeeded");
    }

    /**
     * tasks.md 5.2: Async consumer batch write test (Cassandra + MySQL).
     */
    @Test
    void consumer_writesBothCassandraAndMySQL() throws Exception {
        CommentWriteEvent event = CommentWriteEvent.builder()
            .commentId(10L).postId(100L).rootId(0L).parentId(0L)
            .creatorId(7L).clientRequestId("c-10").body("body text").build();
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
            "comment-write", 0, 0L, "10", objectMapper.writeValueAsString(event));

        consumer.onWrite(record);

        verify(textStorageService).saveCommentText(10L, "body text");
        verify(commentMapper).insert(argThat(c -> c.getCommentId() == 10L));
        verify(pendingCommentMapper).updateStatus(10L, "succeeded");
    }

    /**
     * tasks.md 5.3: Pagination returns deleted placeholder, replies visible.
     */
    @Test
    void getTopLevel_deletedCommentShowsPlaceholder() {
        Comment deleted = Comment.builder()
            .commentId(1L).postId(5L).rootId(0L).parentId(0L)
            .creatorId(1L).clientRequestId("c1").status(1)  // deleted
            .likeCount(0).replyCount(2).createTime(LocalDateTime.now()).build();
        Comment normal = Comment.builder()
            .commentId(2L).postId(5L).rootId(0L).parentId(0L)
            .creatorId(2L).clientRequestId("c2").status(0)  // normal
            .likeCount(1).replyCount(0).createTime(LocalDateTime.now()).build();

        when(commentMapper.findTopLevel(5L, null, 21))
            .thenReturn(List.of(deleted, normal));
        when(textStorageService.getCommentTexts(List.of(2L)))
            .thenReturn(Map.of(2L, "Normal body"));

        CommentPageResponse resp = service.getTopLevel(5L, null, 20);

        assertThat(resp.getItems()).hasSize(2);
        assertThat(resp.getItems().get(0).isDeleted()).isTrue();
        assertThat(resp.getItems().get(0).getBody()).isEqualTo("该评论已删除");
        assertThat(resp.getItems().get(1).isDeleted()).isFalse();
        assertThat(resp.getItems().get(1).getBody()).isEqualTo("Normal body");
        // Deleted comment still visible (thread structure preserved)
        assertThat(resp.getItems().get(0).getReplyCount()).isEqualTo(2);
    }

    /**
     * tasks.md 5.4: Comment like uses CounterService; count rebuild uses idx=3.
     */
    @Test
    void likeComment_usesCounterService() {
        when(counterService.like("comment", "300", 10L)).thenReturn(true);
        assertThat(service.likeComment(10L, 300L)).isTrue();
        verify(counterService).like("comment", "300", 10L);
    }

    @Test
    void consumer_topLevel_publishesCounterEventWithIdx3() throws Exception {
        CommentWriteEvent event = CommentWriteEvent.builder()
            .commentId(5L).postId(20L).rootId(0L).parentId(0L)
            .creatorId(1L).clientRequestId("c5").body("hi").build();
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
            "comment-write", 0, 0L, "5", objectMapper.writeValueAsString(event));

        consumer.onWrite(record);

        verify(counterEventProducer).publish(argThat(e ->
            "knowpost".equals(e.getEntityType()) &&
            "20".equals(e.getEntityId()) &&
            e.getIdx() == 3 &&
            e.getDelta() == 1));
    }
}
```

- [ ] **Step 2: Run all verification tests**

```bash
mvn test -Dtest="CommentServiceImplTest,CommentWriteConsumerTest,CommentVerificationTest" -q
```

Expected: `Tests run: 19, Failures: 0, Errors: 0`

- [ ] **Step 3: Run full test suite**

```bash
mvn test -q
```

Expected: `BUILD SUCCESS` — no regressions

- [ ] **Step 4: Commit**

```bash
git add src/test/java/com/tongji/comment/CommentVerificationTest.java
git commit -m "test: add comment system verification tests covering all spec requirements"
```

---

## Self-Review Checklist

**tasks.md coverage:**
- [x] 1.1 `comments` table with all fields + indices (Task 1)
- [x] 1.2 Indices for top-level pagination (`idx_post_comments`), replies (`idx_root_replies`), author (`idx_creator`) (Task 1)
- [x] 1.3 `pending_comments` table for status tracking (Task 1)
- [x] 2.1 POST comment → 202 + `clientRequestId` + `pendingCommentId` (Task 6)
- [x] 2.2 Status query endpoint (Task 6)
- [x] 2.3 Top-level comment cursor pagination (Task 6)
- [x] 2.4 Reply cursor pagination (Task 6)
- [x] 2.5 Soft delete endpoint (Task 6)
- [x] 3.1 `CommentWriteEvent` Kafka event model (Task 3)
- [x] 3.2 `clientRequestId` idempotency via MySQL unique index (Task 2 schema + Task 5 consumer)
- [x] 3.3 Consumer writes Cassandra text + MySQL metadata (Task 5)
- [x] 3.4 Failure retry (`@RetryableTopic` 3 retries) + DLT + status `failed` callback (Task 5)
- [x] 4.1 Comment likes via `CounterService.like("comment", ...)` (Task 4)
- [x] 4.2 Post comment count (`idx=3` for `knowpost`) + reply count (`idx=3` for `comment`) (Task 5)
- [x] 4.3 Recommendation events via `CommentFeedbackProducer` to `comment-feedback` topic (Task 7)
- [x] 5.1 Idempotency test (`submit_idempotency_duplicateClientRequestId`) (Task 8)
- [x] 5.2 Consumer batch write test (`consumer_writesBothCassandraAndMySQL`) (Task 8)
- [x] 5.3 Pagination + soft delete placeholder test (Task 8)
- [x] 5.4 Like and count idx=3 test (Task 8)

**Bugs fixed during review:**
- **Critical:** `@RetryableTopic` + global `ack-mode: manual` conflict — added `CommentKafkaConfig` with dedicated `RECORD`-mode factory; removed `Acknowledgment` parameter from consumer
- `@Value writeTopic` not injected by `@InjectMocks` — added `ReflectionTestUtils.setField` in `@BeforeEach` of both test classes
- File Map missing `CommentFeedbackProducer.java` and `CommentKafkaConfig.java` — added
- Tests missing `@Mock CommentFeedbackProducer` after Task 7 modifies service — added to both test classes
- Removed unused `import Acknowledgment` from `CommentWriteConsumerTest`, `CommentVerificationTest`, and `CommentWriteConsumer.java`
- Added missing `import org.junit.jupiter.api.BeforeEach` to `CommentServiceImplTest`
- Removed unused imports (`LocalDateTime`, `List`, `Map`, `Optional`) from `CommentServiceImplTest`

**No placeholders.**

**Type consistency:**
- `CommentWriteEvent` fields (`commentId, postId, rootId, parentId, creatorId, clientRequestId, body`) used in Tasks 4+5+8 match Task 3 definition
- `CounterEvent` usage (`entityType, entityId, metric, idx, delta`) in Task 5 consumer matches existing `CounterEventProducer.publish(CounterEvent)` API
- `TextStorageService.saveCommentText(long, String)` and `getCommentTexts(Collection<Long>)` in Tasks 4+5 match `add-cassandra-text-storage` plan definitions
- `IdNamespace.COMMENT` in Task 4 matches `add-leaf-id-service` plan definitions
- `CounterService.like("comment", String, long)` signature matches existing interface
