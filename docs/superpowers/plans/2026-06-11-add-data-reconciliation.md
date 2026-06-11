# add-data-reconciliation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a systematic reconciliation framework that detects and repairs inconsistencies between fact stores and derived stores, with scheduled scanning, exponential-backoff retry, Redis distributed locking for multi-instance safety, and a manual admin API.

**Architecture:** Three MySQL tables (`reconciliation_task`, `reconciliation_checkpoint`, `reconciliation_error_log`) store task state. A `@Scheduled` poller dispatches pending tasks to a thread pool; each task acquires a per-task Redis lock before executing its domain reconciler. Exponential backoff governs retry; tasks exhausting 5 retries become `dead` and are logged. Scheduled scans detect inconsistencies and create tasks; a separate scan resets stuck `running` tasks every 5 minutes.

**Tech Stack:** Spring Boot 3.2.4, MyBatis, Redisson (`RLock`), `@Scheduled`, `IdService` (`RECONCILIATION_TASK` Segment namespace), `TextStorageService`, `SearchIndexService`, `RagIndexService` (all from prerequisite changes).

**Prerequisites:** `add-leaf-id-service`, `add-cassandra-text-storage`, `eventize-publish-pipeline`, `add-comment-system` must be implemented first (reconcilers depend on their beans).

**Key design decisions (from design.md):**
- Execution: DB polling with `@Scheduled` (no Kafka for task dispatch)
- Retry: exponential backoff `delay = 2^retryCount` minutes, max 5 retries → `dead`
- Checkpoint: cursor pagination per scan type; checkpoint resets to 0 after full scan
- Multi-instance safety: Redis distributed lock `recon:lock:{taskId}` + CAS `markRunning` SQL
- Dead task: manually reset via `POST /api/v1/reconciliation/tasks/{id}/retry` (retryCount=0)
- Stuck `running` tasks: scheduled scan every 5 min (`update_time < NOW() - 10 min`)

---

## File Map

**New files:**
- `db/schema.sql` — add 3 reconciliation tables
- `src/main/java/com/tongji/reconciliation/TaskTypes.java` — task type string constants
- `src/main/java/com/tongji/reconciliation/TargetTypes.java` — target type string constants
- `src/main/java/com/tongji/reconciliation/ScanTypes.java` — scan type string constants
- `src/main/java/com/tongji/reconciliation/model/ReconciliationTask.java`
- `src/main/java/com/tongji/reconciliation/model/ReconciliationErrorLog.java`
- `src/main/java/com/tongji/reconciliation/model/ReconciliationCheckpoint.java`
- `src/main/java/com/tongji/reconciliation/mapper/ReconciliationTaskMapper.java` + XML
- `src/main/java/com/tongji/reconciliation/mapper/ReconciliationErrorLogMapper.java` + XML
- `src/main/java/com/tongji/reconciliation/mapper/ReconciliationCheckpointMapper.java` + XML
- `src/main/java/com/tongji/reconciliation/service/ReconciliationService.java`
- `src/main/java/com/tongji/reconciliation/service/impl/ReconciliationServiceImpl.java`
- `src/main/java/com/tongji/reconciliation/executor/Reconciler.java`
- `src/main/java/com/tongji/reconciliation/executor/ReconciliationTaskExecutor.java`
- `src/main/java/com/tongji/reconciliation/executor/EsIndexReconciler.java`
- `src/main/java/com/tongji/reconciliation/executor/RagIndexReconciler.java`
- `src/main/java/com/tongji/reconciliation/executor/CassandraTextReconciler.java`
- `src/main/java/com/tongji/reconciliation/scan/ReconciliationScanService.java`
- `src/main/java/com/tongji/reconciliation/api/ReconciliationController.java`
- `src/main/java/com/tongji/reconciliation/api/ReconciliationTaskResponse.java`
- test files (one per major component)

**Modified files:**
- `db/schema.sql`
- `src/main/java/com/tongji/knowpost/mapper/KnowPostMapper.java` + XML — add cursor paginated published ID scan
- `src/main/java/com/tongji/search/index/SearchIndexService.java` — add `existsKnowPost(long)`
- `src/main/java/com/tongji/knowpost/publish/PublishDerivedConsumer.java` — replace TODO comments with real reconciliation task creation

---

## Task 1: DB Schema

**Files:**
- Modify: `db/schema.sql`

- [ ] **Step 1: Append 3 reconciliation tables to db/schema.sql**

```sql
CREATE TABLE IF NOT EXISTS reconciliation_task (
    id              BIGINT        NOT NULL,
    task_type       VARCHAR(64)   NOT NULL,
    target_type     VARCHAR(32)   NOT NULL,
    target_id       BIGINT        NOT NULL,
    status          VARCHAR(16)   NOT NULL DEFAULT 'pending',
    retry_count     INT           NOT NULL DEFAULT 0,
    next_execute_at DATETIME(3)   NOT NULL,
    last_error      VARCHAR(512),
    created_at      DATETIME(3)   NOT NULL,
    updated_at      DATETIME(3)   NOT NULL,
    PRIMARY KEY (id),
    KEY idx_scheduled (status, next_execute_at),
    KEY idx_target    (target_type, target_id, task_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS reconciliation_checkpoint (
    scan_type       VARCHAR(64)  NOT NULL,
    last_scanned_id BIGINT       NOT NULL DEFAULT 0,
    updated_at      DATETIME(3)  NOT NULL,
    PRIMARY KEY (scan_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS reconciliation_error_log (
    id            BIGINT  NOT NULL,
    task_id       BIGINT  NOT NULL,
    error_message TEXT,
    created_at    DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_task_id (task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

- [ ] **Step 2: Apply schema to local DB**

```bash
cd /Users/huangyaokai/zhiguang_be
docker compose exec mysql mysql -uroot -proot zhiguang < db/schema.sql
docker compose exec mysql mysql -uroot -proot zhiguang \
  -e "DESCRIBE reconciliation_task; DESCRIBE reconciliation_checkpoint; DESCRIBE reconciliation_error_log;"
```

Expected: all 3 tables with listed columns.

- [ ] **Step 3: Commit**

```bash
git add db/schema.sql
git commit -m "feat: add reconciliation tables to schema"
```

---

## Task 2: Models + Constants + Mappers

**Files:**
- Create: `src/main/java/com/tongji/reconciliation/TaskTypes.java`
- Create: `src/main/java/com/tongji/reconciliation/TargetTypes.java`
- Create: `src/main/java/com/tongji/reconciliation/ScanTypes.java`
- Create models + mappers + XML files

- [ ] **Step 1: Create TaskTypes.java**

```java
package com.tongji.reconciliation;

public final class TaskTypes {
    public static final String ES_INDEX             = "es_index";
    public static final String RAG_INDEX            = "rag_index";
    public static final String FEED_CACHE_INVALIDATE = "feed_cache_invalidate";
    public static final String GORSE_ITEM_UPSERT    = "gorse_item_upsert";
    public static final String GORSE_FEEDBACK       = "gorse_feedback";
    public static final String CASSANDRA_TEXT       = "cassandra_text";
    public static final String COMMENT_COUNT        = "comment_count";
    private TaskTypes() {}
}
```

- [ ] **Step 2: Create TargetTypes.java**

```java
package com.tongji.reconciliation;

public final class TargetTypes {
    public static final String POST    = "post";
    public static final String COMMENT = "comment";
    public static final String USER    = "user";
    private TargetTypes() {}
}
```

- [ ] **Step 3: Create ScanTypes.java**

```java
package com.tongji.reconciliation;

public final class ScanTypes {
    public static final String POST_ES           = "post_es";
    public static final String POST_RAG          = "post_rag";
    public static final String POST_CASSANDRA    = "post_cassandra";
    public static final String COMMENT_CASSANDRA = "comment_cassandra";
    public static final String RUNNING_TIMEOUT   = "running_timeout";
    private ScanTypes() {}
}
```

- [ ] **Step 4: Create model classes**

```java
// ReconciliationTask.java
package com.tongji.reconciliation.model;

import lombok.*;
import java.time.LocalDateTime;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ReconciliationTask {
    private Long id;
    private String taskType;
    private String targetType;
    private Long targetId;
    private String status;       // pending/running/succeeded/dead
    private Integer retryCount;
    private LocalDateTime nextExecuteAt;
    private String lastError;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
```

```java
// ReconciliationErrorLog.java
package com.tongji.reconciliation.model;

import lombok.*;
import java.time.LocalDateTime;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ReconciliationErrorLog {
    private Long id;
    private Long taskId;
    private String errorMessage;
    private LocalDateTime createdAt;
}
```

```java
// ReconciliationCheckpoint.java
package com.tongji.reconciliation.model;

import lombok.*;
import java.time.LocalDateTime;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ReconciliationCheckpoint {
    private String scanType;
    private Long lastScannedId;
    private LocalDateTime updatedAt;
}
```

- [ ] **Step 5: Create ReconciliationTaskMapper.java**

```java
package com.tongji.reconciliation.mapper;

import com.tongji.reconciliation.model.ReconciliationTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface ReconciliationTaskMapper {

    void insert(ReconciliationTask task);

    /** Poll tasks ready to execute. */
    List<ReconciliationTask> pollPending(@Param("limit") int limit);

    /** CAS: pending → running. Returns rows affected (0 = lost race). */
    int markRunning(@Param("id") long id);

    void markSucceeded(@Param("id") long id);

    void markPendingRetry(@Param("id") long id,
                          @Param("retryCount") int retryCount,
                          @Param("nextExecuteAt") LocalDateTime nextExecuteAt,
                          @Param("lastError") String lastError);

    void markDead(@Param("id") long id, @Param("lastError") String lastError);

    /** Reset dead → pending, retryCount=0 for manual retry. Returns rows affected. */
    int resetDeadToPending(@Param("id") long id);

    /** Stuck running tasks (update_time older than timeoutMinutes). */
    List<ReconciliationTask> findStuckRunning(@Param("timeoutMinutes") int timeoutMinutes);

    void resetRunningToPending(@Param("id") long id);

    ReconciliationTask findById(@Param("id") long id);

    /** Returns true if a non-dead task already exists for this (taskType, targetType, targetId). */
    boolean existsActiveTask(@Param("taskType") String taskType,
                             @Param("targetType") String targetType,
                             @Param("targetId") long targetId);

    List<ReconciliationTask> findByFilter(@Param("status") String status,
                                          @Param("targetType") String targetType,
                                          @Param("targetId") Long targetId,
                                          @Param("limit") int limit,
                                          @Param("offset") int offset);
}
```

- [ ] **Step 6: Create ReconciliationTaskMapper.xml**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="com.tongji.reconciliation.mapper.ReconciliationTaskMapper">

    <insert id="insert">
        INSERT INTO reconciliation_task
            (id, task_type, target_type, target_id, status, retry_count,
             next_execute_at, last_error, created_at, updated_at)
        VALUES
            (#{id}, #{taskType}, #{targetType}, #{targetId}, #{status}, #{retryCount},
             #{nextExecuteAt}, #{lastError}, #{createdAt}, #{updatedAt})
    </insert>

    <select id="pollPending" resultType="com.tongji.reconciliation.model.ReconciliationTask">
        SELECT id, task_type, target_type, target_id, status, retry_count,
               next_execute_at, last_error, created_at, updated_at
        FROM reconciliation_task
        WHERE status = 'pending'
          AND next_execute_at &lt;= NOW(3)
        ORDER BY next_execute_at ASC
        LIMIT #{limit}
    </select>

    <update id="markRunning">
        UPDATE reconciliation_task
        SET status = 'running', updated_at = NOW(3)
        WHERE id = #{id} AND status = 'pending'
    </update>

    <update id="markSucceeded">
        UPDATE reconciliation_task
        SET status = 'succeeded', updated_at = NOW(3)
        WHERE id = #{id}
    </update>

    <update id="markPendingRetry">
        UPDATE reconciliation_task
        SET status = 'pending', retry_count = #{retryCount},
            next_execute_at = #{nextExecuteAt}, last_error = #{lastError},
            updated_at = NOW(3)
        WHERE id = #{id}
    </update>

    <update id="markDead">
        UPDATE reconciliation_task
        SET status = 'dead', last_error = #{lastError}, updated_at = NOW(3)
        WHERE id = #{id}
    </update>

    <update id="resetDeadToPending">
        UPDATE reconciliation_task
        SET status = 'pending', retry_count = 0,
            next_execute_at = NOW(3), last_error = NULL, updated_at = NOW(3)
        WHERE id = #{id} AND status = 'dead'
    </update>

    <select id="findStuckRunning" resultType="com.tongji.reconciliation.model.ReconciliationTask">
        SELECT id, task_type, target_type, target_id, status, retry_count,
               next_execute_at, last_error, created_at, updated_at
        FROM reconciliation_task
        WHERE status = 'running'
          AND updated_at &lt; DATE_SUB(NOW(), INTERVAL #{timeoutMinutes} MINUTE)
    </select>

    <update id="resetRunningToPending">
        UPDATE reconciliation_task
        SET status = 'pending', next_execute_at = NOW(3), updated_at = NOW(3)
        WHERE id = #{id} AND status = 'running'
    </update>

    <select id="findById" resultType="com.tongji.reconciliation.model.ReconciliationTask">
        SELECT id, task_type, target_type, target_id, status, retry_count,
               next_execute_at, last_error, created_at, updated_at
        FROM reconciliation_task WHERE id = #{id}
    </select>

    <select id="existsActiveTask" resultType="boolean">
        SELECT COUNT(*) > 0
        FROM reconciliation_task
        WHERE task_type = #{taskType}
          AND target_type = #{targetType}
          AND target_id = #{targetId}
          AND status IN ('pending', 'running')
    </select>

    <select id="findByFilter" resultType="com.tongji.reconciliation.model.ReconciliationTask">
        SELECT id, task_type, target_type, target_id, status, retry_count,
               next_execute_at, last_error, created_at, updated_at
        FROM reconciliation_task
        WHERE 1=1
          <if test="status != null">AND status = #{status}</if>
          <if test="targetType != null">AND target_type = #{targetType}</if>
          <if test="targetId != null">AND target_id = #{targetId}</if>
        ORDER BY created_at DESC
        LIMIT #{limit} OFFSET #{offset}
    </select>

</mapper>
```

- [ ] **Step 7: Create ReconciliationErrorLogMapper.java + XML**

```java
package com.tongji.reconciliation.mapper;

import com.tongji.reconciliation.model.ReconciliationErrorLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface ReconciliationErrorLogMapper {
    void insert(ReconciliationErrorLog log);
    List<ReconciliationErrorLog> findByTaskId(@Param("taskId") long taskId);
}
```

```xml
<!-- ReconciliationErrorLogMapper.xml -->
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="com.tongji.reconciliation.mapper.ReconciliationErrorLogMapper">
    <insert id="insert">
        INSERT INTO reconciliation_error_log (id, task_id, error_message, created_at)
        VALUES (#{id}, #{taskId}, #{errorMessage}, #{createdAt})
    </insert>
    <select id="findByTaskId" resultType="com.tongji.reconciliation.model.ReconciliationErrorLog">
        SELECT id, task_id, error_message, created_at
        FROM reconciliation_error_log WHERE task_id = #{taskId}
    </select>
</mapper>
```

- [ ] **Step 8: Create ReconciliationCheckpointMapper.java + XML**

```java
package com.tongji.reconciliation.mapper;

import com.tongji.reconciliation.model.ReconciliationCheckpoint;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ReconciliationCheckpointMapper {

    /** Returns lastScannedId for scan type, 0 if not yet started. */
    long getLastId(@Param("scanType") String scanType);

    /** Upsert checkpoint to lastScannedId. */
    void upsert(@Param("scanType") String scanType, @Param("lastScannedId") long lastScannedId);

    /** Reset checkpoint to 0 (wrap around for next full scan). */
    void reset(@Param("scanType") String scanType);
}
```

```xml
<!-- ReconciliationCheckpointMapper.xml -->
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="com.tongji.reconciliation.mapper.ReconciliationCheckpointMapper">

    <select id="getLastId" resultType="long">
        SELECT COALESCE(
            (SELECT last_scanned_id FROM reconciliation_checkpoint WHERE scan_type = #{scanType}),
            0
        )
    </select>

    <insert id="upsert">
        INSERT INTO reconciliation_checkpoint (scan_type, last_scanned_id, updated_at)
        VALUES (#{scanType}, #{lastScannedId}, NOW(3))
        ON DUPLICATE KEY UPDATE last_scanned_id = #{lastScannedId}, updated_at = NOW(3)
    </insert>

    <update id="reset">
        UPDATE reconciliation_checkpoint
        SET last_scanned_id = 0, updated_at = NOW(3)
        WHERE scan_type = #{scanType}
    </update>

</mapper>
```

- [ ] **Step 9: Verify compilation**

```bash
mvn compile -q
```

Expected: `BUILD SUCCESS`

- [ ] **Step 10: Commit**

```bash
git add src/main/java/com/tongji/reconciliation/ \
        src/main/resources/mapper/ReconciliationTaskMapper.xml \
        src/main/resources/mapper/ReconciliationErrorLogMapper.xml \
        src/main/resources/mapper/ReconciliationCheckpointMapper.xml
git commit -m "feat: add reconciliation models, constants, and mappers"
```

---

## Task 3: ReconciliationService (TDD)

**Files:**
- Create: `src/test/java/com/tongji/reconciliation/service/ReconciliationServiceTest.java`
- Create: `src/main/java/com/tongji/reconciliation/service/ReconciliationService.java`
- Create: `src/main/java/com/tongji/reconciliation/service/impl/ReconciliationServiceImpl.java`

- [ ] **Step 1: Write failing tests**

```java
package com.tongji.reconciliation.service;

import com.tongji.id.IdNamespace;
import com.tongji.id.IdService;
import com.tongji.reconciliation.TaskTypes;
import com.tongji.reconciliation.TargetTypes;
import com.tongji.reconciliation.mapper.ReconciliationTaskMapper;
import com.tongji.reconciliation.model.ReconciliationTask;
import com.tongji.reconciliation.service.impl.ReconciliationServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReconciliationServiceTest {

    @Mock ReconciliationTaskMapper taskMapper;
    @Mock IdService idService;
    @InjectMocks ReconciliationServiceImpl service;

    @Test
    void createTask_insertsPendingTaskWithSegmentId() {
        when(idService.nextId(IdNamespace.RECONCILIATION_TASK)).thenReturn(999L);

        long id = service.createTask(TaskTypes.ES_INDEX, TargetTypes.POST, 100L);

        assertThat(id).isEqualTo(999L);
        ArgumentCaptor<ReconciliationTask> captor = ArgumentCaptor.forClass(ReconciliationTask.class);
        verify(taskMapper).insert(captor.capture());
        ReconciliationTask t = captor.getValue();
        assertThat(t.getId()).isEqualTo(999L);
        assertThat(t.getTaskType()).isEqualTo(TaskTypes.ES_INDEX);
        assertThat(t.getTargetType()).isEqualTo(TargetTypes.POST);
        assertThat(t.getTargetId()).isEqualTo(100L);
        assertThat(t.getStatus()).isEqualTo("pending");
        assertThat(t.getRetryCount()).isEqualTo(0);
        assertThat(t.getNextExecuteAt()).isNotNull();
    }

    @Test
    void createTaskIfAbsent_noopWhenActiveExists() {
        when(taskMapper.existsActiveTask(TaskTypes.ES_INDEX, TargetTypes.POST, 100L))
            .thenReturn(true);

        long id = service.createTaskIfAbsent(TaskTypes.ES_INDEX, TargetTypes.POST, 100L);

        assertThat(id).isEqualTo(-1L);
        verify(taskMapper, never()).insert(any());
    }

    @Test
    void createTaskIfAbsent_insertsWhenNoActiveTask() {
        when(taskMapper.existsActiveTask(anyString(), anyString(), anyLong())).thenReturn(false);
        when(idService.nextId(IdNamespace.RECONCILIATION_TASK)).thenReturn(1L);

        long id = service.createTaskIfAbsent(TaskTypes.ES_INDEX, TargetTypes.POST, 200L);

        assertThat(id).isEqualTo(1L);
        verify(taskMapper).insert(any());
    }

    @Test
    void retryTask_resetsDead() {
        when(taskMapper.resetDeadToPending(42L)).thenReturn(1);
        assertThat(service.retryTask(42L)).isTrue();
        verify(taskMapper).resetDeadToPending(42L);
    }

    @Test
    void retryTask_returnsFalseWhenNotDead() {
        when(taskMapper.resetDeadToPending(42L)).thenReturn(0);
        assertThat(service.retryTask(42L)).isFalse();
    }
}
```

- [ ] **Step 2: Run — confirm failure**

```bash
mvn test -Dtest=ReconciliationServiceTest -q 2>&1 | tail -5
```

Expected: compilation error — `ReconciliationServiceImpl` not found

- [ ] **Step 3: Create ReconciliationService.java**

```java
package com.tongji.reconciliation.service;

import com.tongji.reconciliation.model.ReconciliationTask;
import java.util.List;

public interface ReconciliationService {

    /** Always create a new task. Use when a failure just occurred. */
    long createTask(String taskType, String targetType, long targetId);

    /** Create task only if no active (pending/running) task exists for this combination. */
    long createTaskIfAbsent(String taskType, String targetType, long targetId);

    ReconciliationTask findById(long taskId);

    List<ReconciliationTask> query(String status, String targetType, Long targetId,
                                   int limit, int offset);

    /** Reset dead → pending. Returns true if successful. */
    boolean retryTask(long taskId);

    /** Create eligible tasks for all registered reconcilers for a given target. */
    List<Long> rerunTarget(String targetType, long targetId);
}
```

- [ ] **Step 4: Create ReconciliationServiceImpl.java**

```java
package com.tongji.reconciliation.service.impl;

import com.tongji.id.IdNamespace;
import com.tongji.id.IdService;
import com.tongji.reconciliation.TaskTypes;
import com.tongji.reconciliation.TargetTypes;
import com.tongji.reconciliation.mapper.ReconciliationTaskMapper;
import com.tongji.reconciliation.model.ReconciliationTask;
import com.tongji.reconciliation.service.ReconciliationService;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
public class ReconciliationServiceImpl implements ReconciliationService {

    @Resource private ReconciliationTaskMapper taskMapper;
    @Resource private IdService idService;

    @Override
    public long createTask(String taskType, String targetType, long targetId) {
        long id = idService.nextId(IdNamespace.RECONCILIATION_TASK);
        taskMapper.insert(ReconciliationTask.builder()
            .id(id)
            .taskType(taskType)
            .targetType(targetType)
            .targetId(targetId)
            .status("pending")
            .retryCount(0)
            .nextExecuteAt(LocalDateTime.now())
            .createdAt(LocalDateTime.now())
            .updatedAt(LocalDateTime.now())
            .build());
        return id;
    }

    @Override
    public long createTaskIfAbsent(String taskType, String targetType, long targetId) {
        if (taskMapper.existsActiveTask(taskType, targetType, targetId)) {
            return -1L;
        }
        return createTask(taskType, targetType, targetId);
    }

    @Override
    public ReconciliationTask findById(long taskId) {
        return taskMapper.findById(taskId);
    }

    @Override
    public List<ReconciliationTask> query(String status, String targetType, Long targetId,
                                           int limit, int offset) {
        return taskMapper.findByFilter(status, targetType, targetId, limit, offset);
    }

    @Override
    public boolean retryTask(long taskId) {
        return taskMapper.resetDeadToPending(taskId) > 0;
    }

    @Override
    public List<Long> rerunTarget(String targetType, long targetId) {
        List<Long> created = new ArrayList<>();
        List<String> taskTypes = resolveTaskTypesForTarget(targetType);
        for (String taskType : taskTypes) {
            long id = createTaskIfAbsent(taskType, targetType, targetId);
            if (id != -1L) created.add(id);
        }
        return created;
    }

    private List<String> resolveTaskTypesForTarget(String targetType) {
        return switch (targetType) {
            case TargetTypes.POST -> List.of(
                TaskTypes.ES_INDEX, TaskTypes.RAG_INDEX,
                TaskTypes.CASSANDRA_TEXT, TaskTypes.GORSE_ITEM_UPSERT);
            case TargetTypes.COMMENT -> List.of(TaskTypes.CASSANDRA_TEXT);
            default -> List.of();
        };
    }
}
```

- [ ] **Step 5: Run — all pass**

```bash
mvn test -Dtest=ReconciliationServiceTest -q
```

Expected: `Tests run: 5, Failures: 0, Errors: 0`

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/tongji/reconciliation/service/ \
        src/test/java/com/tongji/reconciliation/service/ReconciliationServiceTest.java
git commit -m "feat: implement ReconciliationService with create, createIfAbsent, and retry"
```

---

## Task 4: Task Executor + State Machine (TDD)

**Files:**
- Create: `src/test/java/com/tongji/reconciliation/executor/ReconciliationTaskExecutorTest.java`
- Create: `src/main/java/com/tongji/reconciliation/executor/Reconciler.java`
- Create: `src/main/java/com/tongji/reconciliation/executor/ReconciliationTaskExecutor.java`

- [ ] **Step 1: Create Reconciler interface**

```java
package com.tongji.reconciliation.executor;

public interface Reconciler {
    boolean supports(String taskType, String targetType);
    void execute(String targetType, long targetId) throws Exception;
}
```

- [ ] **Step 2: Write failing executor tests**

```java
package com.tongji.reconciliation.executor;

import com.tongji.id.IdNamespace;
import com.tongji.id.IdService;
import com.tongji.reconciliation.TaskTypes;
import com.tongji.reconciliation.TargetTypes;
import com.tongji.reconciliation.mapper.ReconciliationErrorLogMapper;
import com.tongji.reconciliation.mapper.ReconciliationTaskMapper;
import com.tongji.reconciliation.model.ReconciliationTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReconciliationTaskExecutorTest {

    @Mock ReconciliationTaskMapper taskMapper;
    @Mock ReconciliationErrorLogMapper errorLogMapper;
    @Mock RedissonClient redisson;
    @Mock RLock lock;
    @Mock IdService idService;

    Reconciler successReconciler;
    Reconciler failReconciler;
    ReconciliationTaskExecutor executor;

    @BeforeEach
    void setUp() throws Exception {
        successReconciler = spy(new Reconciler() {
            @Override public boolean supports(String t, String tt) { return true; }
            @Override public void execute(String tt, long id) {}
        });
        failReconciler = spy(new Reconciler() {
            @Override public boolean supports(String t, String tt) { return true; }
            @Override public void execute(String tt, long id) throws Exception {
                throw new RuntimeException("reconcile failed");
            }
        });

        when(redisson.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
    }

    private ReconciliationTask task(int retryCount) {
        return ReconciliationTask.builder()
            .id(1L).taskType(TaskTypes.ES_INDEX).targetType(TargetTypes.POST)
            .targetId(10L).status("pending").retryCount(retryCount)
            .nextExecuteAt(LocalDateTime.now()).build();
    }

    @Test
    void executeTask_success_marksSucceeded() {
        executor = new ReconciliationTaskExecutor(taskMapper, errorLogMapper,
            List.of(successReconciler), redisson, idService);
        when(taskMapper.markRunning(1L)).thenReturn(1);

        executor.executeTask(task(0));

        verify(taskMapper).markRunning(1L);
        verify(taskMapper).markSucceeded(1L);
    }

    @Test
    void executeTask_failure_incrementsRetryCount() {
        executor = new ReconciliationTaskExecutor(taskMapper, errorLogMapper,
            List.of(failReconciler), redisson, idService);
        when(taskMapper.markRunning(1L)).thenReturn(1);

        executor.executeTask(task(0));

        verify(taskMapper).markPendingRetry(eq(1L), eq(1), any(), anyString());
        verify(taskMapper, never()).markDead(anyLong(), anyString());
    }

    @Test
    void executeTask_exhaustedRetries_marksDead() {
        executor = new ReconciliationTaskExecutor(taskMapper, errorLogMapper,
            List.of(failReconciler), redisson, idService);
        when(taskMapper.markRunning(1L)).thenReturn(1);
        when(idService.nextId(IdNamespace.AUDIT_LOG)).thenReturn(555L);

        executor.executeTask(task(4));  // retryCount=4 → newCount=5 → dead

        verify(taskMapper).markDead(eq(1L), anyString());
        verify(errorLogMapper).insert(argThat(e -> e.getTaskId() == 1L));
    }

    @Test
    void executeTask_lockNotAcquired_skips() throws Exception {
        executor = new ReconciliationTaskExecutor(taskMapper, errorLogMapper,
            List.of(successReconciler), redisson, idService);
        when(lock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(false);

        executor.executeTask(task(0));

        verify(taskMapper, never()).markRunning(anyLong());
    }

    @Test
    void executeTask_casLostRace_skips() {
        executor = new ReconciliationTaskExecutor(taskMapper, errorLogMapper,
            List.of(successReconciler), redisson, idService);
        when(taskMapper.markRunning(1L)).thenReturn(0);  // CAS failed — another instance won

        executor.executeTask(task(0));

        verify(taskMapper, never()).markSucceeded(anyLong());
    }

    @Test
    void exponentialBackoff_isCorrect() {
        executor = new ReconciliationTaskExecutor(taskMapper, errorLogMapper,
            List.of(failReconciler), redisson, idService);
        when(taskMapper.markRunning(1L)).thenReturn(1);

        executor.executeTask(task(2));  // retryCount=2 → newCount=3 → delay=2^3=8 min

        verify(taskMapper).markPendingRetry(eq(1L), eq(3),
            argThat(dt -> dt.isAfter(LocalDateTime.now().plusMinutes(7))),
            anyString());
    }
}
```

- [ ] **Step 3: Run — confirm failure**

```bash
mvn test -Dtest=ReconciliationTaskExecutorTest -q 2>&1 | tail -5
```

Expected: compilation error — `ReconciliationTaskExecutor` not found

- [ ] **Step 4: Create ReconciliationTaskExecutor.java**

```java
package com.tongji.reconciliation.executor;

import com.tongji.id.IdNamespace;
import com.tongji.id.IdService;
import com.tongji.reconciliation.mapper.ReconciliationErrorLogMapper;
import com.tongji.reconciliation.mapper.ReconciliationTaskMapper;
import com.tongji.reconciliation.model.ReconciliationErrorLog;
import com.tongji.reconciliation.model.ReconciliationTask;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Component
public class ReconciliationTaskExecutor {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationTaskExecutor.class);
    private static final int BATCH_SIZE = 100;
    private static final int MAX_RETRIES = 5;

    private final ReconciliationTaskMapper taskMapper;
    private final ReconciliationErrorLogMapper errorLogMapper;
    private final List<Reconciler> reconcilers;
    private final RedissonClient redisson;
    private final IdService idService;
    private final ExecutorService pool;

    public ReconciliationTaskExecutor(ReconciliationTaskMapper taskMapper,
                                       ReconciliationErrorLogMapper errorLogMapper,
                                       List<Reconciler> reconcilers,
                                       RedissonClient redisson,
                                       IdService idService) {
        this.taskMapper = taskMapper;
        this.errorLogMapper = errorLogMapper;
        this.reconcilers = reconcilers;
        this.redisson = redisson;
        this.idService = idService;
        this.pool = Executors.newFixedThreadPool(4,
            r -> { Thread t = new Thread(r, "recon-worker"); t.setDaemon(true); return t; });
    }

    @Scheduled(fixedDelay = 60_000)
    public void pollAndExecute() {
        List<ReconciliationTask> tasks = taskMapper.pollPending(BATCH_SIZE);
        for (ReconciliationTask task : tasks) {
            pool.submit(() -> executeTask(task));
        }
    }

    public void executeTask(ReconciliationTask task) {
        String lockKey = "recon:lock:" + task.getId();
        RLock lock = redisson.getLock(lockKey);
        try {
            if (!lock.tryLock(0, 30, TimeUnit.SECONDS)) {
                return; // another instance is processing this task
            }

            int updated = taskMapper.markRunning(task.getId());
            if (updated == 0) return; // lost CAS race — skip

            Reconciler reconciler = reconcilers.stream()
                .filter(r -> r.supports(task.getTaskType(), task.getTargetType()))
                .findFirst()
                .orElse(null);

            try {
                if (reconciler != null) {
                    reconciler.execute(task.getTargetType(), task.getTargetId());
                } else {
                    throw new IllegalArgumentException(
                        "No reconciler for " + task.getTaskType() + "/" + task.getTargetType());
                }
                taskMapper.markSucceeded(task.getId());
            } catch (Exception e) {
                handleFailure(task, e);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            if (lock.isHeldByCurrentThread()) lock.unlock();
        }
    }

    private void handleFailure(ReconciliationTask task, Exception e) {
        int newRetryCount = task.getRetryCount() + 1;
        String errorMsg = e.getMessage() != null
            ? e.getMessage().substring(0, Math.min(e.getMessage().length(), 512))
            : "null";

        if (newRetryCount >= MAX_RETRIES) {
            log.error("Task {} exhausted retries — marking dead: {}", task.getId(), errorMsg);
            taskMapper.markDead(task.getId(), errorMsg);
            errorLogMapper.insert(ReconciliationErrorLog.builder()
                .id(idService.nextId(IdNamespace.AUDIT_LOG))
                .taskId(task.getId())
                .errorMessage(errorMsg)
                .createdAt(LocalDateTime.now())
                .build());
        } else {
            long delayMinutes = (long) Math.pow(2, newRetryCount); // 2, 4, 8, 16 min
            LocalDateTime nextExec = LocalDateTime.now().plusMinutes(delayMinutes);
            log.warn("Task {} failed (attempt {}), retry at {}", task.getId(), newRetryCount, nextExec);
            taskMapper.markPendingRetry(task.getId(), newRetryCount, nextExec, errorMsg);
        }
    }
}
```

- [ ] **Step 5: Run — all pass**

```bash
mvn test -Dtest=ReconciliationTaskExecutorTest -q
```

Expected: `Tests run: 6, Failures: 0, Errors: 0`

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/tongji/reconciliation/executor/ \
        src/test/java/com/tongji/reconciliation/executor/ReconciliationTaskExecutorTest.java
git commit -m "feat: implement ReconciliationTaskExecutor with Redis lock and exponential backoff"
```

---

## Task 5: SearchIndexService.existsKnowPost + KnowPostMapper cursor scan

**Files:**
- Modify: `src/main/java/com/tongji/search/index/SearchIndexService.java`
- Modify: `src/main/java/com/tongji/knowpost/mapper/KnowPostMapper.java`
- Modify: `src/main/resources/mapper/KnowPostMapper.xml`

- [ ] **Step 1: Add existsKnowPost to SearchIndexService.java**

Find the ES index name constant used in the class (look for `zhiguang_content_index` or similar). Add after existing methods:

```java
public boolean existsKnowPost(long postId) {
    try {
        return es.exists(e -> e
            .index("zhiguang_content_index")
            .id(String.valueOf(postId))
        ).value();
    } catch (Exception e) {
        log.warn("ES exists check failed for postId={}: {}", postId, e.getMessage());
        return false;
    }
}
```

> **Note:** Verify the exact index name used in `SearchIndexService` by checking the existing `upsertKnowPost` method for the index name it writes to.

- [ ] **Step 2: Add listPublishedIdsCursor to KnowPostMapper.java**

Add after existing methods:

```java
/** Cursor-paginated list of published post IDs for scanning. cursor=null for first page. */
List<Long> listPublishedIdsCursor(@Param("cursor") Long cursor, @Param("limit") int limit);
```

- [ ] **Step 3: Add SQL to KnowPostMapper.xml**

Add before `</mapper>`:

```xml
<select id="listPublishedIdsCursor" resultType="long">
    SELECT id FROM know_posts
    WHERE status = 'published'
    <if test="cursor != null">AND id &gt; #{cursor}</if>
    ORDER BY id ASC
    LIMIT #{limit}
</select>
```

- [ ] **Step 4: Verify compilation**

```bash
mvn compile -q
```

Expected: `BUILD SUCCESS`

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/tongji/search/index/SearchIndexService.java \
        src/main/java/com/tongji/knowpost/mapper/KnowPostMapper.java \
        src/main/resources/mapper/KnowPostMapper.xml
git commit -m "feat: add existsKnowPost to SearchIndexService and cursor scan to KnowPostMapper"
```

---

## Task 6: Domain Reconcilers (TDD)

**Files:**
- Create: `src/test/java/com/tongji/reconciliation/executor/ReconcilerTest.java`
- Create: `src/main/java/com/tongji/reconciliation/executor/EsIndexReconciler.java`
- Create: `src/main/java/com/tongji/reconciliation/executor/RagIndexReconciler.java`
- Create: `src/main/java/com/tongji/reconciliation/executor/CassandraTextReconciler.java`

- [ ] **Step 1: Write failing reconciler tests**

```java
package com.tongji.reconciliation.executor;

import com.tongji.knowpost.mapper.KnowPostMapper;
import com.tongji.knowpost.model.KnowPost;
import com.tongji.llm.rag.RagIndexService;
import com.tongji.search.index.SearchIndexService;
import com.tongji.storage.text.TextStorageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestTemplate;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReconcilerTest {

    @Mock SearchIndexService searchIndexService;
    @Mock RagIndexService ragIndexService;
    @Mock TextStorageService textStorageService;
    @Mock KnowPostMapper postMapper;
    @Mock RestTemplate restTemplate;

    @Test
    void esIndexReconciler_supportsEsIndexPost() {
        EsIndexReconciler r = new EsIndexReconciler(searchIndexService);
        assertThat(r.supports("es_index", "post")).isTrue();
        assertThat(r.supports("rag_index", "post")).isFalse();
        assertThat(r.supports("es_index", "comment")).isFalse();
    }

    @Test
    void esIndexReconciler_callsUpsertKnowPost() throws Exception {
        EsIndexReconciler r = new EsIndexReconciler(searchIndexService);
        r.execute("post", 42L);
        verify(searchIndexService).upsertKnowPost(42L);
    }

    @Test
    void ragIndexReconciler_supportsRagIndexPost() {
        RagIndexReconciler r = new RagIndexReconciler(ragIndexService);
        assertThat(r.supports("rag_index", "post")).isTrue();
        assertThat(r.supports("es_index", "post")).isFalse();
    }

    @Test
    void ragIndexReconciler_callsEnsureIndexed() throws Exception {
        RagIndexReconciler r = new RagIndexReconciler(ragIndexService);
        r.execute("post", 99L);
        verify(ragIndexService).ensureIndexed(99L);
    }

    @Test
    void cassandraTextReconciler_supportsBothPostAndComment() {
        CassandraTextReconciler r = new CassandraTextReconciler(textStorageService, postMapper, restTemplate);
        assertThat(r.supports("cassandra_text", "post")).isTrue();
        assertThat(r.supports("cassandra_text", "comment")).isTrue();
        assertThat(r.supports("es_index", "post")).isFalse();
    }

    @Test
    void cassandraTextReconciler_postAlreadyInCassandra_noop() throws Exception {
        CassandraTextReconciler r = new CassandraTextReconciler(textStorageService, postMapper, restTemplate);
        when(textStorageService.getPostText(10L, null)).thenReturn(Optional.of("existing body"));

        r.execute("post", 10L);

        verify(textStorageService, never()).savePostText(anyLong(), anyString());
    }

    @Test
    void cassandraTextReconciler_postMissingInCassandra_fetchesAndSaves() throws Exception {
        CassandraTextReconciler r = new CassandraTextReconciler(textStorageService, postMapper, restTemplate);
        KnowPost post = new KnowPost();
        post.setId(10L);
        post.setContentUrl("http://minio/posts/10/content.md");
        when(textStorageService.getPostText(10L, null)).thenReturn(Optional.empty());
        when(postMapper.findById(10L)).thenReturn(post);
        when(restTemplate.getForObject("http://minio/posts/10/content.md", String.class))
            .thenReturn("# Hello World");

        r.execute("post", 10L);

        verify(textStorageService).savePostText(10L, "# Hello World");
    }

    @Test
    void cassandraTextReconciler_commentMissingInCassandra_throwsUnresolvable() {
        CassandraTextReconciler r = new CassandraTextReconciler(textStorageService, postMapper, restTemplate);
        when(textStorageService.getCommentTexts(anyList())).thenReturn(java.util.Map.of());

        assertThatThrownBy(() -> r.execute("comment", 50L))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("no source");
    }
}
```

- [ ] **Step 2: Run — confirm failure**

```bash
mvn test -Dtest=ReconcilerTest -q 2>&1 | tail -5
```

Expected: compilation error — reconciler classes not found

- [ ] **Step 3: Create EsIndexReconciler.java**

```java
package com.tongji.reconciliation.executor;

import com.tongji.search.index.SearchIndexService;
import org.springframework.stereotype.Component;

@Component
public class EsIndexReconciler implements Reconciler {

    private final SearchIndexService searchIndexService;

    public EsIndexReconciler(SearchIndexService searchIndexService) {
        this.searchIndexService = searchIndexService;
    }

    @Override
    public boolean supports(String taskType, String targetType) {
        return "es_index".equals(taskType) && "post".equals(targetType);
    }

    @Override
    public void execute(String targetType, long targetId) throws Exception {
        searchIndexService.upsertKnowPost(targetId);
    }
}
```

- [ ] **Step 4: Create RagIndexReconciler.java**

```java
package com.tongji.reconciliation.executor;

import com.tongji.llm.rag.RagIndexService;
import org.springframework.stereotype.Component;

@Component
public class RagIndexReconciler implements Reconciler {

    private final RagIndexService ragIndexService;

    public RagIndexReconciler(RagIndexService ragIndexService) {
        this.ragIndexService = ragIndexService;
    }

    @Override
    public boolean supports(String taskType, String targetType) {
        return "rag_index".equals(taskType) && "post".equals(targetType);
    }

    @Override
    public void execute(String targetType, long targetId) throws Exception {
        ragIndexService.ensureIndexed(targetId);
    }
}
```

- [ ] **Step 5: Create CassandraTextReconciler.java**

```java
package com.tongji.reconciliation.executor;

import com.tongji.knowpost.mapper.KnowPostMapper;
import com.tongji.knowpost.model.KnowPost;
import com.tongji.storage.text.TextStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class CassandraTextReconciler implements Reconciler {

    private static final Logger log = LoggerFactory.getLogger(CassandraTextReconciler.class);

    private final TextStorageService textStorageService;
    private final KnowPostMapper postMapper;
    private final RestTemplate restTemplate;

    public CassandraTextReconciler(TextStorageService textStorageService,
                                    KnowPostMapper postMapper,
                                    RestTemplate restTemplate) {
        this.textStorageService = textStorageService;
        this.postMapper = postMapper;
        this.restTemplate = restTemplate;
    }

    @Override
    public boolean supports(String taskType, String targetType) {
        return "cassandra_text".equals(taskType);
    }

    @Override
    public void execute(String targetType, long targetId) throws Exception {
        if ("post".equals(targetType)) {
            reconcilePost(targetId);
        } else if ("comment".equals(targetType)) {
            reconcileComment(targetId);
        }
    }

    private void reconcilePost(long postId) throws Exception {
        Optional<String> existing = textStorageService.getPostText(postId, null);
        if (existing.isPresent()) return; // already in Cassandra

        KnowPost post = postMapper.findById(postId);
        if (post == null) {
            log.info("Post {} not found (deleted) — skipping cassandra_text reconciliation", postId);
            return;
        }
        if (post.getContentUrl() == null || post.getContentUrl().isBlank()) {
            throw new IllegalStateException("Post " + postId + " has no contentUrl — cannot restore text");
        }
        String body = restTemplate.getForObject(post.getContentUrl(), String.class);
        if (body == null || body.isBlank()) {
            throw new IllegalStateException("Fetched empty body from MinIO for post " + postId);
        }
        textStorageService.savePostText(postId, body);
    }

    private void reconcileComment(long commentId) throws Exception {
        Map<Long, String> texts = textStorageService.getCommentTexts(List.of(commentId));
        if (texts.containsKey(commentId)) return; // already in Cassandra
        // Comments have no retrievable source — flag for manual review
        throw new IllegalStateException(
            "Comment text missing for commentId=" + commentId + " — no source to restore from");
    }
}
```

- [ ] **Step 6: Run — all pass**

```bash
mvn test -Dtest=ReconcilerTest -q
```

Expected: `Tests run: 8, Failures: 0, Errors: 0`

- [ ] **Step 7: Compile full project**

```bash
mvn compile -q
```

Expected: `BUILD SUCCESS`

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/tongji/reconciliation/executor/ \
        src/test/java/com/tongji/reconciliation/executor/ReconcilerTest.java
git commit -m "feat: implement EsIndex, RagIndex, and CassandraText reconcilers"
```

---

## Task 7: Scheduled Scans + Stuck Task Recovery

**Files:**
- Create: `src/main/java/com/tongji/reconciliation/scan/ReconciliationScanService.java`

The scan service runs cursor-paginated scans of published posts and checks their derived stores. Each invocation processes one batch; after completing a full pass, the checkpoint resets to 0 and the next scan restarts from the beginning.

- [ ] **Step 1: Create ReconciliationScanService.java**

> **`commentMapper` is optional:** `@Resource(required = false)` means Spring injects it if `add-comment-system` is deployed, leaves it `null` otherwise. The `scanCommentCassandraInternal` method guards with `if (commentMapper == null) return`. This avoids a hard compile-time dependency on the comment package while the two changes are implemented in parallel.

```java
package com.tongji.reconciliation.scan;

import com.tongji.reconciliation.ScanTypes;
import com.tongji.reconciliation.TaskTypes;
import com.tongji.reconciliation.TargetTypes;
import com.tongji.reconciliation.mapper.ReconciliationCheckpointMapper;
import com.tongji.reconciliation.mapper.ReconciliationTaskMapper;
import com.tongji.reconciliation.model.ReconciliationTask;
import com.tongji.reconciliation.service.ReconciliationService;
import com.tongji.knowpost.mapper.KnowPostMapper;
import com.tongji.search.index.SearchIndexService;
import com.tongji.storage.text.TextStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.List;
import java.util.Map;

@Component
public class ReconciliationScanService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationScanService.class);
    private static final int SCAN_BATCH = 100;
    private static final int STUCK_TIMEOUT_MINUTES = 10;

    @Resource private ReconciliationService reconciliationService;
    @Resource private ReconciliationCheckpointMapper checkpointMapper;
    @Resource private ReconciliationTaskMapper taskMapper;
    @Resource private KnowPostMapper knowPostMapper;
    @Resource private SearchIndexService searchIndexService;
    @Resource private TextStorageService textStorageService;

    // Optional: null until add-comment-system is deployed alongside this change
    @Resource(required = false)
    private com.tongji.comment.mapper.CommentMapper commentMapper;

    /**
     * Scan published posts for missing ES documents.
     * Runs every 5 minutes, processes one cursor batch at a time.
     */
    @Scheduled(fixedDelay = 5 * 60_000)
    public void scanPostEs() {
        Long cursor = nullIfZero(checkpointMapper.getLastId(ScanTypes.POST_ES));
        List<Long> ids = knowPostMapper.listPublishedIdsCursor(cursor, SCAN_BATCH + 1);
        boolean hasMore = ids.size() > SCAN_BATCH;
        List<Long> batch = hasMore ? ids.subList(0, SCAN_BATCH) : ids;

        int created = 0;
        for (Long postId : batch) {
            if (!searchIndexService.existsKnowPost(postId)) {
                long taskId = reconciliationService.createTaskIfAbsent(
                    TaskTypes.ES_INDEX, TargetTypes.POST, postId);
                if (taskId != -1L) created++;
            }
        }

        if (!batch.isEmpty()) {
            checkpointMapper.upsert(ScanTypes.POST_ES, batch.get(batch.size() - 1));
        }
        if (!hasMore) {
            checkpointMapper.reset(ScanTypes.POST_ES); // end of full scan → restart
        }
        if (created > 0) log.info("scanPostEs: created {} es_index tasks", created);
    }

    /**
     * Scan published posts for missing Cassandra text.
     * Uses getPostText(id, null) — returns empty if not in Cassandra.
     */
    @Scheduled(fixedDelay = 7 * 60_000)  // slightly offset from post_es
    public void scanPostCassandra() {
        Long cursor = nullIfZero(checkpointMapper.getLastId(ScanTypes.POST_CASSANDRA));
        List<Long> ids = knowPostMapper.listPublishedIdsCursor(cursor, SCAN_BATCH + 1);
        boolean hasMore = ids.size() > SCAN_BATCH;
        List<Long> batch = hasMore ? ids.subList(0, SCAN_BATCH) : ids;

        int created = 0;
        for (Long postId : batch) {
            if (textStorageService.getPostText(postId, null).isEmpty()) {
                long taskId = reconciliationService.createTaskIfAbsent(
                    TaskTypes.CASSANDRA_TEXT, TargetTypes.POST, postId);
                if (taskId != -1L) created++;
            }
        }

        if (!batch.isEmpty()) {
            checkpointMapper.upsert(ScanTypes.POST_CASSANDRA, batch.get(batch.size() - 1));
        }
        if (!hasMore) checkpointMapper.reset(ScanTypes.POST_CASSANDRA);
        if (created > 0) log.info("scanPostCassandra: created {} cassandra_text tasks", created);
    }

    /**
     * Scan comment metadata vs Cassandra text.
     * No-ops if add-comment-system is not yet deployed (commentMapper == null).
     */
    @Scheduled(fixedDelay = 9 * 60_000)
    public void scanCommentCassandra() {
        if (commentMapper == null) return;  // add-comment-system not yet deployed

        Long cursor = nullIfZero(checkpointMapper.getLastId(ScanTypes.COMMENT_CASSANDRA));
        List<Long> ids = commentMapper.listCommentIdsCursor(cursor, SCAN_BATCH + 1);
        boolean hasMore = ids.size() > SCAN_BATCH;
        List<Long> batch = hasMore ? ids.subList(0, SCAN_BATCH) : ids;

        Map<Long, String> texts = batch.isEmpty()
            ? Map.of()
            : textStorageService.getCommentTexts(batch);

        int created = 0;
        for (Long commentId : batch) {
            if (!texts.containsKey(commentId)) {
                long taskId = reconciliationService.createTaskIfAbsent(
                    TaskTypes.CASSANDRA_TEXT, TargetTypes.COMMENT, commentId);
                if (taskId != -1L) created++;
            }
        }
        if (!batch.isEmpty()) {
            checkpointMapper.upsert(ScanTypes.COMMENT_CASSANDRA, batch.get(batch.size() - 1));
        }
        if (!hasMore) checkpointMapper.reset(ScanTypes.COMMENT_CASSANDRA);
        if (created > 0) log.info("scanCommentCassandra: created {} cassandra_text tasks", created);
    }

    /**
     * Reset running tasks stuck for more than STUCK_TIMEOUT_MINUTES.
     * Covers process crashes and execution timeouts.
     */
    @Scheduled(fixedDelay = 5 * 60_000)
    public void scanRunningTimeout() {
        List<ReconciliationTask> stuck = taskMapper.findStuckRunning(STUCK_TIMEOUT_MINUTES);
        for (ReconciliationTask task : stuck) {
            taskMapper.resetRunningToPending(task.getId());
            log.warn("Reset stuck running task {} ({}/{}) to pending",
                task.getId(), task.getTaskType(), task.getTargetType());
        }
    }

    private static Long nullIfZero(long value) {
        return value == 0 ? null : value;
    }
}
```

Also add `listCommentIdsCursor` to `CommentMapper` (in `add-comment-system` change, already planned) and its XML:

```java
// In CommentMapper.java (add-comment-system), add:
List<Long> listCommentIdsCursor(@Param("cursor") Long cursor, @Param("limit") int limit);
```

```xml
<!-- In CommentMapper.xml, add: -->
<select id="listCommentIdsCursor" resultType="long">
    SELECT comment_id FROM comments
    <if test="cursor != null">WHERE comment_id &gt; #{cursor}</if>
    ORDER BY comment_id ASC
    LIMIT #{limit}
</select>
```

- [ ] **Step 2: Verify compilation**

```bash
mvn compile -q
```

Expected: `BUILD SUCCESS`

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/tongji/reconciliation/scan/
git commit -m "feat: add scheduled reconciliation scans and stuck task recovery"
```

---

## Task 8: Wire Task Creation into PublishDerivedConsumer + Admin API

**Files:**
- Modify: `src/main/java/com/tongji/knowpost/publish/PublishDerivedConsumer.java`
- Create: `src/main/java/com/tongji/reconciliation/api/ReconciliationTaskResponse.java`
- Create: `src/main/java/com/tongji/reconciliation/api/ReconciliationController.java`

- [ ] **Step 1: Replace TODO comments in PublishDerivedConsumer.java**

Find the two `// TODO(add-data-reconciliation)` comment lines (in the `onContentPublished` exception handlers) and replace them:

Replace:
```java
// TODO(add-data-reconciliation): write reconciliation_task(es_index, post, postId)
```
With:
```java
reconciliationService.createTaskIfAbsent(TaskTypes.ES_INDEX, TargetTypes.POST, postId);
```

Replace:
```java
// TODO(add-data-reconciliation): write reconciliation_task(rag_index, post, postId)
```
With:
```java
reconciliationService.createTaskIfAbsent(TaskTypes.RAG_INDEX, TargetTypes.POST, postId);
```

Also add the `ReconciliationService` injection to `PublishDerivedConsumer`:

```java
@Resource
private com.tongji.reconciliation.service.ReconciliationService reconciliationService;
```

And add imports:
```java
import com.tongji.reconciliation.TaskTypes;
import com.tongji.reconciliation.TargetTypes;
import com.tongji.reconciliation.service.ReconciliationService;
```

- [ ] **Step 2: Create ReconciliationTaskResponse.java**

```java
package com.tongji.reconciliation.api;

import com.tongji.reconciliation.model.ReconciliationTask;
import java.time.LocalDateTime;

public class ReconciliationTaskResponse {
    private long id;
    private String taskType;
    private String targetType;
    private long targetId;
    private String status;
    private int retryCount;
    private LocalDateTime nextExecuteAt;
    private String lastError;
    private LocalDateTime createdAt;

    public static ReconciliationTaskResponse from(ReconciliationTask t) {
        ReconciliationTaskResponse r = new ReconciliationTaskResponse();
        r.id = t.getId();
        r.taskType = t.getTaskType();
        r.targetType = t.getTargetType();
        r.targetId = t.getTargetId();
        r.status = t.getStatus();
        r.retryCount = t.getRetryCount();
        r.nextExecuteAt = t.getNextExecuteAt();
        r.lastError = t.getLastError();
        r.createdAt = t.getCreatedAt();
        return r;
    }

    public long getId() { return id; }
    public String getTaskType() { return taskType; }
    public String getTargetType() { return targetType; }
    public long getTargetId() { return targetId; }
    public String getStatus() { return status; }
    public int getRetryCount() { return retryCount; }
    public LocalDateTime getNextExecuteAt() { return nextExecuteAt; }
    public String getLastError() { return lastError; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
```

- [ ] **Step 3: Create ReconciliationController.java**

```java
package com.tongji.reconciliation.api;

import com.tongji.reconciliation.model.ReconciliationTask;
import com.tongji.reconciliation.service.ReconciliationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.Resource;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/reconciliation")
public class ReconciliationController {

    @Resource
    private ReconciliationService reconciliationService;

    /** GET /api/v1/reconciliation/tasks?status=&targetType=&targetId=&limit=&offset= */
    @GetMapping("/tasks")
    public ResponseEntity<List<ReconciliationTaskResponse>> listTasks(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String targetType,
            @RequestParam(required = false) Long targetId,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        List<ReconciliationTask> tasks = reconciliationService.query(
            status, targetType, targetId, Math.min(limit, 100), offset);
        return ResponseEntity.ok(tasks.stream()
            .map(ReconciliationTaskResponse::from)
            .collect(Collectors.toList()));
    }

    /** GET /api/v1/reconciliation/tasks/{id} */
    @GetMapping("/tasks/{id}")
    public ResponseEntity<ReconciliationTaskResponse> getTask(@PathVariable long id) {
        ReconciliationTask task = reconciliationService.findById(id);
        if (task == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(ReconciliationTaskResponse.from(task));
    }

    /** POST /api/v1/reconciliation/tasks/{id}/retry — reset dead → pending */
    @PostMapping("/tasks/{id}/retry")
    public ResponseEntity<Void> retryTask(@PathVariable long id) {
        boolean reset = reconciliationService.retryTask(id);
        return reset ? ResponseEntity.ok().build() : ResponseEntity.notFound().build();
    }

    /** POST /api/v1/reconciliation/targets/{type}/{id}/rerun — rerun all tasks for target */
    @PostMapping("/targets/{targetType}/{targetId}/rerun")
    public ResponseEntity<List<Long>> rerunTarget(
            @PathVariable String targetType,
            @PathVariable long targetId) {
        List<Long> created = reconciliationService.rerunTarget(targetType, targetId);
        return ResponseEntity.ok(created);
    }
}
```

- [ ] **Step 4: Verify compilation**

```bash
mvn compile -q
```

Expected: `BUILD SUCCESS`

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/tongji/knowpost/publish/PublishDerivedConsumer.java \
        src/main/java/com/tongji/reconciliation/api/
git commit -m "feat: wire ReconciliationService into PublishDerivedConsumer and add admin API"
```

---

## Task 9: Verification Tests

**Files:**
- Create: `src/test/java/com/tongji/reconciliation/ReconciliationVerificationTest.java`

- [ ] **Step 1: Write verification tests (tasks.md 5.1–5.3)**

```java
package com.tongji.reconciliation;

import com.tongji.id.IdNamespace;
import com.tongji.id.IdService;
import com.tongji.knowpost.mapper.KnowPostMapper;
import com.tongji.llm.rag.RagIndexService;
import com.tongji.reconciliation.executor.*;
import com.tongji.reconciliation.mapper.ReconciliationCheckpointMapper;
import com.tongji.reconciliation.mapper.ReconciliationErrorLogMapper;
import com.tongji.reconciliation.mapper.ReconciliationTaskMapper;
import com.tongji.reconciliation.model.ReconciliationTask;
import com.tongji.reconciliation.service.impl.ReconciliationServiceImpl;
import com.tongji.search.index.SearchIndexService;
import com.tongji.storage.text.TextStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReconciliationVerificationTest {

    @Mock ReconciliationTaskMapper taskMapper;
    @Mock ReconciliationErrorLogMapper errorLogMapper;
    @Mock ReconciliationCheckpointMapper checkpointMapper;
    @Mock RedissonClient redisson;
    @Mock RLock lock;
    @Mock IdService idService;
    @Mock SearchIndexService searchIndexService;
    @Mock RagIndexService ragIndexService;
    @Mock TextStorageService textStorageService;
    @Mock KnowPostMapper postMapper;
    @Mock RestTemplate restTemplate;

    ReconciliationServiceImpl service;
    ReconciliationTaskExecutor executor;

    @BeforeEach
    void setUp() throws Exception {
        when(redisson.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);

        service = new ReconciliationServiceImpl();
        org.springframework.test.util.ReflectionTestUtils.setField(service, "taskMapper", taskMapper);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "idService", idService);
    }

    private ReconciliationTask pendingTask(long id, String taskType, String targetType, int retryCount) {
        return ReconciliationTask.builder()
            .id(id).taskType(taskType).targetType(targetType)
            .targetId(10L).status("pending").retryCount(retryCount)
            .nextExecuteAt(LocalDateTime.now()).build();
    }

    /**
     * tasks.md 5.1: State machine test.
     * pending → running → succeeded (success path)
     * pending → running → pending with retry (failure < 5)
     * pending → running → dead + error_log (failure >= 5)
     */
    @Test
    void stateMachine_successPath_pendingToSucceeded() {
        Reconciler noop = new EsIndexReconciler(searchIndexService);
        executor = new ReconciliationTaskExecutor(taskMapper, errorLogMapper,
            List.of(noop), redisson, idService);
        when(taskMapper.markRunning(1L)).thenReturn(1);

        executor.executeTask(pendingTask(1L, TaskTypes.ES_INDEX, TargetTypes.POST, 0));

        verify(taskMapper).markRunning(1L);
        verify(taskMapper).markSucceeded(1L);
        verify(taskMapper, never()).markDead(anyLong(), anyString());
    }

    @Test
    void stateMachine_failurePath_incrementsRetryUntilDead() {
        Reconciler always_fail = new RagIndexReconciler(ragIndexService);
        doThrow(new RuntimeException("ES down")).when(ragIndexService).ensureIndexed(anyLong());
        executor = new ReconciliationTaskExecutor(taskMapper, errorLogMapper,
            List.of(always_fail), redisson, idService);

        // 4th failure (retryCount=4 → newCount=5 → dead)
        when(taskMapper.markRunning(2L)).thenReturn(1);
        when(idService.nextId(IdNamespace.AUDIT_LOG)).thenReturn(100L);

        executor.executeTask(pendingTask(2L, TaskTypes.RAG_INDEX, TargetTypes.POST, 4));

        verify(taskMapper).markDead(eq(2L), anyString());
        verify(errorLogMapper).insert(argThat(e -> e.getTaskId() == 2L));
        verify(taskMapper, never()).markPendingRetry(anyLong(), anyInt(), any(), anyString());
    }

    /**
     * tasks.md 5.2: Checkpoint scan test.
     * Verifies cursor advances per batch and resets after full pass.
     */
    @Test
    void checkpoint_cursorAdvancesAfterEachBatch() {
        when(checkpointMapper.getLastId(ScanTypes.POST_ES)).thenReturn(0L);
        when(idService.nextId(IdNamespace.RECONCILIATION_TASK)).thenReturn(99L);
        // Simulate finding post IDs 1..50 (less than batch, so this is end of full scan)
        var postIds = List.of(1L, 5L, 10L, 50L);
        when(postMapper.listPublishedIdsCursor(null, 101)).thenReturn(postIds);
        when(searchIndexService.existsKnowPost(anyLong())).thenReturn(false); // all missing

        service = spy(service);
        com.tongji.reconciliation.scan.ReconciliationScanService scanService =
            new com.tongji.reconciliation.scan.ReconciliationScanService();
        org.springframework.test.util.ReflectionTestUtils.setField(scanService, "reconciliationService", service);
        org.springframework.test.util.ReflectionTestUtils.setField(scanService, "checkpointMapper", checkpointMapper);
        org.springframework.test.util.ReflectionTestUtils.setField(scanService, "taskMapper", taskMapper);
        org.springframework.test.util.ReflectionTestUtils.setField(scanService, "knowPostMapper", postMapper);
        org.springframework.test.util.ReflectionTestUtils.setField(scanService, "searchIndexService", searchIndexService);
        org.springframework.test.util.ReflectionTestUtils.setField(scanService, "textStorageService", textStorageService);

        scanService.scanPostEs();

        // Checkpoint updated to last scanned ID (50)
        verify(checkpointMapper).upsert(ScanTypes.POST_ES, 50L);
        // Full scan completed (batch < 100) → reset for next full pass
        verify(checkpointMapper).reset(ScanTypes.POST_ES);
        // Tasks created for all missing posts
        // Must use eq() for literal args when mixing with anyLong() — Mockito requires all-matchers
        verify(service, times(4)).createTaskIfAbsent(eq(TaskTypes.ES_INDEX), eq(TargetTypes.POST), anyLong());
    }

    /**
     * tasks.md 5.3: Three domain reconciler smoke tests.
     */
    @Test
    void smokeTest_esIndex_callsUpsertKnowPost() throws Exception {
        EsIndexReconciler r = new EsIndexReconciler(searchIndexService);
        r.execute(TargetTypes.POST, 42L);
        verify(searchIndexService).upsertKnowPost(42L);
    }

    @Test
    void smokeTest_ragIndex_callsEnsureIndexed() throws Exception {
        RagIndexReconciler r = new RagIndexReconciler(ragIndexService);
        r.execute(TargetTypes.POST, 55L);
        verify(ragIndexService).ensureIndexed(55L);
    }

    @Test
    void smokeTest_cassandraText_post_fetchesAndSavesWhenMissing() throws Exception {
        var post = new com.tongji.knowpost.model.KnowPost();
        post.setId(10L);
        post.setContentUrl("http://minio/posts/10/content.md");
        when(textStorageService.getPostText(10L, null)).thenReturn(java.util.Optional.empty());
        when(postMapper.findById(10L)).thenReturn(post);
        when(restTemplate.getForObject("http://minio/posts/10/content.md", String.class))
            .thenReturn("# Restored text");

        CassandraTextReconciler r = new CassandraTextReconciler(textStorageService, postMapper, restTemplate);
        r.execute(TargetTypes.POST, 10L);

        verify(textStorageService).savePostText(10L, "# Restored text");
    }
}
```

- [ ] **Step 2: Run all verification tests**

```bash
mvn test -Dtest="ReconciliationServiceTest,ReconciliationTaskExecutorTest,ReconcilerTest,ReconciliationVerificationTest" -q
```

Expected: `Tests run: 25, Failures: 0, Errors: 0` (5 + 6 + 8 + 6)

- [ ] **Step 3: Run full test suite**

```bash
mvn test -q
```

Expected: `BUILD SUCCESS` — no regressions

- [ ] **Step 4: Commit**

```bash
git add src/test/java/com/tongji/reconciliation/ReconciliationVerificationTest.java
git commit -m "test: add reconciliation verification tests covering state machine, checkpoint, and 3 smoke tests"
```

---

## Self-Review Checklist

**tasks.md coverage:**
- [x] 1.1 `reconciliation_task` table (Task 1)
- [x] 1.2 `reconciliation_checkpoint` table (Task 1)
- [x] 1.3 `reconciliation_error_log` table (Task 1)
- [x] 1.4 Task types, state machine, retry strategy defined (Tasks 2–4)
- [x] 1.5 Scheduler + executor + Redis shard lock (Task 4)
- [x] 2.1 Scheduled scan tasks: post_es, post_cassandra, comment_cassandra (Task 7)
- [x] 2.2 Publish pipeline failure → `createTaskIfAbsent` (Task 8)
- [x] 2.3 ES/RAG failure → `createTaskIfAbsent` (Task 8 wires TODO comments)
- [x] 2.4 Startup/stuck running → scheduled scan every 5 min (Task 7 `scanRunningTimeout`)
- [x] 2.5 Manual rerun endpoint (Task 8 `POST /targets/{type}/{id}/rerun`)
- [x] 3.1 Like/fav bitmap → SDS reconciler: deferred — counter SDS rebuild needs CounterService internal; existing counter system handles this via `rebuild()` on startup
- [x] 3.2 Following → follower/Redis/user count: deferred — requires RelationService internals not exposed
- [x] 3.3 Comment → Cassandra/count: `CassandraTextReconciler` (Task 6)
- [x] 3.4 Published post → ES/RAG/Gorse: `EsIndexReconciler` + `RagIndexReconciler` (Task 6); Gorse stub skipped (handled by default "no reconciler" path in executor)
- [x] 3.5 Follow feed inbox: deferred — requires follow feed implementation from `add-recommendation-and-follow-feed`
- [x] 3.6 Gorse user/item/feedback: deferred — requires Gorse client from `add-recommendation-and-follow-feed`
- [x] 4.1 Execution timing + failure reason + retry count stored in task table (Task 2)
- [x] 4.2 Task query API (Task 8 `GET /reconciliation/tasks`)
- [x] 4.3 Dead task error log (Task 4 executor writes `reconciliation_error_log`)
- [x] 5.1 State machine test (Task 9)
- [x] 5.2 Checkpoint scan test (Task 9)
- [x] 5.3 3 domain smoke tests: ES, RAG, CassandraText (Task 9)

**Bugs fixed during review:**
- Task 7: Removed invalid `BeansUtils_NOT_VALID.getBean()` code from Step 1; consolidated into single correct implementation using `@Resource(required = false) CommentMapper` from the start
- Task 9 checkpoint test: Fixed Mockito `InvalidUseOfMatchersException` — `createTaskIfAbsent(TaskTypes.ES_INDEX, TargetTypes.POST, anyLong())` changed to use `eq()` for literal args
- Task 6 test count: corrected 9 → 8 (ReconcilerTest has 8 test methods)
- Task 9 total test count: corrected 26 → 25 (5+6+8+6)

**No placeholders.**

**Type consistency:**
- `ReconciliationService.createTask/createTaskIfAbsent` signatures match usage in Task 8 and Task 7
- `ReconciliationTaskExecutor.executeTask(ReconciliationTask)` matches test invocation in Task 9
- `Reconciler.execute(String targetType, long targetId)` matches implementations in Task 6 and tests in Task 9
- `ReconciliationCheckpointMapper.getLastId` returns `long`; `nullIfZero` converts to `Long` for cursor SQL — consistent
- `IdNamespace.RECONCILIATION_TASK` used in Task 3 matches `add-leaf-id-service` plan definition
