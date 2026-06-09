# Add Leaf ID Service Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a unified `IdService` for ZhiGuang so business code requests IDs by namespace, with Snowflake for high-throughput entities and Leaf Segment for low-frequency operational records.

**Architecture:** Add a top-level `com.tongji.id` package that owns ID namespaces, routing, Snowflake generation, Segment allocation, and MyBatis persistence for `leaf_alloc`. Existing user IDs remain MySQL auto-increment. Existing `knowpost`, `relation`, and `outbox` ID generation moves behind `IdService`.

**Tech Stack:** Java 21, Spring Boot 3.2, MyBatis XML mappers, MySQL, JUnit 5, Spring Boot configuration properties.

---

## Source Context

- OpenSpec change: `openspec/changes/add-leaf-id-service`
- Spec: `openspec/changes/add-leaf-id-service/specs/id-service/spec.md`
- Tasks: `openspec/changes/add-leaf-id-service/tasks.md`
- Existing Snowflake generator: `src/main/java/com/tongji/knowpost/id/SnowflakeIdGenerator.java`
- Existing random relation IDs: `src/main/java/com/tongji/relation/service/impl/RelationServiceImpl.java`
- Existing outbox mapper: `src/main/java/com/tongji/relation/outbox/OutboxMapper.java`

## File Structure

Create:

- `src/main/java/com/tongji/id/IdService.java` - public ID service interface.
- `src/main/java/com/tongji/id/IdNamespace.java` - enum namespaces and routing metadata.
- `src/main/java/com/tongji/id/IdMode.java` - `SNOWFLAKE` or `SEGMENT`.
- `src/main/java/com/tongji/id/IdProperties.java` - `zhiguang.id` configuration binding and validation.
- `src/main/java/com/tongji/id/SnowflakeIdGenerator.java` - Snowflake implementation moved to shared package.
- `src/main/java/com/tongji/id/SegmentIdGenerator.java` - segment generator contract used by `DefaultIdService`.
- `src/main/java/com/tongji/id/LeafSegmentIdGenerator.java` - Segment double-buffer generator implementation.
- `src/main/java/com/tongji/id/DefaultIdService.java` - routes namespace requests to the right generator.
- `src/main/java/com/tongji/id/LeafAlloc.java` - row model for `leaf_alloc`.
- `src/main/java/com/tongji/id/LeafAllocMapper.java` - MyBatis mapper for segment allocation.
- `src/main/resources/mapper/LeafAllocMapper.xml` - SQL for loading and advancing segments.
- `src/test/java/com/tongji/id/SnowflakeIdGeneratorTest.java`
- `src/test/java/com/tongji/id/DefaultIdServiceTest.java`
- `src/test/java/com/tongji/id/LeafSegmentIdGeneratorTest.java`
- `src/test/java/com/tongji/id/IdGenerationSmokeTest.java`

Modify:

- `db/schema.sql` - add `leaf_alloc` table and initial `biz_tag` rows.
- `src/main/resources/application.yml` - add Snowflake worker/datacenter configuration.
- `src/main/java/com/tongji/knowpost/service/impl/KnowPostServiceImpl.java` - inject `IdService`, replace direct Snowflake calls.
- `src/main/java/com/tongji/relation/service/impl/RelationServiceImpl.java` - inject `IdService`, replace `ThreadLocalRandom` IDs.

Delete:

- `src/main/java/com/tongji/knowpost/id/SnowflakeIdGenerator.java` after the shared generator is in place and imports are updated.

---

## Chunk 1: Shared Snowflake ID Service

### Task 1: Add ID Namespace Model

**Files:**

- Create: `src/main/java/com/tongji/id/IdMode.java`
- Create: `src/main/java/com/tongji/id/IdNamespace.java`
- Test: `src/test/java/com/tongji/id/DefaultIdServiceTest.java`

- [ ] **Step 1: Write namespace routing test**

Add `DefaultIdServiceTest` with enum routing assertions:

```java
package com.tongji.id;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultIdServiceTest {
    @Test
    void namespacesExposeStableBizTagsAndModes() {
        assertThat(IdNamespace.KNOW_POST.mode()).isEqualTo(IdMode.SNOWFLAKE);
        assertThat(IdNamespace.OUTBOX.bizTag()).isEqualTo("outbox");
        assertThat(IdNamespace.FOLLOWING.mode()).isEqualTo(IdMode.SNOWFLAKE);
        assertThat(IdNamespace.RECONCILIATION_TASK.mode()).isEqualTo(IdMode.SEGMENT);
        assertThat(IdNamespace.values())
                .extracting(IdNamespace::bizTag)
                .doesNotContain("user");
    }
}
```

- [ ] **Step 2: Run test and verify it fails**

Run:

```powershell
& 'C:\Users\Administrator\Desktop\文档\.codex\tools\apache-maven-3.9.6\bin\mvn.cmd' -Dtest=com.tongji.id.DefaultIdServiceTest test
```

Expected: compile failure because `IdNamespace` and `IdMode` do not exist.

- [ ] **Step 3: Implement enums**

Create `IdMode.java`:

```java
package com.tongji.id;

public enum IdMode {
    SNOWFLAKE,
    SEGMENT
}
```

Create `IdNamespace.java`:

```java
package com.tongji.id;

public enum IdNamespace {
    KNOW_POST("know_post", IdMode.SNOWFLAKE),
    OUTBOX("outbox", IdMode.SNOWFLAKE),
    FOLLOWING("following", IdMode.SNOWFLAKE),
    COMMENT("comment", IdMode.SNOWFLAKE),
    PENDING_COMMENT("pending_comment", IdMode.SNOWFLAKE),
    PUBLISH_ATTEMPT("publish_attempt", IdMode.SNOWFLAKE),
    RECONCILIATION_TASK("reconciliation_task", IdMode.SEGMENT),
    ADMIN_OPERATION("admin_operation", IdMode.SEGMENT),
    AUDIT_LOG("audit_log", IdMode.SEGMENT);

    private final String bizTag;
    private final IdMode mode;

    IdNamespace(String bizTag, IdMode mode) {
        this.bizTag = bizTag;
        this.mode = mode;
    }

    public String bizTag() {
        return bizTag;
    }

    public IdMode mode() {
        return mode;
    }
}
```

- [ ] **Step 4: Run test**

Run:

```powershell
& 'C:\Users\Administrator\Desktop\文档\.codex\tools\apache-maven-3.9.6\bin\mvn.cmd' -Dtest=com.tongji.id.DefaultIdServiceTest test
```

Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add src/main/java/com/tongji/id/IdMode.java src/main/java/com/tongji/id/IdNamespace.java src/test/java/com/tongji/id/DefaultIdServiceTest.java
git commit -m "feat: add id namespace routing model"
```

### Task 2: Add Snowflake Configuration

**Files:**

- Create: `src/main/java/com/tongji/id/IdProperties.java`
- Modify: `src/main/resources/application.yml`
- Test: `src/test/java/com/tongji/id/SnowflakeIdGeneratorTest.java`

- [ ] **Step 1: Write configuration validation test**

Add validation-oriented tests in `SnowflakeIdGeneratorTest`:

```java
package com.tongji.id;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SnowflakeIdGeneratorTest {
    @Test
    void rejectsWorkerIdOutsideFiveBits() {
        assertThatThrownBy(() -> new SnowflakeIdGenerator(1, 32))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("workerId");
    }

    @Test
    void rejectsDatacenterIdOutsideFiveBits() {
        assertThatThrownBy(() -> new SnowflakeIdGenerator(32, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("datacenterId");
    }
}
```

- [ ] **Step 2: Run test and verify it fails**

Run:

```powershell
& 'C:\Users\Administrator\Desktop\文档\.codex\tools\apache-maven-3.9.6\bin\mvn.cmd' -Dtest=com.tongji.id.SnowflakeIdGeneratorTest test
```

Expected: compile failure because shared `SnowflakeIdGenerator` does not exist.

- [ ] **Step 3: Create `IdProperties`**

```java
package com.tongji.id;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Component
@Validated
@ConfigurationProperties(prefix = "zhiguang.id")
public class IdProperties {
    @Valid
    private Snowflake snowflake = new Snowflake();

    public Snowflake getSnowflake() {
        return snowflake;
    }

    public void setSnowflake(Snowflake snowflake) {
        this.snowflake = snowflake;
    }

    public static class Snowflake {
        @Min(0)
        @Max(31)
        private long datacenterId = 1;

        @Min(0)
        @Max(31)
        private long workerId = 1;

        public long getDatacenterId() {
            return datacenterId;
        }

        public void setDatacenterId(long datacenterId) {
            this.datacenterId = datacenterId;
        }

        public long getWorkerId() {
            return workerId;
        }

        public void setWorkerId(long workerId) {
            this.workerId = workerId;
        }
    }
}
```

- [ ] **Step 4: Add YAML config**

Append to `src/main/resources/application.yml`:

```yaml
zhiguang:
  id:
    snowflake:
      datacenter-id: 1
      worker-id: 1
```

- [ ] **Step 5: Move Snowflake generator into shared package**

Create `src/main/java/com/tongji/id/SnowflakeIdGenerator.java` by moving the existing implementation from `com.tongji.knowpost.id` and changing the package.

Keep these behaviors:

- 41-bit timestamp, 5-bit datacenter, 5-bit worker, 12-bit sequence.
- Reject invalid worker/datacenter values.
- Wait up to 5 ms for small clock rollback.
- Throw for larger rollback.

Constructor shape:

```java
public SnowflakeIdGenerator(IdProperties properties) {
    this(properties.getSnowflake().getDatacenterId(), properties.getSnowflake().getWorkerId());
}

public SnowflakeIdGenerator(long datacenterId, long workerId) {
    // existing validation and assignment
}
```

Annotate with `@Component`.

- [ ] **Step 6: Run Snowflake tests**

Run:

```powershell
& 'C:\Users\Administrator\Desktop\文档\.codex\tools\apache-maven-3.9.6\bin\mvn.cmd' -Dtest=com.tongji.id.SnowflakeIdGeneratorTest test
```

Expected: PASS.

- [ ] **Step 7: Commit**

```powershell
git add src/main/java/com/tongji/id/IdProperties.java src/main/java/com/tongji/id/SnowflakeIdGenerator.java src/main/resources/application.yml src/test/java/com/tongji/id/SnowflakeIdGeneratorTest.java
git commit -m "feat: configure shared snowflake id generator"
```

### Task 3: Add IdService Snowflake Routing

**Files:**

- Create: `src/main/java/com/tongji/id/IdService.java`
- Create: `src/main/java/com/tongji/id/DefaultIdService.java`
- Test: `src/test/java/com/tongji/id/DefaultIdServiceTest.java`

- [ ] **Step 1: Write Snowflake routing tests**

Extend `DefaultIdServiceTest`:

```java
@Test
void returnsSnowflakeIdsForHighThroughputNamespaces() {
    SnowflakeIdGenerator snowflake = new SnowflakeIdGenerator(1, 1);
    SegmentIdGenerator segment = namespace -> {
        throw new AssertionError("segment should not be used");
    };
    IdService service = new DefaultIdService(snowflake, segment);

    long postId = service.nextId(IdNamespace.KNOW_POST);
    long outboxId = service.nextId(IdNamespace.OUTBOX);

    assertThat(postId).isPositive();
    assertThat(outboxId).isPositive();
    assertThat(outboxId).isNotEqualTo(postId);
}
```

- [ ] **Step 2: Run test and verify it fails**

Run:

```powershell
& 'C:\Users\Administrator\Desktop\文档\.codex\tools\apache-maven-3.9.6\bin\mvn.cmd' -Dtest=com.tongji.id.DefaultIdServiceTest test
```

Expected: compile failure for `IdService`, `DefaultIdService`, and `SegmentIdGenerator`.

- [ ] **Step 3: Implement `IdService`**

```java
package com.tongji.id;

public interface IdService {
    long nextId(IdNamespace namespace);
}
```

- [ ] **Step 4: Implement `DefaultIdService` with Snowflake branch**

```java
package com.tongji.id;

import org.springframework.stereotype.Service;

@Service
public class DefaultIdService implements IdService {
    private final SnowflakeIdGenerator snowflake;
    private final SegmentIdGenerator segment;

    public DefaultIdService(SnowflakeIdGenerator snowflake, SegmentIdGenerator segment) {
        this.snowflake = snowflake;
        this.segment = segment;
    }

    @Override
    public long nextId(IdNamespace namespace) {
        if (namespace == null) {
            throw new IllegalArgumentException("namespace must not be null");
        }
        return switch (namespace.mode()) {
            case SNOWFLAKE -> snowflake.nextId();
            case SEGMENT -> segment.nextId(namespace);
        };
    }
}
```

- [ ] **Step 5: Add the segment contract**

Create `SegmentIdGenerator` as the stable contract used by `DefaultIdService` and tests:

```java
package com.tongji.id;

public interface SegmentIdGenerator {
    long nextId(IdNamespace namespace);
}
```

Task 8 adds `LeafSegmentIdGenerator implements SegmentIdGenerator` without changing `DefaultIdService`.

- [ ] **Step 6: Run tests**

Run:

```powershell
& 'C:\Users\Administrator\Desktop\文档\.codex\tools\apache-maven-3.9.6\bin\mvn.cmd' -Dtest=com.tongji.id.DefaultIdServiceTest test
```

Expected: PASS.

- [ ] **Step 7: Commit**

```powershell
git add src/main/java/com/tongji/id/IdService.java src/main/java/com/tongji/id/DefaultIdService.java src/main/java/com/tongji/id/SegmentIdGenerator.java src/test/java/com/tongji/id/DefaultIdServiceTest.java
git commit -m "feat: route id generation by namespace"
```

---

## Chunk 2: Integrate Existing Business IDs

### Task 4: Replace KnowPost and Outbox Snowflake Calls

**Files:**

- Modify: `src/main/java/com/tongji/knowpost/service/impl/KnowPostServiceImpl.java`
- Test: existing compile plus targeted unit tests if service tests are added later.

- [ ] **Step 1: Replace field and constructor dependency**

Change import:

```java
import com.tongji.id.IdNamespace;
import com.tongji.id.IdService;
```

Replace field:

```java
private final IdService idService;
```

Replace constructor parameter:

```java
IdService idService,
```

Assign:

```java
this.idService = idService;
```

- [ ] **Step 2: Replace draft post ID**

In `createDraft`:

```java
long id = idService.nextId(IdNamespace.KNOW_POST);
```

- [ ] **Step 3: Replace outbox IDs**

In metadata update, publish, and delete outbox writes:

```java
long outId = idService.nextId(IdNamespace.OUTBOX);
```

Search command:

```powershell
rg -n "idGen|SnowflakeIdGenerator|nextId\\(" src/main/java/com/tongji/knowpost/service/impl/KnowPostServiceImpl.java
```

Expected: no `idGen` or `SnowflakeIdGenerator` references remain.

- [ ] **Step 4: Run compile test**

Run:

```powershell
& 'C:\Users\Administrator\Desktop\文档\.codex\tools\apache-maven-3.9.6\bin\mvn.cmd' -DskipTests compile
```

Expected: compile succeeds or exposes missing imports from the move.

- [ ] **Step 5: Remove old Snowflake component**

After `KnowPostServiceImpl` no longer imports it, delete:

```powershell
git rm src/main/java/com/tongji/knowpost/id/SnowflakeIdGenerator.java
```

- [ ] **Step 6: Compile again**

Run:

```powershell
& 'C:\Users\Administrator\Desktop\文档\.codex\tools\apache-maven-3.9.6\bin\mvn.cmd' -DskipTests compile
```

Expected: compile succeeds with no references to the old package.

- [ ] **Step 7: Commit**

```powershell
git add src/main/java/com/tongji/knowpost/service/impl/KnowPostServiceImpl.java
git commit -m "refactor: use id service for knowpost ids"
```

### Task 5: Replace Relation Random IDs

**Files:**

- Modify: `src/main/java/com/tongji/relation/service/impl/RelationServiceImpl.java`

- [ ] **Step 1: Add `IdService` dependency**

Imports:

```java
import com.tongji.id.IdNamespace;
import com.tongji.id.IdService;
```

Field:

```java
private final IdService idService;
```

Constructor parameter:

```java
IdService idService
```

Assignment:

```java
this.idService = idService;
```

- [ ] **Step 2: Replace following row ID**

In `follow`:

```java
long id = idService.nextId(IdNamespace.FOLLOWING);
```

- [ ] **Step 3: Replace relation outbox IDs**

In `follow` and `unfollow`:

```java
Long outId = idService.nextId(IdNamespace.OUTBOX);
```

- [ ] **Step 4: Remove unused import**

Remove:

```java
import java.util.concurrent.ThreadLocalRandom;
```

- [ ] **Step 5: Search for remaining random business IDs**

Run:

```powershell
rg -n "ThreadLocalRandom\\.current\\(\\)\\.nextLong|SnowflakeIdGenerator|idGen" src/main/java
```

Expected: no remaining direct business ID generation except tests or intentional non-ID random use.

- [ ] **Step 6: Compile**

Run:

```powershell
& 'C:\Users\Administrator\Desktop\文档\.codex\tools\apache-maven-3.9.6\bin\mvn.cmd' -DskipTests compile
```

Expected: PASS.

- [ ] **Step 7: Commit**

```powershell
git add src/main/java/com/tongji/relation/service/impl/RelationServiceImpl.java
git commit -m "refactor: use id service for relation ids"
```

---

## Chunk 3: Leaf Segment Persistence and Double Buffer

### Task 6: Add `leaf_alloc` Schema

**Files:**

- Modify: `db/schema.sql`

- [ ] **Step 1: Add schema after `outbox` table**

```sql
CREATE TABLE IF NOT EXISTS leaf_alloc (
    biz_tag VARCHAR(128) NOT NULL,
    max_id BIGINT NOT NULL,
    step INT NOT NULL,
    description VARCHAR(256) NULL,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (biz_tag)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO leaf_alloc (biz_tag, max_id, step, description)
VALUES
    ('reconciliation_task', 0, 1000, 'Data reconciliation task IDs'),
    ('admin_operation', 0, 1000, 'Reserved admin operation IDs'),
    ('audit_log', 0, 1000, 'Reserved audit log IDs')
ON DUPLICATE KEY UPDATE
    step = VALUES(step),
    description = VALUES(description);
```

- [ ] **Step 2: Verify SQL text**

Run:

```powershell
rg -n "CREATE TABLE IF NOT EXISTS leaf_alloc|reconciliation_task|admin_operation|audit_log" db/schema.sql
```

Expected: table and three seed rows are present.

- [ ] **Step 3: Commit**

```powershell
git add db/schema.sql
git commit -m "feat: add leaf segment allocation schema"
```

### Task 7: Add Segment Mapper

**Files:**

- Create: `src/main/java/com/tongji/id/LeafAlloc.java`
- Create: `src/main/java/com/tongji/id/LeafAllocMapper.java`
- Create: `src/main/resources/mapper/LeafAllocMapper.xml`

- [ ] **Step 1: Create row model**

```java
package com.tongji.id;

public class LeafAlloc {
    private String bizTag;
    private long maxId;
    private int step;

    public String getBizTag() {
        return bizTag;
    }

    public void setBizTag(String bizTag) {
        this.bizTag = bizTag;
    }

    public long getMaxId() {
        return maxId;
    }

    public void setMaxId(long maxId) {
        this.maxId = maxId;
    }

    public int getStep() {
        return step;
    }

    public void setStep(int step) {
        this.step = step;
    }
}
```

- [ ] **Step 2: Create mapper interface**

```java
package com.tongji.id;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface LeafAllocMapper {
    LeafAlloc selectForUpdate(@Param("bizTag") String bizTag);

    int updateMaxId(@Param("bizTag") String bizTag,
                    @Param("oldMaxId") long oldMaxId,
                    @Param("newMaxId") long newMaxId);
}
```

- [ ] **Step 3: Create mapper XML**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="com.tongji.id.LeafAllocMapper">
    <select id="selectForUpdate" resultType="com.tongji.id.LeafAlloc">
        SELECT biz_tag, max_id, step
        FROM leaf_alloc
        WHERE biz_tag = #{bizTag}
        FOR UPDATE
    </select>

    <update id="updateMaxId">
        UPDATE leaf_alloc
        SET max_id = #{newMaxId}
        WHERE biz_tag = #{bizTag}
          AND max_id = #{oldMaxId}
    </update>
</mapper>
```

- [ ] **Step 4: Compile mapper**

Run:

```powershell
& 'C:\Users\Administrator\Desktop\文档\.codex\tools\apache-maven-3.9.6\bin\mvn.cmd' -DskipTests compile
```

Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add src/main/java/com/tongji/id/LeafAlloc.java src/main/java/com/tongji/id/LeafAllocMapper.java src/main/resources/mapper/LeafAllocMapper.xml
git commit -m "feat: add leaf allocation mapper"
```

### Task 8: Implement Segment Double Buffer

**Files:**

- Modify: `src/main/java/com/tongji/id/LeafSegmentIdGenerator.java`
- Test: `src/test/java/com/tongji/id/LeafSegmentIdGeneratorTest.java`

- [ ] **Step 1: Write tests for segment generation**

Use a fake in-memory allocator rather than a database. Test two behaviors:

```java
@Test
void generatesIncreasingIdsAcrossSegmentSwitch() {
    FakeAllocator allocator = new FakeAllocator(3);
    SegmentIdGenerator generator = new LeafSegmentIdGenerator(allocator);

    assertThat(generator.nextId(IdNamespace.RECONCILIATION_TASK)).isEqualTo(1);
    assertThat(generator.nextId(IdNamespace.RECONCILIATION_TASK)).isEqualTo(2);
    assertThat(generator.nextId(IdNamespace.RECONCILIATION_TASK)).isEqualTo(3);
    assertThat(generator.nextId(IdNamespace.RECONCILIATION_TASK)).isEqualTo(4);
}

@Test
void rejectsSnowflakeNamespaceForSegmentGenerator() {
    SegmentIdGenerator generator = new LeafSegmentIdGenerator(new FakeAllocator(100));

    assertThatThrownBy(() -> generator.nextId(IdNamespace.KNOW_POST))
            .isInstanceOf(IllegalArgumentException.class);
}
```

- [ ] **Step 2: Run tests and verify failure**

Run:

```powershell
& 'C:\Users\Administrator\Desktop\文档\.codex\tools\apache-maven-3.9.6\bin\mvn.cmd' -Dtest=com.tongji.id.LeafSegmentIdGeneratorTest test
```

Expected: compile failure or failing tests because `LeafSegmentIdGenerator` is not implemented yet.

- [ ] **Step 3: Introduce allocator abstraction**

Create a package-private allocation interface in `LeafSegmentIdGenerator.java` or a separate file if clarity demands. This is separate from the public `SegmentIdGenerator` contract:

```java
interface SegmentAllocator {
    Segment nextSegment(String bizTag);
}
```

Create immutable segment model:

```java
record Segment(long startInclusive, long endInclusive) {
}
```

- [ ] **Step 4: Implement DB allocator**

Create `MyBatisSegmentAllocator`:

```java
package com.tongji.id;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class MyBatisSegmentAllocator implements SegmentAllocator {
    private final LeafAllocMapper mapper;

    MyBatisSegmentAllocator(LeafAllocMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    @Transactional
    public Segment nextSegment(String bizTag) {
        LeafAlloc row = mapper.selectForUpdate(bizTag);
        if (row == null) {
            throw new IllegalStateException("leaf_alloc biz_tag not found: " + bizTag);
        }
        long oldMax = row.getMaxId();
        long newMax = oldMax + row.getStep();
        int updated = mapper.updateMaxId(bizTag, oldMax, newMax);
        if (updated != 1) {
            throw new IllegalStateException("leaf_alloc update failed: " + bizTag);
        }
        return new Segment(oldMax + 1, newMax);
    }
}
```

- [ ] **Step 5: Implement synchronized double-buffer generator**

`LeafSegmentIdGenerator` must implement the stable contract:

```java
@Component
public class LeafSegmentIdGenerator implements SegmentIdGenerator {
    // ...
}
```

Keep the public method:

```java
long nextId(IdNamespace namespace)
```

Behavior:

- Reject null namespaces.
- Reject non-`SEGMENT` namespaces.
- Maintain per-namespace state in `ConcurrentHashMap<IdNamespace, SegmentState>`.
- Lazily allocate first segment.
- When current segment is exhausted, switch to preloaded next segment if available; otherwise allocate synchronously.
- When current usage reaches 75%, try to preload next segment once.
- If async preload is too much for first version, do synchronous preload inside lock. Keep code deterministic and testable.

- [ ] **Step 6: Run segment tests**

Run:

```powershell
& 'C:\Users\Administrator\Desktop\文档\.codex\tools\apache-maven-3.9.6\bin\mvn.cmd' -Dtest=com.tongji.id.LeafSegmentIdGeneratorTest test
```

Expected: PASS.

- [ ] **Step 7: Commit**

```powershell
git add src/main/java/com/tongji/id/LeafSegmentIdGenerator.java src/main/java/com/tongji/id/MyBatisSegmentAllocator.java src/test/java/com/tongji/id/LeafSegmentIdGeneratorTest.java
git commit -m "feat: implement leaf segment id generator"
```

---

## Chunk 4: Verification and OpenSpec Task Closure

### Task 9: Add ID Smoke and Throughput Tests

**Files:**

- Modify: `src/test/java/com/tongji/id/SnowflakeIdGeneratorTest.java`
- Create: `src/test/java/com/tongji/id/IdGenerationSmokeTest.java`

- [ ] **Step 1: Add concurrent Snowflake uniqueness test**

```java
@Test
void generatesUniqueIdsConcurrently() throws Exception {
    SnowflakeIdGenerator generator = new SnowflakeIdGenerator(1, 1);
    int threads = 8;
    int perThread = 2000;
    ExecutorService executor = Executors.newFixedThreadPool(threads);
    Set<Long> ids = ConcurrentHashMap.newKeySet();

    List<Callable<Void>> tasks = IntStream.range(0, threads)
            .mapToObj(i -> (Callable<Void>) () -> {
                for (int j = 0; j < perThread; j++) {
                    ids.add(generator.nextId());
                }
                return null;
            })
            .toList();

    executor.invokeAll(tasks);
    executor.shutdown();

    assertThat(ids).hasSize(threads * perThread);
}
```

- [ ] **Step 2: Add IdService smoke test**

```java
@Test
void routesSnowflakeAndSegmentNamespaces() {
    SnowflakeIdGenerator snowflake = new SnowflakeIdGenerator(1, 1);
    SegmentIdGenerator segment = new LeafSegmentIdGenerator(new FakeAllocator(100));
    IdService service = new DefaultIdService(snowflake, segment);

    assertThat(service.nextId(IdNamespace.KNOW_POST)).isPositive();
    assertThat(service.nextId(IdNamespace.COMMENT)).isPositive();
    assertThat(service.nextId(IdNamespace.PUBLISH_ATTEMPT)).isPositive();
    assertThat(service.nextId(IdNamespace.OUTBOX)).isPositive();
    assertThat(service.nextId(IdNamespace.FOLLOWING)).isPositive();
    assertThat(service.nextId(IdNamespace.RECONCILIATION_TASK)).isEqualTo(1);
}
```

- [ ] **Step 3: Run ID tests**

Run:

```powershell
& 'C:\Users\Administrator\Desktop\文档\.codex\tools\apache-maven-3.9.6\bin\mvn.cmd' -Dtest="com.tongji.id.*Test" test
```

Expected: PASS.

- [ ] **Step 4: Commit**

```powershell
git add src/test/java/com/tongji/id
git commit -m "test: cover id generation paths"
```

### Task 10: Full Verification

**Files:**

- Modify: `openspec/changes/add-leaf-id-service/tasks.md`

- [ ] **Step 1: Search for prohibited direct ID generation**

Run:

```powershell
rg -n "SnowflakeIdGenerator|ThreadLocalRandom\\.current\\(\\)\\.nextLong|idGen" src/main/java
```

Expected:

- No references to `com.tongji.knowpost.id.SnowflakeIdGenerator`.
- No business primary key generation with `ThreadLocalRandom`.
- Direct `SnowflakeIdGenerator` usage only inside `com.tongji.id`.

- [ ] **Step 2: Run full test suite**

Run:

```powershell
& 'C:\Users\Administrator\Desktop\文档\.codex\tools\apache-maven-3.9.6\bin\mvn.cmd' test
```

Expected: PASS.

- [ ] **Step 3: Run compile with skipped tests if full tests require unavailable services**

Only if Step 2 fails due to unavailable external services:

```powershell
& 'C:\Users\Administrator\Desktop\文档\.codex\tools\apache-maven-3.9.6\bin\mvn.cmd' -DskipTests compile
```

Expected: PASS. Record the external-service test failure in the final implementation note.

- [ ] **Step 4: Mark OpenSpec tasks complete**

Update `openspec/changes/add-leaf-id-service/tasks.md` checkboxes only after verification:

- `1.1` leaf_alloc schema exists.
- `1.2` biz_tag seeds exist.
- `1.3` Snowflake config exists.
- `2.1` `IdService` exists.
- `2.2` Snowflake path exists.
- `2.3` Segment path exists.
- `2.4` namespace routing exists.
- `2.5` failure handling exists.
- `3.1` current and future Snowflake namespaces are supported; existing `post_id`, `outbox_id`, `following_id` are integrated.
- `3.2` Segment namespaces are implemented; `reconciliation_task` is ready for later use.
- `3.3` user IDs unchanged.
- `4.1`, `4.2`, `4.3` tests/smoke checks pass.

- [ ] **Step 5: Commit task closure**

```powershell
git add openspec/changes/add-leaf-id-service/tasks.md
git commit -m "docs: mark leaf id service tasks complete"
```

---

## Risk Checks

- Do not add a `USER` namespace. This change explicitly keeps `users.id` auto-increment.
- Do not introduce a standalone ID microservice process. This is an in-process module.
- Do not make Segment depend on Redis or Kafka.
- Do not change existing table primary key types.
- Do not add admin/audit business code just because `admin_operation` and `audit_log` biz tags are seeded.
- Keep the Segment implementation deterministic first; async preloading can be added later only if tests remain stable.

## Final Completion Criteria

- `IdService.nextId(IdNamespace)` is the only business-facing ID API.
- Existing knowpost and relation code no longer instantiate or inject Snowflake directly.
- Existing relation code no longer uses random long IDs for relationship or outbox rows.
- `leaf_alloc` schema and seed rows are present.
- Snowflake config is externally configurable and range-validated.
- Snowflake concurrent uniqueness test passes.
- Segment segment-switch test passes.
- `openspec/changes/add-leaf-id-service/tasks.md` is fully checked off.
- The change is ready for OpenSpec verification and archive.
