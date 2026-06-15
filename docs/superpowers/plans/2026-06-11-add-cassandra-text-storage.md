# add-cassandra-text-storage Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Introduce Cassandra as the authoritative text fact store for published post content and comment text, replacing MinIO as the primary text read path for ES and RAG indexing.

**Architecture:** A new `TextStorageService` interface in `com.tongji.storage.text` wraps Cassandra reads/writes. The publish flow writes post text to Cassandra synchronously (blocking publish on failure). ES and RAG indexing switch to Cassandra-first with MinIO fallback for legacy posts. Comment text capability is exposed for the future comment system.

**Tech Stack:** Spring Data Cassandra (spring-boot-starter-data-cassandra), Cassandra 4.1 via Docker, Testcontainers for integration tests, existing Spring Boot 3.2.4 + Maven project.

**Key design decisions (from design.md):**
- Content key = `post_id` / `comment_id` directly (no separate key column)
- Cassandra write failure on publish → block entire publish (throw exception)
- Old posts not in Cassandra → fallback to MinIO `content_url` via HTTP
- Deleted post/comment → hard delete Cassandra row
- Re-publish after edit → overwrite same row, `version` field increments
- No `text_write_log_by_day` table in v1
- Schema init via CQL script mounted in docker-compose, NOT Spring auto-DDL

---

## File Map

**New files:**
- `pom.xml` — add `spring-boot-starter-data-cassandra` + testcontainers dependency
- `docker-compose.yml` — add `cassandra` + `cassandra-init` services
- `db/cassandra/init.cql` — Cassandra keyspace and table DDL
- `src/main/resources/application.yml` — add `spring.cassandra.*` config block
- `src/main/java/com/tongji/storage/text/TextStorageException.java` — base exception
- `src/main/java/com/tongji/storage/text/TextWriteException.java` — write failure (阻断发布)
- `src/main/java/com/tongji/storage/text/TextReadException.java` — read failure (记录日志，不阻断)
- `src/main/java/com/tongji/storage/text/TextStorageService.java` — service interface
- `src/main/java/com/tongji/storage/text/PostText.java` — Cassandra entity for post text
- `src/main/java/com/tongji/storage/text/CommentText.java` — Cassandra entity for comment text
- `src/main/java/com/tongji/storage/text/PostTextRepository.java` — Spring Data repository
- `src/main/java/com/tongji/storage/text/CommentTextRepository.java` — Spring Data repository
- `src/main/java/com/tongji/storage/text/CassandraTextStorageService.java` — service implementation
- `src/test/java/com/tongji/storage/text/CassandraTextStorageServiceTest.java` — unit tests (mocked repos)
- `src/test/java/com/tongji/storage/text/TextStorageSmokeTest.java` — Testcontainers integration test

**Modified files:**
- `src/main/java/com/tongji/knowpost/service/impl/KnowPostServiceImpl.java` — inject `TextStorageService`, write text to Cassandra on publish
- `src/main/java/com/tongji/search/index/SearchIndexService.java` — read body from Cassandra first, fallback to MinIO URL
- `src/main/java/com/tongji/llm/rag/RagIndexService.java` — read text from Cassandra first, fallback to MinIO URL

---

## Task 1: Add Maven Dependency

**Files:**
- Modify: `pom.xml`

- [ ] **Step 1: Add spring-boot-starter-data-cassandra to pom.xml**

Inside `<dependencies>`, after the MinIO dependency block, add:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-cassandra</artifactId>
</dependency>
```

- [ ] **Step 2: Add Testcontainers Cassandra dependency for tests**

```xml
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>cassandra</artifactId>
    <scope>test</scope>
</dependency>
```

If `testcontainers` BOM is not yet present, add inside `<dependencyManagement>`:

```xml
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>testcontainers-bom</artifactId>
    <version>1.20.4</version>
    <type>pom</type>
    <scope>import</scope>
</dependency>
```

- [ ] **Step 3: Verify the project still compiles**

```bash
cd /Users/huangyaokai/zhiguang_be
mvn compile -q
```

Expected: `BUILD SUCCESS` (no Cassandra code yet, just dependency added)

- [ ] **Step 4: Commit**

```bash
git add pom.xml
git commit -m "chore: add spring-data-cassandra and testcontainers dependency"
```

---

## Task 2: Docker Compose + CQL Schema + application.yml

**Files:**
- Modify: `docker-compose.yml`
- Create: `db/cassandra/init.cql`
- Modify: `src/main/resources/application.yml`

- [ ] **Step 1: Add Cassandra and cassandra-init services to docker-compose.yml**

Append to the `services:` block (before the `volumes:` section):

```yaml
  cassandra:
    image: cassandra:4.1
    container_name: zhiguang-cassandra
    ports:
      - "9042:9042"
    environment:
      CASSANDRA_CLUSTER_NAME: zhiguang-cluster
    volumes:
      - cassandra_data:/var/lib/cassandra
    healthcheck:
      test: ["CMD-SHELL", "cqlsh -e 'describe keyspaces' || exit 1"]
      interval: 30s
      timeout: 10s
      retries: 10
      start_period: 90s

  cassandra-init:
    image: cassandra:4.1
    depends_on:
      cassandra:
        condition: service_healthy
    volumes:
      - ./db/cassandra:/scripts
    command: cqlsh cassandra -f /scripts/init.cql
    restart: "no"
```

Also add `cassandra_data:` under the existing `volumes:` block.

- [ ] **Step 2: Create db/cassandra/init.cql**

```cql
CREATE KEYSPACE IF NOT EXISTS zhiguang
  WITH replication = {'class': 'SimpleStrategy', 'replication_factor': 1};

CREATE TABLE IF NOT EXISTS zhiguang.post_text_by_post_id (
  post_id    bigint PRIMARY KEY,
  body       text,
  version    int,
  sha256     text,
  updated_at timestamp
);

CREATE TABLE IF NOT EXISTS zhiguang.comment_text_by_comment_id (
  comment_id bigint PRIMARY KEY,
  body       text,
  version    int,
  updated_at timestamp
);
```

- [ ] **Step 3: Add Cassandra config to application.yml**

The existing `application.yml` already has a top-level `spring:` key. **Do NOT add a new `spring:` key** — that produces invalid YAML (duplicate keys). Instead, add the `cassandra:` block nested inside the existing `spring:` section:

```yaml
spring:
  # ... existing datasource, redis, kafka, ai config stays here ...
  cassandra:
    keyspace-name: ${CASSANDRA_KEYSPACE:zhiguang}
    contact-points: ${CASSANDRA_CONTACT_POINTS:localhost}
    port: ${CASSANDRA_PORT:9042}
    local-datacenter: ${CASSANDRA_DATACENTER:datacenter1}
    schema-action: none
    request:
      timeout: 5s
      consistency: LOCAL_ONE
```

- [ ] **Step 4: Start Cassandra locally and verify schema**

```bash
docker compose up cassandra -d
# Wait ~90s for health check to pass
docker compose up cassandra-init
docker exec zhiguang-cassandra cqlsh -e "DESCRIBE TABLES IN zhiguang;"
```

Expected output includes: `post_text_by_post_id` and `comment_text_by_comment_id`

- [ ] **Step 5: Commit**

```bash
git add docker-compose.yml db/cassandra/init.cql src/main/resources/application.yml
git commit -m "feat: add Cassandra docker service and schema init"
```

---

## Task 3: Exception Types + Service Interface

> **tasks.md 3.4** 要求三种异常：写失败、读失败、缺正文。本任务建基类 + 两个子类；"缺正文"用返回 `Optional.empty()` 表达，不抛异常（见 design.md 决策 3）。

**Files:**
- Create: `src/main/java/com/tongji/storage/text/TextStorageException.java`
- Create: `src/main/java/com/tongji/storage/text/TextWriteException.java`
- Create: `src/main/java/com/tongji/storage/text/TextReadException.java`
- Create: `src/main/java/com/tongji/storage/text/TextStorageService.java`

- [ ] **Step 1: Create TextStorageException.java (base)**

```java
package com.tongji.storage.text;

public class TextStorageException extends RuntimeException {
    public TextStorageException(String message) { super(message); }
    public TextStorageException(String message, Throwable cause) { super(message, cause); }
}
```

- [ ] **Step 2: Create TextWriteException.java**

```java
package com.tongji.storage.text;

public class TextWriteException extends TextStorageException {
    public TextWriteException(String message, Throwable cause) { super(message, cause); }
}
```

- [ ] **Step 3: Create TextReadException.java**

```java
package com.tongji.storage.text;

public class TextReadException extends TextStorageException {
    public TextReadException(String message, Throwable cause) { super(message, cause); }
}
```

- [ ] **Step 4: Create TextStorageService.java**

> **Note on interface vs design.md:** design.md 写的是 `getPostText(postId)`，但服务层不应该内部查 MySQL 来获取 `content_url`，因此将 fallback URL 作为参数传入。调用方（SearchIndexService、RagIndexService、KnowPostServiceImpl）已有 `content_url`，直接传入更干净，不引入跨模块 DB 依赖。

```java
package com.tongji.storage.text;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;

public interface TextStorageService {

    /**
     * Saves post text to Cassandra. Overwrites existing row, increments version.
     * Throws TextWriteException if write fails — callers must handle (publish should block).
     */
    void savePostText(long postId, String body);

    /**
     * Reads post text from Cassandra. If missing, falls back to fetching from fallbackContentUrl.
     * Returns empty if both sources are missing. Never throws — read failures are logged and treated as missing.
     * Pass null for fallbackContentUrl to skip MinIO fallback (new posts always in Cassandra).
     */
    Optional<String> getPostText(long postId, String fallbackContentUrl);

    /**
     * Saves comment text to Cassandra. Overwrites existing row.
     * Throws TextWriteException if write fails.
     */
    void saveCommentText(long commentId, String body);

    /**
     * Batch reads comment text by comment IDs.
     * Returns only the IDs that exist; missing IDs are absent from the map.
     * Never throws — read failures are logged and treated as missing.
     */
    Map<Long, String> getCommentTexts(Collection<Long> commentIds);

    /** Hard-deletes post text row from Cassandra. */
    void deletePostText(long postId);

    /** Hard-deletes comment text row from Cassandra. */
    void deleteCommentText(long commentId);
}
```

- [ ] **Step 5: Verify compilation**

```bash
mvn compile -q
```

Expected: `BUILD SUCCESS`

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/tongji/storage/text/
git commit -m "feat: add TextStorageService interface and exception hierarchy"
```

---

## Task 4: Cassandra Entity Classes + Repositories

**Files:**
- Create: `src/main/java/com/tongji/storage/text/PostText.java`
- Create: `src/main/java/com/tongji/storage/text/CommentText.java`
- Create: `src/main/java/com/tongji/storage/text/PostTextRepository.java`
- Create: `src/main/java/com/tongji/storage/text/CommentTextRepository.java`

- [ ] **Step 1: Create PostText.java**

```java
package com.tongji.storage.text;

import org.springframework.data.cassandra.core.mapping.PrimaryKey;
import org.springframework.data.cassandra.core.mapping.Table;
import java.time.Instant;

@Table("post_text_by_post_id")
public class PostText {

    @PrimaryKey
    private long postId;
    private String body;
    private int version;
    private String sha256;
    private Instant updatedAt;

    public PostText() {}

    public PostText(long postId, String body, int version, String sha256, Instant updatedAt) {
        this.postId = postId;
        this.body = body;
        this.version = version;
        this.sha256 = sha256;
        this.updatedAt = updatedAt;
    }

    public long getPostId() { return postId; }
    public void setPostId(long postId) { this.postId = postId; }
    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }
    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }
    public String getSha256() { return sha256; }
    public void setSha256(String sha256) { this.sha256 = sha256; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
```

- [ ] **Step 2: Create CommentText.java**

```java
package com.tongji.storage.text;

import org.springframework.data.cassandra.core.mapping.PrimaryKey;
import org.springframework.data.cassandra.core.mapping.Table;
import java.time.Instant;

@Table("comment_text_by_comment_id")
public class CommentText {

    @PrimaryKey
    private long commentId;
    private String body;
    private int version;
    private Instant updatedAt;

    public CommentText() {}

    public CommentText(long commentId, String body, int version, Instant updatedAt) {
        this.commentId = commentId;
        this.body = body;
        this.version = version;
        this.updatedAt = updatedAt;
    }

    public long getCommentId() { return commentId; }
    public void setCommentId(long commentId) { this.commentId = commentId; }
    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }
    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
```

- [ ] **Step 3: Create PostTextRepository.java**

```java
package com.tongji.storage.text;

import org.springframework.data.cassandra.repository.CassandraRepository;

public interface PostTextRepository extends CassandraRepository<PostText, Long> {}
```

- [ ] **Step 4: Create CommentTextRepository.java**

```java
package com.tongji.storage.text;

import org.springframework.data.cassandra.repository.CassandraRepository;

public interface CommentTextRepository extends CassandraRepository<CommentText, Long> {}
```

- [ ] **Step 5: Verify compilation**

```bash
mvn compile -q
```

Expected: `BUILD SUCCESS`

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/tongji/storage/text/
git commit -m "feat: add Cassandra entity classes and repositories"
```

---

## Task 5: CassandraTextStorageService Unit Tests (TDD — write tests first)

**Files:**
- Create: `src/test/java/com/tongji/storage/text/CassandraTextStorageServiceTest.java`

- [ ] **Step 1: Write failing unit tests**

```java
package com.tongji.storage.text;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CassandraTextStorageServiceTest {

    @Mock PostTextRepository postRepo;
    @Mock CommentTextRepository commentRepo;
    @Mock RestTemplate restTemplate;

    CassandraTextStorageService service;

    @BeforeEach
    void setUp() {
        service = new CassandraTextStorageService(postRepo, commentRepo, restTemplate);
    }

    @Test
    void savePostText_savesNewRow() {
        when(postRepo.findById(1L)).thenReturn(Optional.empty());
        service.savePostText(1L, "hello world");
        verify(postRepo).save(argThat(p ->
            p.getPostId() == 1L &&
            "hello world".equals(p.getBody()) &&
            p.getVersion() == 1
        ));
    }

    @Test
    void savePostText_incrementsVersionOnOverwrite() {
        PostText existing = new PostText(1L, "old", 3, null, Instant.now());
        when(postRepo.findById(1L)).thenReturn(Optional.of(existing));
        service.savePostText(1L, "new body");
        verify(postRepo).save(argThat(p -> p.getVersion() == 4));
    }

    @Test
    void savePostText_throwsTextWriteExceptionOnRepositoryFailure() {
        when(postRepo.findById(anyLong())).thenReturn(Optional.empty());
        doThrow(new RuntimeException("Cassandra down")).when(postRepo).save(any());
        assertThatThrownBy(() -> service.savePostText(1L, "body"))
            .isInstanceOf(TextWriteException.class)
            .hasMessageContaining("Failed to save post text");
    }

    @Test
    void getPostText_returnsCassandraBodyWhenPresent() {
        PostText row = new PostText(2L, "cassandra body", 1, null, Instant.now());
        when(postRepo.findById(2L)).thenReturn(Optional.of(row));
        Optional<String> result = service.getPostText(2L, "http://minio/fallback");
        assertThat(result).contains("cassandra body");
        verifyNoInteractions(restTemplate);
    }

    @Test
    void getPostText_fallsBackToMinioWhenCassandraMissing() {
        when(postRepo.findById(3L)).thenReturn(Optional.empty());
        when(restTemplate.getForObject("http://minio/content.md", String.class)).thenReturn("minio body");
        Optional<String> result = service.getPostText(3L, "http://minio/content.md");
        assertThat(result).contains("minio body");
    }

    @Test
    void getPostText_returnsEmptyWhenBothMissing() {
        when(postRepo.findById(4L)).thenReturn(Optional.empty());
        when(restTemplate.getForObject(any(), eq(String.class))).thenReturn(null);
        Optional<String> result = service.getPostText(4L, "http://minio/missing");
        assertThat(result).isEmpty();
    }

    @Test
    void getPostText_returnsEmptyWhenFallbackUrlIsNull() {
        when(postRepo.findById(5L)).thenReturn(Optional.empty());
        Optional<String> result = service.getPostText(5L, null);
        assertThat(result).isEmpty();
        verifyNoInteractions(restTemplate);
    }

    @Test
    void deletePostText_deletesRow() {
        service.deletePostText(10L);
        verify(postRepo).deleteById(10L);
    }

    @Test
    void saveCommentText_savesRow() {
        when(commentRepo.findById(20L)).thenReturn(Optional.empty());
        service.saveCommentText(20L, "great post");
        verify(commentRepo).save(argThat(c ->
            c.getCommentId() == 20L &&
            "great post".equals(c.getBody()) &&
            c.getVersion() == 1
        ));
    }

    @Test
    void getCommentTexts_returnsMapOfPresentIds() {
        CommentText c1 = new CommentText(1L, "body1", 1, Instant.now());
        CommentText c2 = new CommentText(2L, "body2", 1, Instant.now());
        when(commentRepo.findAllById(List.of(1L, 2L, 3L))).thenReturn(List.of(c1, c2));
        Map<Long, String> result = service.getCommentTexts(List.of(1L, 2L, 3L));
        assertThat(result).containsEntry(1L, "body1").containsEntry(2L, "body2").doesNotContainKey(3L);
    }

    @Test
    void deleteCommentText_deletesRow() {
        service.deleteCommentText(30L);
        verify(commentRepo).deleteById(30L);
    }
}
```

- [ ] **Step 2: Run tests to verify they fail (class doesn't exist yet)**

```bash
mvn test -pl . -Dtest=CassandraTextStorageServiceTest -q 2>&1 | tail -5
```

Expected: compilation error — `CassandraTextStorageService` not found

- [ ] **Step 3: Commit the failing tests**

```bash
git add src/test/java/com/tongji/storage/text/CassandraTextStorageServiceTest.java
git commit -m "test: add failing unit tests for CassandraTextStorageService"
```

---

## Task 6: CassandraTextStorageService Implementation

**Files:**
- Create: `src/main/java/com/tongji/storage/text/CassandraTextStorageService.java`

- [ ] **Step 1: Create the implementation**

```java
package com.tongji.storage.text;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

@Service
public class CassandraTextStorageService implements TextStorageService {

    private static final Logger log = LoggerFactory.getLogger(CassandraTextStorageService.class);

    private final PostTextRepository postRepo;
    private final CommentTextRepository commentRepo;
    private final RestTemplate restTemplate;

    public CassandraTextStorageService(PostTextRepository postRepo,
                                       CommentTextRepository commentRepo,
                                       RestTemplate restTemplate) {
        this.postRepo = postRepo;
        this.commentRepo = commentRepo;
        this.restTemplate = restTemplate;
    }

    @Override
    public void savePostText(long postId, String body) {
        try {
            int version = postRepo.findById(postId)
                .map(p -> p.getVersion() + 1)
                .orElse(1);
            postRepo.save(new PostText(postId, body, version, null, Instant.now()));
        } catch (Exception e) {
            throw new TextWriteException("Failed to save post text for postId=" + postId, e);
        }
    }

    @Override
    public Optional<String> getPostText(long postId, String fallbackContentUrl) {
        try {
            Optional<PostText> row = postRepo.findById(postId);
            if (row.isPresent()) {
                return Optional.ofNullable(row.get().getBody());
            }
        } catch (Exception e) {
            log.warn("Cassandra read failed for postId={}, will try fallback: {}", postId, e.getMessage());
        }
        if (fallbackContentUrl == null || fallbackContentUrl.isBlank()) {
            return Optional.empty();
        }
        try {
            String content = restTemplate.getForObject(fallbackContentUrl, String.class);
            return Optional.ofNullable(content).filter(s -> !s.isBlank());
        } catch (Exception e) {
            log.warn("MinIO fallback failed for postId={}: {}", postId, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void saveCommentText(long commentId, String body) {
        try {
            int version = commentRepo.findById(commentId)
                .map(c -> c.getVersion() + 1)
                .orElse(1);
            commentRepo.save(new CommentText(commentId, body, version, Instant.now()));
        } catch (Exception e) {
            throw new TextWriteException("Failed to save comment text for commentId=" + commentId, e);
        }
    }

    @Override
    public Map<Long, String> getCommentTexts(Collection<Long> commentIds) {
        Iterable<CommentText> rows = commentRepo.findAllById(commentIds);
        return StreamSupport.stream(rows.spliterator(), false)
            .filter(c -> c.getBody() != null)
            .collect(Collectors.toMap(CommentText::getCommentId, CommentText::getBody));
    }

    @Override
    public void deletePostText(long postId) {
        postRepo.deleteById(postId);
    }

    @Override
    public void deleteCommentText(long commentId) {
        commentRepo.deleteById(commentId);
    }
}
```

- [ ] **Step 2: Register RestTemplate bean if not already present**

Check if a `RestTemplate` bean exists in the project:

```bash
grep -r "RestTemplate" /Users/huangyaokai/zhiguang_be/src/main/java --include="*.java" -l
```

If no `@Bean RestTemplate` is found, add one in `src/main/java/com/tongji/config/` (create `RestTemplateConfig.java`):

```java
package com.tongji.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

@Configuration
public class RestTemplateConfig {
    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }
}
```

- [ ] **Step 3: Run unit tests — all should pass**

```bash
mvn test -Dtest=CassandraTextStorageServiceTest -q
```

Expected: `Tests run: 11, Failures: 0, Errors: 0`

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/tongji/storage/text/CassandraTextStorageService.java
git add src/main/java/com/tongji/config/RestTemplateConfig.java  # if created
git commit -m "feat: implement CassandraTextStorageService with MinIO fallback"
```

---

## Task 7: Integration Smoke Test (Testcontainers)

**Files:**
- Create: `src/test/java/com/tongji/storage/text/TextStorageSmokeTest.java`

> **重要：** 不能用 `@SpringBootTest` — 它会尝试启动完整 ApplicationContext，需要 MySQL/Redis/Kafka/ES/MinIO 全部就绪，测试环境跑不起来。改用 `@DataCassandraTest`（Spring Boot Cassandra 切片测试），只加载 Cassandra 相关 Bean，其余 Mock。

- [ ] **Step 1: Write the integration test**

```java
package com.tongji.storage.text;

import com.datastax.oss.driver.api.core.CqlSession;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.cassandra.DataCassandraTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestTemplate;
import org.testcontainers.containers.CassandraContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DataCassandraTest
@Import(CassandraTextStorageService.class)
@Testcontainers
class TextStorageSmokeTest {

    @MockBean
    RestTemplate restTemplate;

    @Container
    static CassandraContainer<?> cassandra =
        new CassandraContainer<>("cassandra:4.1").withExposedPorts(9042);

    @DynamicPropertySource
    static void cassandraProperties(DynamicPropertyRegistry registry) {
        // Create keyspace here — @DynamicPropertySource runs BEFORE the Spring context loads,
        // so Cassandra keyspace exists when Spring Data Cassandra tries to connect.
        // @BeforeAll runs AFTER Spring context loads — too late to create keyspace.
        try (CqlSession session = CqlSession.builder()
            .addContactPoint(cassandra.getContactPoint())
            .withLocalDatacenter("datacenter1")
            .build()) {
            session.execute(
                "CREATE KEYSPACE IF NOT EXISTS zhiguang " +
                "WITH replication = {'class':'SimpleStrategy','replication_factor':1}"
            );
        }
        registry.add("spring.cassandra.contact-points", cassandra::getHost);
        registry.add("spring.cassandra.port", () -> cassandra.getMappedPort(9042));
        registry.add("spring.cassandra.local-datacenter", () -> "datacenter1");
        registry.add("spring.cassandra.keyspace-name", () -> "zhiguang");
        // Use create-if-not-exists so Spring Data Cassandra auto-creates tables from @Table entities
        registry.add("spring.cassandra.schema-action", () -> "create-if-not-exists");
    }

    @Autowired
    TextStorageService service;

    @Test
    void postText_roundTrip() {
        service.savePostText(999L, "smoke test body");
        Optional<String> result = service.getPostText(999L, null);
        assertThat(result).contains("smoke test body");
    }

    @Test
    void postText_versionIncrementsOnOverwrite() {
        service.savePostText(1000L, "v1");
        service.savePostText(1000L, "v2");
        Optional<String> result = service.getPostText(1000L, null);
        assertThat(result).contains("v2");
    }

    @Test
    void postText_deleteRemovesRow() {
        service.savePostText(1001L, "to be deleted");
        service.deletePostText(1001L);
        Optional<String> result = service.getPostText(1001L, null);
        assertThat(result).isEmpty();
    }

    @Test
    void commentTexts_batchRead() {
        service.saveCommentText(201L, "comment A");
        service.saveCommentText(202L, "comment B");
        Map<Long, String> result = service.getCommentTexts(java.util.List.of(201L, 202L, 203L));
        assertThat(result).containsEntry(201L, "comment A").containsEntry(202L, "comment B")
            .doesNotContainKey(203L);
    }
}
```

- [ ] **Step 2: Run smoke test (requires Docker)**

```bash
mvn test -Dtest=TextStorageSmokeTest
```

Expected: `Tests run: 4, Failures: 0, Errors: 0` (Testcontainers will pull the Cassandra image on first run — may take a few minutes)

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/tongji/storage/text/TextStorageSmokeTest.java
git commit -m "test: add Testcontainers smoke test for TextStorageService"
```

---

## Task 8: Publish Flow — Write Post Text to Cassandra

**Files:**
- Modify: `src/main/java/com/tongji/knowpost/service/impl/KnowPostServiceImpl.java`

**Context:** The current `publish()` method (lines 180–206) updates DB status, increments user counter, writes Outbox event, and pre-indexes RAG — all synchronous. We add a Cassandra write **before** the DB status update. A Cassandra failure blocks the publish.

> **注意：** 原计划 Task 8 的测试有严重错误：`assertThatThrownBy(() -> { })` 空 lambda 永远通过，是假测试。已修复。

> **注意：** 原 publish() 用 `findById` 后未校验 `status='draft'` 和 `creatorId`，存在无效 Cassandra 写入风险。已修复：前置校验失败直接 fail-fast，Cassandra 写入前先验证。
>
> **@Transactional 说明：** `@Transactional` 只覆盖 MySQL。如果 Cassandra 写入成功但随后 DB CAS 失败（竞争条件），Cassandra 会有孤立数据，这是可接受的最终一致——`add-data-reconciliation` 会处理。

- [ ] **Step 1: Write a failing test for publish-with-cassandra**

In `src/test/java/com/tongji/knowpost/service/KnowPostPublishCassandraTest.java` (new file):

```java
package com.tongji.knowpost.service;

import com.tongji.common.exception.BusinessException;
import com.tongji.knowpost.mapper.KnowPostMapper;
import com.tongji.knowpost.model.KnowPost;
import com.tongji.storage.text.TextWriteException;
import com.tongji.storage.text.TextStorageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class KnowPostPublishCassandraTest {

    @Mock KnowPostMapper mapper;
    @Mock TextStorageService textStorageService;
    @Mock RestTemplate restTemplate;
    @InjectMocks
    com.tongji.knowpost.service.impl.KnowPostServiceImpl service;

    @Test
    void publish_blocksWhenCassandraFails() {
        KnowPost post = new KnowPost();
        post.setId(1L);
        post.setCreatorId(10L);
        post.setStatus("draft");
        post.setContentUrl("http://minio/posts/1/content.md");

        when(mapper.findById(1L)).thenReturn(post);
        when(restTemplate.getForObject("http://minio/posts/1/content.md", String.class))
            .thenReturn("# Hello World");
        doThrow(new TextWriteException("Cassandra down", new RuntimeException()))
            .when(textStorageService).savePostText(eq(1L), any());

        assertThatThrownBy(() -> service.publish(10L, 1L))
            .isInstanceOf(TextWriteException.class);

        verify(mapper, never()).publish(anyLong(), anyLong());  // DB must NOT be updated
    }

    @Test
    void publish_failsWhenPostNotDraft() {
        KnowPost post = new KnowPost();
        post.setId(2L);
        post.setCreatorId(10L);
        post.setStatus("published");  // already published
        post.setContentUrl("http://minio/posts/2/content.md");

        when(mapper.findById(2L)).thenReturn(post);

        assertThatThrownBy(() -> service.publish(10L, 2L))
            .isInstanceOf(BusinessException.class);

        verifyNoInteractions(textStorageService);
    }

    @Test
    void publish_failsWhenWrongCreator() {
        KnowPost post = new KnowPost();
        post.setId(3L);
        post.setCreatorId(99L);  // belongs to someone else
        post.setStatus("draft");
        post.setContentUrl("http://minio/posts/3/content.md");

        when(mapper.findById(3L)).thenReturn(post);

        assertThatThrownBy(() -> service.publish(10L, 3L))  // creatorId=10, but post owned by 99
            .isInstanceOf(BusinessException.class);

        verifyNoInteractions(textStorageService);
    }
}
```

- [ ] **Step 2: Run test to verify it fails (implementation not updated yet)**

```bash
mvn test -Dtest=KnowPostPublishCassandraTest -q 2>&1 | tail -10
```

Expected: compilation error or test failure — `TextWriteException` import missing or logic not yet in `publish()`

- [ ] **Step 3: Inject TextStorageService into KnowPostServiceImpl**

In `KnowPostServiceImpl.java`, add:

```java
@Resource
private TextStorageService textStorageService;

@Resource
private RestTemplate restTemplate;
```

- [ ] **Step 4: Modify publish() with pre-validation + Cassandra write before DB update**

Replace the existing `publish()` method body with:

```java
@Transactional
public void publish(long creatorId, long id) {
    // Pre-validate: check post exists, is a draft, and belongs to this creator
    // This fails fast before any Cassandra write to avoid orphaned data
    KnowPost post = mapper.findById(id);
    if (post == null || post.getCreatorId() != creatorId || !"draft".equals(post.getStatus())) {
        throw new BusinessException(ErrorCode.BAD_REQUEST, "草稿不存在或无权限");
    }

    // Fetch text from MinIO and write to Cassandra — TextWriteException propagates up and blocks publish
    // Note: @Transactional only covers MySQL. If DB CAS below fails after this write,
    // Cassandra will have orphaned data that reconciliation can clean up.
    String body = fetchContent(post.getContentUrl());
    if (body != null && !body.isBlank()) {
        textStorageService.savePostText(id, body);
    }

    // Atomic DB CAS: draft → published (also re-validates ownership and draft status)
    int updated = mapper.publish(id, creatorId);
    if (updated == 0) {
        throw new BusinessException(ErrorCode.BAD_REQUEST, "草稿不存在或无权限");
    }

    try {
        userCounterService.incrementPosts(creatorId, 1);
    } catch (Exception ignored) {}

    try {
        long outId = idGen.nextId();
        String payload = objectMapper.writeValueAsString(
            Map.of("entity", "knowpost", "op", "upsert", "id", id));
        outboxMapper.insert(outId, "knowpost", id, "KnowPostPublished", payload);
    } catch (Exception e) {
        log.warn("Outbox event after publish failed, post {}: {}", id, e.getMessage());
    }

    try {
        ragIndexService.ensureIndexed(id);
    } catch (Exception e) {
        log.warn("Pre-index after publish failed, post {}: {}", id, e.getMessage());
    }
}

private String fetchContent(String url) {
    if (url == null || url.isBlank()) return null;
    try {
        return restTemplate.getForObject(url, String.class);
    } catch (Exception e) {
        log.warn("Failed to fetch content from {}: {}", url, e.getMessage());
        return null;
    }
}
```

Note: `KnowPostMapper` needs a `findById(long id)` method. Check if it exists:

```bash
grep -n "findById" /Users/huangyaokai/zhiguang_be/src/main/java/com/tongji/knowpost/mapper/KnowPostMapper.java
```

If missing, add to mapper interface and XML:

```java
// KnowPostMapper.java
KnowPost findById(@Param("id") long id);
```

```xml
<!-- KnowPostMapper.xml -->
<select id="findById" resultType="com.tongji.knowpost.model.KnowPost">
    SELECT * FROM know_posts WHERE id = #{id}
</select>
```

- [ ] **Step 5: Compile and run all KnowPost tests including the new ones**

```bash
mvn compile -q
mvn test -Dtest="*KnowPost*" -q
```

Expected: `Tests run: N, Failures: 0` including the 3 new `KnowPostPublishCassandraTest` cases

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/tongji/knowpost/service/impl/KnowPostServiceImpl.java
git add src/test/java/com/tongji/knowpost/service/KnowPostPublishCassandraTest.java
git add src/main/java/com/tongji/knowpost/mapper/KnowPostMapper.java  # if modified
git add src/main/resources/mapper/KnowPostMapper.xml  # if modified
git commit -m "feat: write post text to Cassandra on publish (blocks on failure)"
```

---

## Task 9: SearchIndexService — Cassandra-First with MinIO Fallback

**Files:**
- Modify: `src/main/java/com/tongji/search/index/SearchIndexService.java`

**Context:** Currently, `upsertKnowPost()` calls `fetchContentSafe(row.getContentUrl())` at line ~100. We replace this with a Cassandra read, falling back to `fetchContentSafe` for legacy posts.

- [ ] **Step 1: Inject TextStorageService**

In `SearchIndexService.java`, add:

```java
@Resource
private TextStorageService textStorageService;
```

- [ ] **Step 2: Replace content fetch with Cassandra-first logic**

Find the block (around line 100–108):

```java
String body = fetchContentSafe(row.getContentUrl());
if (body == null || body.isBlank()) {
    body = row.getDescription();
}
```

Replace with:

```java
String body = textStorageService
    .getPostText(row.getId(), row.getContentUrl())
    .orElse(null);
if (body == null || body.isBlank()) {
    body = row.getDescription();
}
```

- [ ] **Step 3: Verify compilation and run search tests**

```bash
mvn compile -q
mvn test -Dtest="*Search*" -q
```

Expected: `BUILD SUCCESS`, no test regressions

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/tongji/search/index/SearchIndexService.java
git commit -m "feat: SearchIndexService reads body from Cassandra with MinIO fallback"
```

---

## Task 10: RagIndexService — Cassandra-First with MinIO Fallback

**Files:**
- Modify: `src/main/java/com/tongji/llm/rag/RagIndexService.java`

**Context:** Currently, `ensureIndexed()` calls `fetchContent(row.getContentUrl())` around line 77. We replace this with a Cassandra read with MinIO fallback.

- [ ] **Step 1: Inject TextStorageService**

In `RagIndexService.java`, add:

```java
@Resource
private TextStorageService textStorageService;
```

- [ ] **Step 2: Replace content fetch with Cassandra-first logic**

Find the block (around lines 77–83):

```java
String text = fetchContent(row.getContentUrl());
if (!StringUtils.hasText(text)) {
    log.warn("Post {} content empty", postId);
    return 0;
}
```

Replace with:

```java
String text = textStorageService
    .getPostText(postId, row.getContentUrl())
    .orElse(null);
if (!StringUtils.hasText(text)) {
    log.warn("Post {} content empty or missing from Cassandra and MinIO", postId);
    return 0;
}
```

- [ ] **Step 3: Compile and run RAG-related tests**

```bash
mvn compile -q
mvn test -Dtest="*Rag*" -q
```

Expected: `BUILD SUCCESS`, no regressions

- [ ] **Step 4: Run full test suite**

```bash
mvn test -q
```

Expected: `BUILD SUCCESS` — all tests pass

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/tongji/llm/rag/RagIndexService.java
git commit -m "feat: RagIndexService reads content from Cassandra with MinIO fallback"
```

---

## Task 11: Cassandra 容器启动文档（tasks.md 5.1）

**Files:**
- Modify: `README.md` or create `docs/cassandra.md`

- [ ] **Step 1: Add Cassandra startup instructions**

在项目根目录 `README.md`（或 `docs/cassandra.md`）的"本地开发"章节追加：

```markdown
## Cassandra 本地开发

### 启动

```bash
docker compose up cassandra -d
# 等待约 90 秒，直到健康检查通过
docker compose up cassandra-init
```

验证 schema 已初始化：
```bash
docker exec zhiguang-cassandra cqlsh -e "DESCRIBE TABLES IN zhiguang;"
```

预期输出：
```
Keyspace zhiguang
-----------------
comment_text_by_comment_id  post_text_by_post_id
```

### 停止

```bash
docker compose stop cassandra
```

### 重置数据

```bash
docker compose down cassandra cassandra-init
docker volume rm zhiguang_be_cassandra_data
docker compose up cassandra -d && docker compose up cassandra-init
```

### 连接 cqlsh

```bash
docker exec -it zhiguang-cassandra cqlsh
cqlsh> USE zhiguang;
cqlsh:zhiguang> SELECT post_id, version, updated_at FROM post_text_by_post_id LIMIT 5;
```
```

- [ ] **Step 2: Commit**

```bash
git add README.md  # or docs/cassandra.md
git commit -m "docs: add Cassandra local development setup instructions"
```

---

## Self-Review Checklist

**tasks.md coverage:**
- [x] 1.1 docker-compose Cassandra service (Task 2)
- [x] 1.2 application.yml config (Task 2)
- [x] 1.3 CQL init script (Task 2)
- [x] 2.1 post_text_by_post_id schema (Task 2)
- [x] 2.2 comment_text_by_comment_id schema (Task 2)
- [x] 2.3 text_write_log_by_day — **跳过** (design.md 决策 5: v1 不建)
- [x] 3.1 TextStorageService interface (Task 3)
- [x] 3.2 CassandraTextStorageService (Task 6)
- [x] 3.3 Key rules (post_id / comment_id directly, noted in Task 3 + 4)
- [x] 3.4 Exception types: TextStorageException + TextWriteException + TextReadException (Task 3)
- [x] 4.1 Publish flow → Cassandra (Task 8)
- [x] 4.2 ES/RAG reads from Cassandra with MinIO fallback (Tasks 9–10)
- [x] 4.3 Comment system text capability exposed via TextStorageService (Task 3)
- [x] 5.1 Cassandra 容器启动文档 (Task 11)
- [x] 5.2 TextStorageService unit tests (Task 5)
- [x] 5.3 Integration smoke test (Task 7)

**Bugs fixed vs original plan (round 1):**
- Fixed broken `assertThatThrownBy(() -> { })` empty lambda in Task 8 test
- Fixed `@SpringBootTest` → `@DataCassandraTest` in smoke test (Task 7)
- Added draft status + creatorId pre-validation in `publish()` before Cassandra write (Task 8)
- Added `TextWriteException` and `TextReadException` subtypes (Task 3)
- Added documentation task (Task 11)
- Noted `getPostText` interface deviation from design.md with rationale

**Bugs fixed vs original plan (round 2):**
- Fixed smoke test keyspace timing: moved keyspace creation from `@BeforeAll` (runs after Spring context) to `@DynamicPropertySource` (runs before Spring context); added `schema-action: create-if-not-exists` for test env (Task 7)
- Fixed duplicate Step 4 numbering in Task 8 (now Step 5 / Step 6)
- Removed unused `import java.util.List` in Task 6 implementation
- Clarified Task 2 application.yml instruction: merge into existing `spring:` key, not add duplicate root key

**Type consistency:** `TextWriteException` thrown in Tasks 6/8, caught in Task 5 unit tests and Task 8 tests — consistent throughout.
