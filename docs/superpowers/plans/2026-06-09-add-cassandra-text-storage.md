# Add Cassandra Text Storage Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add Cassandra as the fact store for post and comment text while keeping MySQL as metadata storage and MinIO as media storage.

**Architecture:** Add a focused `com.tongji.textstorage` module with a small business-facing `TextStorageService`, a Cassandra DAO, schema CQL, and failure types. Existing post metadata remains in MySQL; `content_object_key` stores the Cassandra text key, `content_sha256` stores the Cassandra text hash, and search/RAG rebuild derived indexes by reading text from Cassandra.

**Tech Stack:** Java 21, Spring Boot 3.2, Spring Data Cassandra, Cassandra CQL, Docker Compose, JUnit 5, Mockito, MyBatis/MySQL metadata.

---

## Source Context

- OpenSpec change: `openspec/changes/add-cassandra-text-storage`
- Spec: `openspec/changes/add-cassandra-text-storage/specs/text-storage/spec.md`
- Tasks: `openspec/changes/add-cassandra-text-storage/tasks.md`
- Existing post content confirm API: `src/main/java/com/tongji/knowpost/api/KnowPostController.java`
- Existing post service: `src/main/java/com/tongji/knowpost/service/impl/KnowPostServiceImpl.java`
- Existing post metadata mapper: `src/main/resources/mapper/KnowPostMapper.xml`
- Existing search index source: `src/main/java/com/tongji/search/index/SearchIndexService.java`
- Existing RAG index source: `src/main/java/com/tongji/llm/rag/RagIndexService.java`
- Local service docs: `docs/docker-local-env.md`

## Decisions

- Cassandra is only used for ID-based text lookup in this change.
- Post text is saved through the existing content confirm flow, but the request carries inline text instead of a MinIO text object.
- Existing MinIO media flow remains for images/video/attachments; do not use Cassandra for binary media.
- MySQL `know_posts.content_object_key` stores the Cassandra text key such as `post:123:v1`.
- MySQL `know_posts.content_url` is no longer required for text and should be set to `NULL` for Cassandra-backed text.
- MySQL `know_posts.content_sha256` stores the hash computed by the backend from the stored text.
- Comment business APIs do not exist yet; implement comment text storage methods and Cassandra table only.
- Do not add feed/search/list queries to Cassandra.

## File Structure

Create:

- `cassandra/schema.cql` - local keyspace/table initialization.
- `src/main/java/com/tongji/textstorage/TextStorageService.java` - public text storage API.
- `src/main/java/com/tongji/textstorage/TextContent.java` - immutable service result.
- `src/main/java/com/tongji/textstorage/TextType.java` - `POST` and `COMMENT`.
- `src/main/java/com/tongji/textstorage/TextStorageException.java`
- `src/main/java/com/tongji/textstorage/TextStorageReadException.java`
- `src/main/java/com/tongji/textstorage/TextStorageWriteException.java`
- `src/main/java/com/tongji/textstorage/TextContentNotFoundException.java`
- `src/main/java/com/tongji/textstorage/TextStorageKey.java` - deterministic key/hash helpers.
- `src/main/java/com/tongji/textstorage/CassandraTextStorageService.java`
- `src/main/java/com/tongji/textstorage/TextStorageDao.java` - package-private storage DAO contract for tests and Cassandra implementation.
- `src/main/java/com/tongji/textstorage/CassandraTextStorageDao.java`
- `src/main/java/com/tongji/textstorage/PostTextRow.java`
- `src/main/java/com/tongji/textstorage/CommentTextRow.java`
- `src/test/java/com/tongji/textstorage/TextStorageKeyTest.java`
- `src/test/java/com/tongji/textstorage/CassandraTextStorageServiceTest.java`
- `src/test/java/com/tongji/textstorage/CassandraTextStorageSmokeTest.java`

Modify:

- `pom.xml` - add Spring Data Cassandra dependency.
- `docker-compose.yml` - add Cassandra and Cassandra init service/volume.
- `src/main/resources/application.yml` - add Cassandra connection properties.
- `src/main/java/com/tongji/knowpost/api/dto/KnowPostContentConfirmRequest.java` - accept text content.
- `src/main/java/com/tongji/knowpost/api/dto/KnowPostDetailResponse.java` - expose Cassandra-backed text body.
- `src/main/java/com/tongji/knowpost/api/KnowPostController.java` - pass text content to service.
- `src/main/java/com/tongji/knowpost/service/KnowPostService.java` - update `confirmContent` signature.
- `src/main/java/com/tongji/knowpost/service/impl/KnowPostServiceImpl.java` - write text to Cassandra, metadata to MySQL.
- `src/main/java/com/tongji/knowpost/model/KnowPostDetailRow.java` - expose `contentObjectKey`.
- `src/main/resources/mapper/KnowPostMapper.xml` - map/select `content_object_key`.
- `src/main/java/com/tongji/search/index/SearchIndexService.java` - read body from `TextStorageService`.
- `src/main/java/com/tongji/llm/rag/RagIndexService.java` - read body from `TextStorageService`.
- `docs/docker-local-env.md` - document Cassandra local startup and smoke check.
- `openspec/changes/add-cassandra-text-storage/tasks.md` - mark tasks complete only after verification.

Do not create a comment Controller/Service in this change.

---

## Chunk 1: Cassandra Environment and Schema

### Task 1: Add Cassandra Dependency and Configuration

**Files:**

- Modify: `pom.xml`
- Modify: `src/main/resources/application.yml`
- Test: compile check

- [ ] **Step 1: Add Cassandra dependency**

Add this dependency near other Spring data dependencies:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-cassandra</artifactId>
</dependency>
```

- [ ] **Step 2: Add application Cassandra properties**

Under `spring`, add the Spring Boot Cassandra block:

```yaml
  cassandra:
    contact-points: ${CASSANDRA_CONTACT_POINTS:localhost}
    port: ${CASSANDRA_PORT:9042}
    keyspace-name: ${CASSANDRA_KEYSPACE:zhiguang_text}
    local-datacenter: ${CASSANDRA_LOCAL_DATACENTER:datacenter1}
    request:
      timeout: ${CASSANDRA_REQUEST_TIMEOUT:5s}
    schema-action: none
```

- [ ] **Step 3: Run compile to verify dependency wiring**

Run:

```powershell
$mvn = "$env:USERPROFILE\Desktop\$([char]0x6587)$([char]0x6863)\.codex\tools\apache-maven-3.9.6\bin\mvn.cmd"; & $mvn -DskipTests compile
```

Expected: PASS.

### Task 2: Add Docker Compose Cassandra and CQL Schema

**Files:**

- Modify: `docker-compose.yml`
- Create: `cassandra/schema.cql`

- [ ] **Step 1: Add Cassandra schema file**

Create `cassandra/schema.cql`:

```sql
CREATE KEYSPACE IF NOT EXISTS zhiguang_text
WITH replication = {'class': 'SimpleStrategy', 'replication_factor': 1};

CREATE TABLE IF NOT EXISTS zhiguang_text.post_text_by_post_id (
    post_id bigint PRIMARY KEY,
    text_key text,
    body text,
    version int,
    content_sha256 text,
    updated_at timestamp,
    deleted boolean
);

CREATE TABLE IF NOT EXISTS zhiguang_text.comment_text_by_comment_id (
    comment_id bigint PRIMARY KEY,
    text_key text,
    body text,
    version int,
    content_sha256 text,
    updated_at timestamp,
    deleted boolean
);

CREATE TABLE IF NOT EXISTS zhiguang_text.text_write_log_by_day (
    day date,
    written_at timestamp,
    text_type text,
    entity_id bigint,
    text_key text,
    content_sha256 text,
    op text,
    PRIMARY KEY ((day), written_at, text_type, entity_id)
) WITH CLUSTERING ORDER BY (written_at DESC);
```

- [ ] **Step 2: Add Cassandra services to compose**

Add services:

```yaml
  cassandra:
    image: cassandra:4.1
    container_name: zhiguang-cassandra
    restart: unless-stopped
    environment:
      CASSANDRA_CLUSTER_NAME: zhiguang-local
      CASSANDRA_DC: datacenter1
      CASSANDRA_RACK: rack1
      CASSANDRA_ENDPOINT_SNITCH: GossipingPropertyFileSnitch
      MAX_HEAP_SIZE: 512M
      HEAP_NEWSIZE: 128M
      TZ: Asia/Shanghai
    ports:
      - "9042:9042"
    volumes:
      - cassandra-data:/var/lib/cassandra
    healthcheck:
      test: ["CMD-SHELL", "cqlsh -e 'DESCRIBE KEYSPACES' 127.0.0.1 9042 >/dev/null 2>&1"]
      interval: 15s
      timeout: 10s
      retries: 30

  cassandra-init:
    image: cassandra:4.1
    container_name: zhiguang-cassandra-init
    depends_on:
      cassandra:
        condition: service_healthy
    volumes:
      - ./cassandra/schema.cql:/schema.cql:ro
    entrypoint: ["bash", "-lc", "cqlsh cassandra 9042 -f /schema.cql"]
```

Add volume:

```yaml
  cassandra-data:
```

- [ ] **Step 3: Verify schema text exists**

Run:

```powershell
rg -n "post_text_by_post_id|comment_text_by_comment_id|text_write_log_by_day|zhiguang-cassandra" docker-compose.yml cassandra/schema.cql
```

Expected: all table and service names are present.

- [ ] **Step 4: Commit**

```powershell
git add pom.xml src/main/resources/application.yml docker-compose.yml cassandra/schema.cql
git commit -m "feat: add cassandra text storage environment"
```

---

## Chunk 2: Text Storage Service

### Task 3: Add Key, Hash, and API Model

**Files:**

- Create: `src/main/java/com/tongji/textstorage/TextType.java`
- Create: `src/main/java/com/tongji/textstorage/TextContent.java`
- Create: `src/main/java/com/tongji/textstorage/TextStorageKey.java`
- Create: `src/main/java/com/tongji/textstorage/TextStorageService.java`
- Test: `src/test/java/com/tongji/textstorage/TextStorageKeyTest.java`

- [ ] **Step 1: Write failing key/hash tests**

Create `TextStorageKeyTest`:

```java
package com.tongji.textstorage;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TextStorageKeyTest {
    @Test
    void buildsPostAndCommentKeys() {
        assertThat(TextStorageKey.post(123L, 2)).isEqualTo("post:123:v2");
        assertThat(TextStorageKey.comment(456L, 1)).isEqualTo("comment:456:v1");
    }

    @Test
    void computesStableSha256Hex() {
        assertThat(TextStorageKey.sha256Hex("hello"))
                .isEqualTo("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824");
    }
}
```

- [ ] **Step 2: Run test and verify RED**

Run:

```powershell
$mvn = "$env:USERPROFILE\Desktop\$([char]0x6587)$([char]0x6863)\.codex\tools\apache-maven-3.9.6\bin\mvn.cmd"; & $mvn -Dtest=com.tongji.textstorage.TextStorageKeyTest test
```

Expected: compile failure because text storage classes do not exist.

- [ ] **Step 3: Implement API model**

Create `TextType.java`:

```java
package com.tongji.textstorage;

public enum TextType {
    POST,
    COMMENT
}
```

Create `TextContent.java`:

```java
package com.tongji.textstorage;

import java.time.Instant;

public record TextContent(
        TextType type,
        long id,
        String textKey,
        String body,
        int version,
        String contentSha256,
        Instant updatedAt,
        boolean deleted
) {
}
```

Create `TextStorageService.java`:

```java
package com.tongji.textstorage;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;

public interface TextStorageService {
    TextContent savePostText(long postId, String body);

    TextContent saveCommentText(long commentId, String body);

    Optional<TextContent> findPostText(long postId);

    Optional<TextContent> findCommentText(long commentId);

    Map<Long, TextContent> findCommentTexts(Collection<Long> commentIds);

    void softDeletePostText(long postId);

    void softDeleteCommentText(long commentId);
}
```

Create `TextStorageKey.java`:

```java
package com.tongji.textstorage;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class TextStorageKey {
    private TextStorageKey() {
    }

    public static String post(long postId, int version) {
        return "post:" + postId + ":v" + version;
    }

    public static String comment(long commentId, int version) {
        return "comment:" + commentId + ":v" + version;
    }

    public static String sha256Hex(String body) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(body.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
```

- [ ] **Step 4: Run key tests**

Run:

```powershell
$mvn = "$env:USERPROFILE\Desktop\$([char]0x6587)$([char]0x6863)\.codex\tools\apache-maven-3.9.6\bin\mvn.cmd"; & $mvn -Dtest=com.tongji.textstorage.TextStorageKeyTest test
```

Expected: PASS.

### Task 4: Add Exceptions, Cassandra Rows, DAO, and Service

**Files:**

- Create exception classes under `src/main/java/com/tongji/textstorage`
- Create: `src/main/java/com/tongji/textstorage/PostTextRow.java`
- Create: `src/main/java/com/tongji/textstorage/CommentTextRow.java`
- Create: `src/main/java/com/tongji/textstorage/CassandraTextStorageDao.java`
- Create: `src/main/java/com/tongji/textstorage/CassandraTextStorageService.java`
- Test: `src/test/java/com/tongji/textstorage/CassandraTextStorageServiceTest.java`

- [ ] **Step 1: Write failing service tests**

Create `CassandraTextStorageServiceTest` using a fake DAO:

```java
package com.tongji.textstorage;

import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CassandraTextStorageServiceTest {
    @Test
    void savesPostTextWithIncrementingVersionHashAndKey() {
        FakeDao dao = new FakeDao();
        CassandraTextStorageService service = new CassandraTextStorageService(dao);

        TextContent first = service.savePostText(10L, "hello");
        TextContent second = service.savePostText(10L, "hello again");

        assertThat(first.version()).isEqualTo(1);
        assertThat(first.textKey()).isEqualTo("post:10:v1");
        assertThat(first.contentSha256()).isEqualTo(TextStorageKey.sha256Hex("hello"));
        assertThat(second.version()).isEqualTo(2);
        assertThat(second.textKey()).isEqualTo("post:10:v2");
    }

    @Test
    void batchReadsCommentTextByIds() {
        FakeDao dao = new FakeDao();
        CassandraTextStorageService service = new CassandraTextStorageService(dao);
        service.saveCommentText(1L, "one");
        service.saveCommentText(2L, "two");

        Map<Long, TextContent> result = service.findCommentTexts(java.util.List.of(2L, 1L, 3L));

        assertThat(result.keySet()).containsExactly(2L, 1L);
        assertThat(result.get(2L).body()).isEqualTo("two");
    }

    @Test
    void rejectsBlankBody() {
        CassandraTextStorageService service = new CassandraTextStorageService(new FakeDao());

        assertThatThrownBy(() -> service.savePostText(10L, " "))
                .isInstanceOf(TextStorageWriteException.class);
    }

    private static final class FakeDao implements TextStorageDao {
        private final Map<Long, PostTextRow> posts = new LinkedHashMap<>();
        private final Map<Long, CommentTextRow> comments = new LinkedHashMap<>();

        @Override
        public Optional<PostTextRow> findPost(long postId) {
            return Optional.ofNullable(posts.get(postId));
        }

        @Override
        public void savePost(PostTextRow row) {
            posts.put(row.postId(), row);
        }

        @Override
        public Optional<CommentTextRow> findComment(long commentId) {
            return Optional.ofNullable(comments.get(commentId));
        }

        @Override
        public Map<Long, CommentTextRow> findComments(Collection<Long> commentIds) {
            Map<Long, CommentTextRow> result = new LinkedHashMap<>();
            for (Long id : commentIds) {
                CommentTextRow row = comments.get(id);
                if (row != null) {
                    result.put(id, row);
                }
            }
            return result;
        }

        @Override
        public void saveComment(CommentTextRow row) {
            comments.put(row.commentId(), row);
        }
    }
}
```

- [ ] **Step 2: Run service test and verify RED**

Run:

```powershell
$mvn = "$env:USERPROFILE\Desktop\$([char]0x6587)$([char]0x6863)\.codex\tools\apache-maven-3.9.6\bin\mvn.cmd"; & $mvn -Dtest=com.tongji.textstorage.CassandraTextStorageServiceTest test
```

Expected: compile failure for missing service/DAO/row/exception types.

- [ ] **Step 3: Add exception hierarchy**

Create:

```java
package com.tongji.textstorage;

public class TextStorageException extends RuntimeException {
    public TextStorageException(String message) {
        super(message);
    }

    public TextStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

Create read/write/not-found subclasses:

```java
package com.tongji.textstorage;

public class TextStorageWriteException extends TextStorageException {
    public TextStorageWriteException(String message) {
        super(message);
    }

    public TextStorageWriteException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

```java
package com.tongji.textstorage;

public class TextStorageReadException extends TextStorageException {
    public TextStorageReadException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

```java
package com.tongji.textstorage;

public class TextContentNotFoundException extends TextStorageException {
    public TextContentNotFoundException(String message) {
        super(message);
    }
}
```

- [ ] **Step 4: Add row records and DAO contract**

Create package-private `TextStorageDao.java`:

```java
package com.tongji.textstorage;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;

interface TextStorageDao {
    Optional<PostTextRow> findPost(long postId);

    void savePost(PostTextRow row);

    Optional<CommentTextRow> findComment(long commentId);

    Map<Long, CommentTextRow> findComments(Collection<Long> commentIds);

    void saveComment(CommentTextRow row);
}
```

Create row records:

```java
package com.tongji.textstorage;

import java.time.Instant;

record PostTextRow(long postId, String textKey, String body, int version,
                   String contentSha256, Instant updatedAt, boolean deleted) {
}
```

```java
package com.tongji.textstorage;

import java.time.Instant;

record CommentTextRow(long commentId, String textKey, String body, int version,
                      String contentSha256, Instant updatedAt, boolean deleted) {
}
```

- [ ] **Step 5: Implement Cassandra DAO**

Create `CassandraTextStorageDao.java` with `CqlSession` prepared CQL. Keep CQL ID-based only:

```java
package com.tongji.textstorage;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.Row;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@Repository
class CassandraTextStorageDao implements TextStorageDao {
    private final CqlSession session;

    CassandraTextStorageDao(CqlSession session) {
        this.session = session;
    }

    @Override
    public Optional<PostTextRow> findPost(long postId) {
        Row row = session.execute("SELECT post_id,text_key,body,version,content_sha256,updated_at,deleted FROM post_text_by_post_id WHERE post_id = ?", postId)
                .one();
        return Optional.ofNullable(row).map(this::toPost);
    }

    @Override
    public void savePost(PostTextRow row) {
        session.execute("""
                INSERT INTO post_text_by_post_id (post_id,text_key,body,version,content_sha256,updated_at,deleted)
                VALUES (?,?,?,?,?,?,?)
                """, row.postId(), row.textKey(), row.body(), row.version(), row.contentSha256(), row.updatedAt(), row.deleted());
    }

    @Override
    public Optional<CommentTextRow> findComment(long commentId) {
        Row row = session.execute("SELECT comment_id,text_key,body,version,content_sha256,updated_at,deleted FROM comment_text_by_comment_id WHERE comment_id = ?", commentId)
                .one();
        return Optional.ofNullable(row).map(this::toComment);
    }

    @Override
    public Map<Long, CommentTextRow> findComments(Collection<Long> commentIds) {
        Map<Long, CommentTextRow> result = new LinkedHashMap<>();
        for (Long id : commentIds) {
            if (id != null) {
                findComment(id).ifPresent(row -> result.put(id, row));
            }
        }
        return result;
    }

    @Override
    public void saveComment(CommentTextRow row) {
        session.execute("""
                INSERT INTO comment_text_by_comment_id (comment_id,text_key,body,version,content_sha256,updated_at,deleted)
                VALUES (?,?,?,?,?,?,?)
                """, row.commentId(), row.textKey(), row.body(), row.version(), row.contentSha256(), row.updatedAt(), row.deleted());
    }

    private PostTextRow toPost(Row row) {
        return new PostTextRow(row.getLong("post_id"), row.getString("text_key"), row.getString("body"),
                row.getInt("version"), row.getString("content_sha256"), row.getInstant("updated_at"),
                row.getBoolean("deleted"));
    }

    private CommentTextRow toComment(Row row) {
        return new CommentTextRow(row.getLong("comment_id"), row.getString("text_key"), row.getString("body"),
                row.getInt("version"), row.getString("content_sha256"), row.getInstant("updated_at"),
                row.getBoolean("deleted"));
    }
}
```

If the implementer prefers prepared statements, keep behavior identical and do not expand scope.

- [ ] **Step 6: Implement service**

Create `CassandraTextStorageService.java`:

```java
package com.tongji.textstorage;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@Service
public class CassandraTextStorageService implements TextStorageService {
    private final TextStorageDao dao;

    CassandraTextStorageService(TextStorageDao dao) {
        this.dao = dao;
    }

    @Override
    public TextContent savePostText(long postId, String body) {
        String clean = requireBody(body);
        try {
            int version = dao.findPost(postId).map(PostTextRow::version).orElse(0) + 1;
            PostTextRow row = new PostTextRow(postId, TextStorageKey.post(postId, version), clean, version,
                    TextStorageKey.sha256Hex(clean), Instant.now(), false);
            dao.savePost(row);
            return toContent(row);
        } catch (TextStorageException e) {
            throw e;
        } catch (Exception e) {
            throw new TextStorageWriteException("failed to save post text: " + postId, e);
        }
    }

    @Override
    public TextContent saveCommentText(long commentId, String body) {
        String clean = requireBody(body);
        try {
            int version = dao.findComment(commentId).map(CommentTextRow::version).orElse(0) + 1;
            CommentTextRow row = new CommentTextRow(commentId, TextStorageKey.comment(commentId, version), clean, version,
                    TextStorageKey.sha256Hex(clean), Instant.now(), false);
            dao.saveComment(row);
            return toContent(row);
        } catch (TextStorageException e) {
            throw e;
        } catch (Exception e) {
            throw new TextStorageWriteException("failed to save comment text: " + commentId, e);
        }
    }

    @Override
    public Optional<TextContent> findPostText(long postId) {
        try {
            return dao.findPost(postId).filter(row -> !row.deleted()).map(this::toContent);
        } catch (Exception e) {
            throw new TextStorageReadException("failed to read post text: " + postId, e);
        }
    }

    @Override
    public Optional<TextContent> findCommentText(long commentId) {
        try {
            return dao.findComment(commentId).filter(row -> !row.deleted()).map(this::toContent);
        } catch (Exception e) {
            throw new TextStorageReadException("failed to read comment text: " + commentId, e);
        }
    }

    @Override
    public Map<Long, TextContent> findCommentTexts(Collection<Long> commentIds) {
        try {
            Map<Long, TextContent> result = new LinkedHashMap<>();
            dao.findComments(commentIds).forEach((id, row) -> {
                if (!row.deleted()) {
                    result.put(id, toContent(row));
                }
            });
            return result;
        } catch (Exception e) {
            throw new TextStorageReadException("failed to batch read comment text", e);
        }
    }

    @Override
    public void softDeletePostText(long postId) {
        PostTextRow current = dao.findPost(postId)
                .orElseThrow(() -> new TextContentNotFoundException("post text not found: " + postId));
        dao.savePost(new PostTextRow(postId, current.textKey(), current.body(), current.version(),
                current.contentSha256(), Instant.now(), true));
    }

    @Override
    public void softDeleteCommentText(long commentId) {
        CommentTextRow current = dao.findComment(commentId)
                .orElseThrow(() -> new TextContentNotFoundException("comment text not found: " + commentId));
        dao.saveComment(new CommentTextRow(commentId, current.textKey(), current.body(), current.version(),
                current.contentSha256(), Instant.now(), true));
    }

    private String requireBody(String body) {
        if (body == null || body.isBlank()) {
            throw new TextStorageWriteException("text body must not be blank");
        }
        return body;
    }

    private TextContent toContent(PostTextRow row) {
        return new TextContent(TextType.POST, row.postId(), row.textKey(), row.body(), row.version(),
                row.contentSha256(), row.updatedAt(), row.deleted());
    }

    private TextContent toContent(CommentTextRow row) {
        return new TextContent(TextType.COMMENT, row.commentId(), row.textKey(), row.body(), row.version(),
                row.contentSha256(), row.updatedAt(), row.deleted());
    }
}
```

- [ ] **Step 7: Run text storage tests**

Run:

```powershell
$mvn = "$env:USERPROFILE\Desktop\$([char]0x6587)$([char]0x6863)\.codex\tools\apache-maven-3.9.6\bin\mvn.cmd"; & $mvn -Dtest="com.tongji.textstorage.*Test" test
```

Expected: PASS.

- [ ] **Step 8: Commit**

```powershell
git add src/main/java/com/tongji/textstorage src/test/java/com/tongji/textstorage
git commit -m "feat: add cassandra text storage service"
```

---

## Chunk 3: Post, Search, and RAG Integration

### Task 5: Store Post Text in Cassandra During Content Confirm

**Files:**

- Modify: `src/main/java/com/tongji/knowpost/api/dto/KnowPostContentConfirmRequest.java`
- Modify: `src/main/java/com/tongji/knowpost/api/dto/KnowPostDetailResponse.java`
- Modify: `src/main/java/com/tongji/knowpost/api/KnowPostController.java`
- Modify: `src/main/java/com/tongji/knowpost/service/KnowPostService.java`
- Modify: `src/main/java/com/tongji/knowpost/service/impl/KnowPostServiceImpl.java`
- Modify: `src/main/java/com/tongji/knowpost/model/KnowPostDetailRow.java`
- Modify: `src/main/resources/mapper/KnowPostMapper.xml`

- [ ] **Step 1: Update request DTO**

Replace object metadata fields with text:

```java
package com.tongji.knowpost.api.dto;

import jakarta.validation.constraints.NotBlank;

public record KnowPostContentConfirmRequest(
        @NotBlank String content
) {
}
```

- [ ] **Step 2: Update service interface and controller call**

Change `KnowPostService.confirmContent` to:

```java
void confirmContent(long creatorId, long id, String content);
```

Change controller:

```java
service.confirmContent(userId, id, request.content());
```

- [ ] **Step 3: Inject `TextStorageService` into `KnowPostServiceImpl`**

Add import and field:

```java
import com.tongji.textstorage.TextContent;
import com.tongji.textstorage.TextStorageService;
```

```java
private final TextStorageService textStorageService;
```

Add constructor parameter and assignment.

- [ ] **Step 4: Validate post ownership before Cassandra write**

At the start of `confirmContent`, validate that the post exists and belongs to `creatorId` before writing text:

```java
KnowPost existing = mapper.findById(id);
if (existing == null || existing.getCreatorId() == null || !existing.getCreatorId().equals(creatorId)) {
    throw new BusinessException(ErrorCode.BAD_REQUEST, "content does not exist or no permission");
}
```

This prevents orphan Cassandra text rows for invalid or unauthorized `post_id` values.

- [ ] **Step 5: Save text and update MySQL metadata**

Change `confirmContent` to:

```java
@Transactional
public void confirmContent(long creatorId, long id, String content) {
    invalidateCache(id);

    KnowPost existing = mapper.findById(id);
    if (existing == null || existing.getCreatorId() == null || !existing.getCreatorId().equals(creatorId)) {
        throw new BusinessException(ErrorCode.BAD_REQUEST, "content does not exist or no permission");
    }

    TextContent stored = textStorageService.savePostText(id, content);
    KnowPost post = KnowPost.builder()
            .id(id)
            .creatorId(creatorId)
            .contentObjectKey(stored.textKey())
            .contentEtag(null)
            .contentSize((long) content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
            .contentSha256(stored.contentSha256())
            .contentUrl(null)
            .updateTime(Instant.now())
            .build();

    int updated = mapper.updateContent(post);
    if (updated == 0) {
        throw new BusinessException(ErrorCode.BAD_REQUEST, "content does not exist or no permission");
    }

    invalidateCache(id);
    try {
        ragIndexService.ensureIndexed(id);
    } catch (Exception e) {
        log.warn("Pre-index after content confirm failed, post {}: {}", id, e.getMessage());
    }
}
```

Keep the project message language if the file already uses Chinese literals.

- [ ] **Step 6: Make MyBatis clear old object-storage text columns**

The existing `updateContent` mapper only updates fields when values are non-null, so `content_url` and `content_etag` will not be cleared by setting Java fields to `null`.

Change `KnowPostMapper.xml` `updateContent` to explicitly set the Cassandra text metadata:

```xml
<update id="updateContent" parameterType="com.tongji.knowpost.model.KnowPost">
    UPDATE know_posts
    SET
        content_url = NULL,
        content_object_key = #{contentObjectKey},
        content_etag = NULL,
        content_size = #{contentSize},
        content_sha256 = #{contentSha256},
        update_time = #{updateTime}
    WHERE id = #{id} AND creator_id = #{creatorId}
</update>
```

This is acceptable because this API path now confirms Cassandra-backed text, not MinIO-backed media.

- [ ] **Step 7: Expose content object key in detail row**

Add `contentObjectKey` field to `KnowPostDetailRow`, result map, and `findDetailById` select:

```xml
p.content_object_key AS contentObjectKey,
```

- [ ] **Step 8: Add Cassandra text body to detail response**

Modify `KnowPostDetailResponse` to add a `String content` field immediately after `contentUrl`:

```java
public record KnowPostDetailResponse(
        String id,
        String title,
        String description,
        String contentUrl,
        String content,
        List<String> images,
        List<String> tags,
        String authorId,
        String authorAvatar,
        String authorNickname,
        String authorTagJson,
        Long likeCount,
        Long favoriteCount,
        Boolean liked,
        Boolean faved,
        Boolean isTop,
        String visible,
        String type,
        Instant publishTime
) {
}
```

In `KnowPostServiceImpl#getDetail`, read Cassandra text after permission checks:

```java
String body = textStorageService.findPostText(id)
        .map(TextContent::body)
        .orElse(null);
```

Pass `body` to the response constructor. In `enrichDetailResponse`, preserve `base.content()` in the copied response.

- [ ] **Step 9: Compile**

Run:

```powershell
$mvn = "$env:USERPROFILE\Desktop\$([char]0x6587)$([char]0x6863)\.codex\tools\apache-maven-3.9.6\bin\mvn.cmd"; & $mvn -DskipTests compile
```

Expected: PASS.

### Task 6: Make Search and RAG Read Text from Cassandra

**Files:**

- Modify: `src/main/java/com/tongji/search/index/SearchIndexService.java`
- Modify: `src/main/java/com/tongji/llm/rag/RagIndexService.java`

- [ ] **Step 1: Inject text storage into search index service**

Add:

```java
import com.tongji.textstorage.TextStorageService;
```

Add final field:

```java
private final TextStorageService textStorageService;
```

Replace:

```java
String body = fetchContentSafe(row.getContentUrl());
```

with:

```java
String body = textStorageService.findPostText(row.getId())
        .map(text -> text.body())
        .orElse(null);
```

Keep description fallback unchanged.

- [ ] **Step 2: Remove unused HTTP content fetch helpers from search**

Remove `RestTemplate http`, HTTP imports, charset sniffing helpers, and regex imports if unused after the replacement.

- [ ] **Step 3: Inject text storage into RAG index service**

Add:

```java
import com.tongji.textstorage.TextStorageService;
```

Add final field:

```java
private final TextStorageService textStorageService;
```

Replace the content URL guard:

```java
if (!StringUtils.hasText(row.getContentUrl())) {
    log.warn("Post {} missing contentUrl or not found", postId);
    return 0;
}
```

with:

```java
String text = textStorageService.findPostText(postId)
        .map(content -> content.body())
        .orElse(null);
if (!StringUtils.hasText(text)) {
    log.warn("Post {} missing text content", postId);
    return 0;
}
```

Remove the later `fetchContent(row.getContentUrl())` call and use the `text` variable.

- [ ] **Step 4: Update RAG metadata**

Replace `contentUrl` metadata with `contentKey`:

```java
meta.put("contentKey", row.getContentObjectKey());
```

Keep `contentEtag` and `contentSha256` fingerprint behavior for now, because `contentSha256` remains the main text fingerprint.

- [ ] **Step 5: Remove unused RAG HTTP fetch code**

Remove `RestTemplate http` and the private `fetchContent` method if unused.

- [ ] **Step 6: Search for stale text-object assumptions**

Run:

```powershell
rg -n "fetchContent|contentUrl|knowpost_content|objectKey\\(|etag\\(|sha256\\(|size\\(" src/main/java src/main/resources
```

Expected:

- `contentUrl` may remain in DTO/detail compatibility and MySQL model.
- No search/RAG code fetches post body from `contentUrl`.
- No content confirm path requires MinIO object metadata for text.

- [ ] **Step 7: Compile**

Run:

```powershell
$mvn = "$env:USERPROFILE\Desktop\$([char]0x6587)$([char]0x6863)\.codex\tools\apache-maven-3.9.6\bin\mvn.cmd"; & $mvn -DskipTests compile
```

Expected: PASS.

- [ ] **Step 8: Commit**

```powershell
git add src/main/java/com/tongji/knowpost src/main/java/com/tongji/search/index/SearchIndexService.java src/main/java/com/tongji/llm/rag/RagIndexService.java src/main/resources/mapper/KnowPostMapper.xml
git commit -m "feat: store post text in cassandra"
```

---

## Chunk 4: Verification, Smoke Test, Docs, and OpenSpec Closure

### Task 7: Add Local Cassandra Smoke Test

**Files:**

- Create: `src/test/java/com/tongji/textstorage/CassandraTextStorageSmokeTest.java`

- [ ] **Step 1: Add smoke test gated by environment variable**

Create:

```java
package com.tongji.textstorage;

import com.datastax.oss.driver.api.core.CqlSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.net.InetSocketAddress;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "CASSANDRA_SMOKE", matches = "true")
class CassandraTextStorageSmokeTest {
    @Test
    void writesAndReadsPostTextAgainstLocalCassandra() {
        long postId = System.currentTimeMillis();

        try (CqlSession session = CqlSession.builder()
                .addContactPoint(new InetSocketAddress(
                        System.getenv().getOrDefault("CASSANDRA_CONTACT_POINTS", "localhost"),
                        Integer.parseInt(System.getenv().getOrDefault("CASSANDRA_PORT", "9042"))))
                .withLocalDatacenter(System.getenv().getOrDefault("CASSANDRA_LOCAL_DATACENTER", "datacenter1"))
                .withKeyspace(System.getenv().getOrDefault("CASSANDRA_KEYSPACE", "zhiguang_text"))
                .build()) {
            TextStorageService textStorageService = new CassandraTextStorageService(new CassandraTextStorageDao(session));
            TextContent saved = textStorageService.savePostText(postId, "local smoke text");

            assertThat(textStorageService.findPostText(postId)).hasValueSatisfying(read -> {
                assertThat(read.textKey()).isEqualTo(saved.textKey());
                assertThat(read.body()).isEqualTo("local smoke text");
            });
        }
    }
}
```

- [ ] **Step 2: Run normal tests and verify smoke is skipped**

Run:

```powershell
$mvn = "$env:USERPROFILE\Desktop\$([char]0x6587)$([char]0x6863)\.codex\tools\apache-maven-3.9.6\bin\mvn.cmd"; & $mvn -Dtest=com.tongji.textstorage.CassandraTextStorageSmokeTest test
```

Expected: build succeeds with the smoke test skipped unless `CASSANDRA_SMOKE=true`. This smoke test must not use `@SpringBootTest`; it should create `CqlSession`, `CassandraTextStorageDao`, and `CassandraTextStorageService` directly so it only depends on Cassandra.

### Task 8: Update Local Docs

**Files:**

- Modify: `docs/docker-local-env.md`
- Optionally modify API docs if the implementation changes request payload docs.

- [ ] **Step 1: Document Cassandra service**

Add Cassandra to the services list:

```markdown
- Cassandra: `localhost:9042`
```

Add Cassandra defaults:

```markdown
Cassandra defaults:

- Keyspace: `zhiguang_text`
- Local datacenter: `datacenter1`
- Schema file: `cassandra/schema.cql`
```

- [ ] **Step 2: Document smoke command**

Add:

```bash
CASSANDRA_SMOKE=true mvn -Dtest=com.tongji.textstorage.CassandraTextStorageSmokeTest test
```

For PowerShell:

```powershell
$env:CASSANDRA_SMOKE='true'
$mvn = "$env:USERPROFILE\Desktop\$([char]0x6587)$([char]0x6863)\.codex\tools\apache-maven-3.9.6\bin\mvn.cmd"; & $mvn -Dtest=com.tongji.textstorage.CassandraTextStorageSmokeTest test
Remove-Item Env:\CASSANDRA_SMOKE
```

### Task 9: Full Verification and OpenSpec Task Closure

**Files:**

- Modify: `openspec/changes/add-cassandra-text-storage/tasks.md`

- [ ] **Step 1: Run targeted text storage tests**

Run:

```powershell
$mvn = "$env:USERPROFILE\Desktop\$([char]0x6587)$([char]0x6863)\.codex\tools\apache-maven-3.9.6\bin\mvn.cmd"; & $mvn -Dtest="com.tongji.textstorage.*Test" test
```

Expected: PASS.

- [ ] **Step 2: Run compile**

Run:

```powershell
$mvn = "$env:USERPROFILE\Desktop\$([char]0x6587)$([char]0x6863)\.codex\tools\apache-maven-3.9.6\bin\mvn.cmd"; & $mvn -DskipTests compile
```

Expected: PASS.

- [ ] **Step 3: Run full test suite**

Run:

```powershell
$mvn = "$env:USERPROFILE\Desktop\$([char]0x6587)$([char]0x6863)\.codex\tools\apache-maven-3.9.6\bin\mvn.cmd"; & $mvn test
```

Expected: PASS. If unrelated external-service tests fail, also run Step 2 and record the external dependency in the final note.

- [ ] **Step 4: Optional local Cassandra smoke**

Only when local Docker services are running:

```powershell
docker compose up -d cassandra cassandra-init
$env:CASSANDRA_SMOKE='true'
$mvn = "$env:USERPROFILE\Desktop\$([char]0x6587)$([char]0x6863)\.codex\tools\apache-maven-3.9.6\bin\mvn.cmd"; & $mvn -Dtest=com.tongji.textstorage.CassandraTextStorageSmokeTest test
Remove-Item Env:\CASSANDRA_SMOKE
```

Expected: PASS.

- [ ] **Step 5: Mark OpenSpec tasks complete**

Update `openspec/changes/add-cassandra-text-storage/tasks.md` checkboxes only after verification:

- `1.1` Cassandra compose service, port, volume, healthcheck exist.
- `1.2` Cassandra application config exists.
- `1.3` CQL schema initialization exists.
- `2.1` `post_text_by_post_id` exists.
- `2.2` `comment_text_by_comment_id` exists.
- `2.3` `text_write_log_by_day` exists.
- `3.1` `TextStorageService` supports save/read/batch read/soft delete.
- `3.2` Cassandra implementation exists.
- `3.3` post/comment key rules exist.
- `3.4` write/read/missing-text exception types exist.
- `4.1` post text confirm writes text to Cassandra and metadata to MySQL.
- `4.2` search/RAG read text from Cassandra.
- `4.3` comment text write and batch read capability exists, without adding comment business APIs.
- `5.1` local Cassandra docs exist.
- `5.2` unit tests exist and pass.
- `5.3` smoke test exists and is documented.

- [ ] **Step 6: Commit verification closure**

```powershell
git add docs/docker-local-env.md openspec/changes/add-cassandra-text-storage/tasks.md src/test/java/com/tongji/textstorage/CassandraTextStorageSmokeTest.java
git commit -m "docs: mark cassandra text storage tasks complete"
```

---

## Risk Checks

- Do not use Cassandra for feed, author lists, search, or comment pagination.
- Do not use Elasticsearch or the vector store as the text fact store.
- Do not migrate historical MinIO text objects in this change.
- Do not create comment business APIs; only provide storage capability for the future comment change.
- Do not store images, videos, or attachments in Cassandra.
- Do not add a fallback that silently treats missing Cassandra text as valid post text. Search may fall back to description, but RAG should skip missing text.
- Keep Cassandra tables keyed by exact ID lookup.

## Final Completion Criteria

- Docker Compose can start Cassandra and run `cassandra/schema.cql`.
- `TextStorageService` is the only business-facing text storage API.
- Post text confirm writes text to Cassandra and records Cassandra key/hash metadata in MySQL.
- Search indexing and RAG indexing read text from Cassandra.
- Comment text save and batch-read methods are available for the later comment system.
- MinIO remains the media storage path.
- Unit tests pass, compile passes, and the optional local Cassandra smoke test is available.
- `openspec/changes/add-cassandra-text-storage/tasks.md` is fully checked off after verification.
