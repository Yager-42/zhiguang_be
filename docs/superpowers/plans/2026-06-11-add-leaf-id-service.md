# add-leaf-id-service Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the existing hard-coded `SnowflakeIdGenerator` with a unified `IdService` that supports Snowflake (high-freq entities) and Leaf Segment (low-freq operational records) via a namespace-to-mode routing enum.

**Architecture:** A new `com.tongji.id` package hosts `IdService`, `IdNamespace` enum, a reconfigured `SnowflakeIdGenerator`, and a `LeafSegmentIdGenerator` with dual-buffer loading. `IdServiceImpl` routes each namespace to the correct generator. `KnowPostServiceImpl` is migrated from the old `SnowflakeIdGenerator` to `IdService`. The old generator is deleted.

**Tech Stack:** Spring Boot 3.2.4, MyBatis (existing), MySQL `leaf_alloc` table, Java `synchronized` + `ExecutorService` for dual-buffer concurrency. No new dependencies needed.

**Key design decisions (from design.md):**
- **Replace** (not wrap) existing `SnowflakeIdGenerator`
- `workerId`/`datacenterId` from `application.yml` static config
- Namespace → mode routing via `IdNamespace` enum (compile-time)
- Segment step fixed at 1000
- Clock rollback ≤5ms: wait; >5ms: throw `ClockBackwardException`
- Segment DB load failure: dual buffer provides resilience; throw only when both buffers exhausted

---

## File Map

**New files:**
- `src/main/java/com/tongji/id/IdMode.java` — enum: SNOWFLAKE, SEGMENT
- `src/main/java/com/tongji/id/IdNamespace.java` — enum routing namespace → mode + bizTag
- `src/main/java/com/tongji/id/IdService.java` — interface: `nextId(IdNamespace)`
- `src/main/java/com/tongji/id/ClockBackwardException.java` — specific exception for large clock skew
- `src/main/java/com/tongji/id/SnowflakeIdGenerator.java` — new in `id` package, configurable from YAML
- `src/main/java/com/tongji/id/segment/LeafAlloc.java` — model for `leaf_alloc` row
- `src/main/java/com/tongji/id/segment/LeafAllocMapper.java` — MyBatis mapper
- `src/main/java/com/tongji/id/segment/LeafAllocService.java` — @Transactional UPDATE+SELECT pair
- `src/main/java/com/tongji/id/segment/SegmentExhaustedException.java` — thrown when dual buffer runs dry
- `src/main/java/com/tongji/id/segment/LeafSegmentIdGenerator.java` — dual-buffer per-biz-tag generator
- `src/main/java/com/tongji/id/IdServiceImpl.java` — routes to Snowflake or Segment
- `src/main/resources/mapper/LeafAllocMapper.xml` — SQL for leaf_alloc
- `src/test/java/com/tongji/id/SnowflakeIdGeneratorTest.java` — unit tests
- `src/test/java/com/tongji/id/segment/LeafSegmentIdGeneratorTest.java` — unit tests
- `src/test/java/com/tongji/id/IdConcurrencyTest.java` — Snowflake concurrency + throughput
- `src/test/java/com/tongji/id/SegmentSmokeTest.java` — Segment end-to-end smoke (requires live DB, @Tag("integration"))

**Modified files:**
- `db/schema.sql` — add `leaf_alloc` table + initial Segment biz tag rows
- `src/main/resources/application.yml` — add `id.snowflake.worker-id` / `datacenter-id`
- `src/main/java/com/tongji/knowpost/service/impl/KnowPostServiceImpl.java` — replace `SnowflakeIdGenerator` with `IdService`

**Deleted files:**
- `src/main/java/com/tongji/knowpost/id/SnowflakeIdGenerator.java` — replaced by new version

---

## Task 1: DB Schema + application.yml

**Files:**
- Modify: `db/schema.sql`
- Modify: `src/main/resources/application.yml`

- [ ] **Step 1: Add leaf_alloc table and initial biz tag rows to db/schema.sql**

Append at the end of `db/schema.sql`:

```sql
CREATE TABLE IF NOT EXISTS leaf_alloc (
    biz_tag     VARCHAR(128) NOT NULL,
    max_id      BIGINT       NOT NULL DEFAULT 1,
    step        INT          NOT NULL DEFAULT 1000,
    description VARCHAR(256),
    update_time TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (biz_tag)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO leaf_alloc (biz_tag, max_id, step, description) VALUES
    ('reconciliation_task', 1, 1000, '对账任务 ID'),
    ('admin_operation',     1, 1000, '管理操作 ID'),
    ('audit_log',           1, 1000, '审计日志 ID')
ON DUPLICATE KEY UPDATE description = VALUES(description);
```

- [ ] **Step 2: Add Snowflake worker config to application.yml**

Add under existing top-level keys (not nested under `spring:`):

```yaml
id:
  snowflake:
    worker-id: ${SNOWFLAKE_WORKER_ID:1}
    datacenter-id: ${SNOWFLAKE_DATACENTER_ID:1}
```

- [ ] **Step 3: Apply schema to local DB**

```bash
# Use docker compose exec (avoids hardcoding container name)
cd /Users/huangyaokai/zhiguang_be
docker compose exec mysql mysql -uroot -proot zhiguang < db/schema.sql
docker compose exec mysql mysql -uroot -proot zhiguang -e "SELECT * FROM leaf_alloc;"
```

If your MySQL service name in docker-compose.yml is different from `mysql` (e.g., `db`), replace accordingly. Check with: `docker compose ps`.

Expected: three rows for `reconciliation_task`, `admin_operation`, `audit_log`.

- [ ] **Step 4: Commit**

```bash
git add db/schema.sql src/main/resources/application.yml
git commit -m "feat: add leaf_alloc schema and snowflake config"
```

---

## Task 2: IdMode + IdNamespace + IdService + ClockBackwardException

**Files:**
- Create: `src/main/java/com/tongji/id/IdMode.java`
- Create: `src/main/java/com/tongji/id/IdNamespace.java`
- Create: `src/main/java/com/tongji/id/IdService.java`
- Create: `src/main/java/com/tongji/id/ClockBackwardException.java`
- Create: `src/main/java/com/tongji/id/segment/SegmentExhaustedException.java`

- [ ] **Step 1: Create IdMode.java**

```java
package com.tongji.id;

public enum IdMode {
    SNOWFLAKE,
    SEGMENT
}
```

- [ ] **Step 2: Create IdNamespace.java**

```java
package com.tongji.id;

public enum IdNamespace {

    // Snowflake: high-throughput business entities
    POST(IdMode.SNOWFLAKE, null),
    COMMENT(IdMode.SNOWFLAKE, null),
    PENDING_COMMENT(IdMode.SNOWFLAKE, null),
    PUBLISH_ATTEMPT(IdMode.SNOWFLAKE, null),
    OUTBOX_EVENT(IdMode.SNOWFLAKE, null),

    // Segment: low-frequency operational records
    RECONCILIATION_TASK(IdMode.SEGMENT, "reconciliation_task"),
    ADMIN_OPERATION(IdMode.SEGMENT, "admin_operation"),
    AUDIT_LOG(IdMode.SEGMENT, "audit_log");

    public final IdMode mode;
    public final String bizTag;  // null for SNOWFLAKE namespaces

    IdNamespace(IdMode mode, String bizTag) {
        this.mode = mode;
        this.bizTag = bizTag;
    }
}
```

- [ ] **Step 3: Create IdService.java**

```java
package com.tongji.id;

public interface IdService {
    long nextId(IdNamespace namespace);
}
```

- [ ] **Step 4: Create ClockBackwardException.java**

```java
package com.tongji.id;

public class ClockBackwardException extends RuntimeException {
    public ClockBackwardException(long offsetMs) {
        super("Clock moved backwards by " + offsetMs + "ms — refusing to generate ID");
    }
}
```

- [ ] **Step 5: Create SegmentExhaustedException.java**

```java
package com.tongji.id.segment;

public class SegmentExhaustedException extends RuntimeException {
    public SegmentExhaustedException(String bizTag) {
        super("Segment buffer exhausted for biz_tag=" + bizTag + " and standby not ready within timeout");
    }
}
```

- [ ] **Step 6: Verify compilation**

```bash
mvn compile -q
```

Expected: `BUILD SUCCESS`

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/tongji/id/
git commit -m "feat: add IdService interface, IdNamespace enum, and exception types"
```

---

## Task 3: SnowflakeIdGenerator (TDD)

**Files:**
- Create: `src/test/java/com/tongji/id/SnowflakeIdGeneratorTest.java`
- Create: `src/main/java/com/tongji/id/SnowflakeIdGenerator.java`

The new `SnowflakeIdGenerator` is functionally identical to the existing `com.tongji.knowpost.id.SnowflakeIdGenerator` but moves to the `com.tongji.id` package and reads `workerId`/`datacenterId` from `@Value` instead of hardcoding them.

Bit layout (unchanged from existing):
- 1 bit sign (always 0)
- 41 bits millisecond timestamp (epoch = 2024-01-01 00:00:00 UTC = `1704067200000L`)
- 5 bits datacenter ID (0–31)
- 5 bits worker ID (0–31)
- 12 bits sequence (0–4095)

- [ ] **Step 1: Write failing tests**

```java
package com.tongji.id;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SnowflakeIdGeneratorTest {

    private final SnowflakeIdGenerator gen = new SnowflakeIdGenerator(1, 1);

    @Test
    void nextId_isPositive() {
        assertThat(gen.nextId()).isPositive();
    }

    @Test
    void nextId_isUnique_singleThread() {
        int count = 10_000;
        Set<Long> ids = new HashSet<>(count);
        for (int i = 0; i < count; i++) {
            ids.add(gen.nextId());
        }
        assertThat(ids).hasSize(count);
    }

    @Test
    void nextId_isMonotonicallyIncreasing() {
        long prev = gen.nextId();
        for (int i = 0; i < 100; i++) {
            long next = gen.nextId();
            assertThat(next).isGreaterThan(prev);
            prev = next;
        }
    }

    @Test
    void nextId_throwsClockBackwardExceptionForLargeSkew() {
        // Capture a fixed "now" so the rollback offset is always deterministic.
        // Two live calls to System.currentTimeMillis() could drift between invocations
        // and produce an unpredictable offset.
        SnowflakeIdGenerator skewed = new SnowflakeIdGenerator(1, 1) {
            private final long fixedNow = System.currentTimeMillis();
            private int call = 0;
            @Override
            protected long currentTimeMillis() {
                // 1st call: fixedNow  → primes lastTimestamp
                // 2nd call: fixedNow - 10  → offset = 10ms > 5ms tolerance → throws
                return call++ == 0 ? fixedNow : fixedNow - 10;
            }
        };
        skewed.nextId();  // prime lastTimestamp = fixedNow
        assertThatThrownBy(skewed::nextId)
            .isInstanceOf(ClockBackwardException.class)
            .hasMessageContaining("10ms");
    }

    @Test
    void constructor_throwsForInvalidWorkerId() {
        assertThatThrownBy(() -> new SnowflakeIdGenerator(32, 1))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void constructor_throwsForInvalidDatacenterId() {
        assertThatThrownBy(() -> new SnowflakeIdGenerator(1, 32))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
```

- [ ] **Step 2: Run tests — confirm they fail**

```bash
mvn test -Dtest=SnowflakeIdGeneratorTest -q 2>&1 | tail -5
```

Expected: compilation error — `SnowflakeIdGenerator` not found in `com.tongji.id`

- [ ] **Step 3: Create SnowflakeIdGenerator.java**

```java
package com.tongji.id;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class SnowflakeIdGenerator {

    private static final long EPOCH = 1704067200000L; // 2024-01-01 UTC
    private static final int WORKER_BITS = 5;
    private static final int DATACENTER_BITS = 5;
    private static final int SEQUENCE_BITS = 12;

    private static final long MAX_WORKER_ID = ~(-1L << WORKER_BITS);       // 31
    private static final long MAX_DATACENTER_ID = ~(-1L << DATACENTER_BITS); // 31
    private static final long MAX_SEQUENCE = ~(-1L << SEQUENCE_BITS);        // 4095

    private static final int WORKER_SHIFT = SEQUENCE_BITS;                   // 12
    private static final int DATACENTER_SHIFT = SEQUENCE_BITS + WORKER_BITS; // 17
    private static final int TIMESTAMP_SHIFT = DATACENTER_SHIFT + DATACENTER_BITS; // 22

    private static final long CLOCK_ROLLBACK_TOLERANCE_MS = 5L;

    private final long workerId;
    private final long datacenterId;

    private long lastTimestamp = -1L;
    private long sequence = 0L;

    public SnowflakeIdGenerator(
            @Value("${id.snowflake.worker-id:1}") int workerId,
            @Value("${id.snowflake.datacenter-id:1}") int datacenterId) {
        if (workerId > MAX_WORKER_ID || workerId < 0) {
            throw new IllegalArgumentException("workerId must be in [0, " + MAX_WORKER_ID + "]");
        }
        if (datacenterId > MAX_DATACENTER_ID || datacenterId < 0) {
            throw new IllegalArgumentException("datacenterId must be in [0, " + MAX_DATACENTER_ID + "]");
        }
        this.workerId = workerId;
        this.datacenterId = datacenterId;
    }

    public synchronized long nextId() {
        long timestamp = currentTimeMillis();

        if (timestamp < lastTimestamp) {
            long offset = lastTimestamp - timestamp;
            if (offset <= CLOCK_ROLLBACK_TOLERANCE_MS) {
                // Small rollback — wait for clock to catch up
                try {
                    Thread.sleep(offset << 1);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                timestamp = currentTimeMillis();
                if (timestamp < lastTimestamp) {
                    throw new ClockBackwardException(lastTimestamp - timestamp);
                }
            } else {
                throw new ClockBackwardException(offset);
            }
        }

        if (timestamp == lastTimestamp) {
            sequence = (sequence + 1) & MAX_SEQUENCE;
            if (sequence == 0) {
                timestamp = waitForNextMillis(lastTimestamp);
            }
        } else {
            sequence = 0L;
        }

        lastTimestamp = timestamp;
        return ((timestamp - EPOCH) << TIMESTAMP_SHIFT)
                | (datacenterId << DATACENTER_SHIFT)
                | (workerId << WORKER_SHIFT)
                | sequence;
    }

    protected long currentTimeMillis() {
        return System.currentTimeMillis();
    }

    private long waitForNextMillis(long lastTs) {
        long ts = currentTimeMillis();
        while (ts <= lastTs) {
            ts = currentTimeMillis();
        }
        return ts;
    }
}
```

- [ ] **Step 4: Run tests — all should pass**

```bash
mvn test -Dtest=SnowflakeIdGeneratorTest -q
```

Expected: `Tests run: 6, Failures: 0, Errors: 0`

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/tongji/id/SnowflakeIdGenerator.java \
        src/test/java/com/tongji/id/SnowflakeIdGeneratorTest.java
git commit -m "feat: add SnowflakeIdGenerator in id package with configurable worker/datacenter"
```

---

## Task 4: LeafAlloc Model + Mapper

**Files:**
- Create: `src/main/java/com/tongji/id/segment/LeafAlloc.java`
- Create: `src/main/java/com/tongji/id/segment/LeafAllocMapper.java`
- Create: `src/main/resources/mapper/LeafAllocMapper.xml`
- Create: `src/main/java/com/tongji/id/segment/LeafAllocService.java`

- [ ] **Step 1: Create LeafAlloc.java**

```java
package com.tongji.id.segment;

public class LeafAlloc {
    private String bizTag;
    private long maxId;
    private int step;

    public String getBizTag() { return bizTag; }
    public void setBizTag(String bizTag) { this.bizTag = bizTag; }
    public long getMaxId() { return maxId; }
    public void setMaxId(long maxId) { this.maxId = maxId; }
    public int getStep() { return step; }
    public void setStep(int step) { this.step = step; }
}
```

- [ ] **Step 2: Create LeafAllocMapper.java**

```java
package com.tongji.id.segment;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface LeafAllocMapper {

    /** Atomically advances max_id by step for the given biz_tag. */
    void updateMaxId(@Param("bizTag") String bizTag);

    /** Reads the current row after updateMaxId. Must be called in same transaction. */
    LeafAlloc getByTag(@Param("bizTag") String bizTag);
}
```

- [ ] **Step 3: Create LeafAllocMapper.xml**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="com.tongji.id.segment.LeafAllocMapper">

    <update id="updateMaxId">
        UPDATE leaf_alloc
        SET max_id = max_id + step, update_time = NOW()
        WHERE biz_tag = #{bizTag}
    </update>

    <select id="getByTag" resultType="com.tongji.id.segment.LeafAlloc">
        SELECT biz_tag, max_id, step
        FROM leaf_alloc
        WHERE biz_tag = #{bizTag}
    </select>

</mapper>
```

- [ ] **Step 4: Create LeafAllocService.java**

The UPDATE + SELECT must be in a single transaction so the SELECT reads *our* updated value, not a subsequent update by another thread.

```java
package com.tongji.id.segment;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.Resource;

@Service
public class LeafAllocService {

    @Resource
    private LeafAllocMapper mapper;

    /**
     * Atomically increments max_id by step and returns the new allocation.
     * The returned range is [maxId - step, maxId).
     */
    @Transactional
    public LeafAlloc loadNextSegment(String bizTag) {
        mapper.updateMaxId(bizTag);
        return mapper.getByTag(bizTag);
    }
}
```

- [ ] **Step 5: Verify compilation**

```bash
mvn compile -q
```

Expected: `BUILD SUCCESS`

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/tongji/id/segment/ src/main/resources/mapper/LeafAllocMapper.xml
git commit -m "feat: add LeafAlloc model, mapper, and transactional loader"
```

---

## Task 5: LeafSegmentIdGenerator (TDD)

**Files:**
- Create: `src/test/java/com/tongji/id/segment/LeafSegmentIdGeneratorTest.java`
- Create: `src/main/java/com/tongji/id/segment/LeafSegmentIdGenerator.java`

**How dual buffer works:**
1. On first call, loads the active buffer synchronously: calls `loadNextSegment("tag")` which returns `(maxId, step)`, so range is `[maxId - step, maxId)`.
2. When 50% of active buffer consumed, asynchronously loads standby buffer in background.
3. When active buffer exhausted: blocks until standby ready (up to 5s), then swaps.
4. Throws `SegmentExhaustedException` if standby not ready within 5s.

- [ ] **Step 1: Write failing tests**

```java
package com.tongji.id.segment;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LeafSegmentIdGeneratorTest {

    @Mock
    LeafAllocService allocService;

    private LeafAlloc alloc(long maxId) {
        LeafAlloc a = new LeafAlloc();
        a.setBizTag("test");
        a.setMaxId(maxId);
        a.setStep(1000);
        return a;
    }

    @Test
    void nextId_firstBatchStartsFromOne() {
        // alloc(1001): maxId=1001, step=1000 → range [maxId-step, maxId) = [1, 1001) → IDs: 1..1000
        when(allocService.loadNextSegment("test"))
            .thenReturn(alloc(1001L))   // active buffer: range [1, 1001)
            .thenReturn(alloc(2001L));  // standby buffer loaded at 50%: range [1001, 2001)

        LeafSegmentIdGenerator gen = new LeafSegmentIdGenerator("test", allocService,
            Executors.newSingleThreadExecutor());

        assertThat(gen.nextId()).isEqualTo(1L);
        assertThat(gen.nextId()).isEqualTo(2L);
        assertThat(gen.nextId()).isEqualTo(3L);
    }

    @Test
    void nextId_returnsUniqueIds_acrossBufferSwitch() throws InterruptedException {
        when(allocService.loadNextSegment("test"))
            .thenReturn(alloc(1001L))
            .thenReturn(alloc(2001L))
            .thenReturn(alloc(3001L));

        LeafSegmentIdGenerator gen = new LeafSegmentIdGenerator("test", allocService,
            Executors.newSingleThreadExecutor());

        Set<Long> ids = new HashSet<>();
        // Consume first full buffer (1000 IDs) and start of second buffer
        for (int i = 0; i < 1100; i++) {
            ids.add(gen.nextId());
        }
        assertThat(ids).hasSize(1100);
    }

    @Test
    void nextId_switchesToStandbyAfterActiveExhausted() throws InterruptedException {
        when(allocService.loadNextSegment("test"))
            .thenReturn(alloc(1001L))   // active: [1, 1001)
            .thenReturn(alloc(2001L));  // standby: [1001, 2001)

        LeafSegmentIdGenerator gen = new LeafSegmentIdGenerator("test", allocService,
            Executors.newSingleThreadExecutor());

        // Consume first buffer
        for (int i = 0; i < 1000; i++) gen.nextId();

        // Brief wait for background standby load triggered at 50%
        Thread.sleep(200);

        // 1001st ID should be first of second buffer
        assertThat(gen.nextId()).isEqualTo(1001L);
    }

    @Test
    void nextId_throwsWhenBothBuffersUnavailable() {
        // Simulate permanent DB failure — loadNextSegment always throws
        when(allocService.loadNextSegment("test"))
            .thenThrow(new RuntimeException("DB down"));

        LeafSegmentIdGenerator gen = new LeafSegmentIdGenerator("test", allocService,
            Executors.newSingleThreadExecutor());

        assertThatThrownBy(gen::nextId)
            .isInstanceOf(SegmentExhaustedException.class);
    }
}
```

- [ ] **Step 2: Run tests to confirm they fail**

```bash
mvn test -Dtest=LeafSegmentIdGeneratorTest -q 2>&1 | tail -5
```

Expected: compilation error — `LeafSegmentIdGenerator` not found

- [ ] **Step 3: Create LeafSegmentIdGenerator.java**

```java
package com.tongji.id.segment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutorService;

public class LeafSegmentIdGenerator {

    private static final Logger log = LoggerFactory.getLogger(LeafSegmentIdGenerator.class);
    private static final int LOAD_THRESHOLD_PERCENT = 50;
    private static final long WAIT_TIMEOUT_MS = 5000L;

    private final String bizTag;
    private final LeafAllocService allocService;
    private final ExecutorService loader;

    // Active buffer: IDs in [activeMin, activeMax)
    private long activeMin;
    private long activeMax;
    private long cursor;

    // Standby buffer
    private long standbyMin;
    private long standbyMax;
    private volatile boolean standbyReady = false;
    private volatile boolean loadingStandby = false;

    private boolean initialized = false;

    public LeafSegmentIdGenerator(String bizTag, LeafAllocService allocService,
                                  ExecutorService loader) {
        this.bizTag = bizTag;
        this.allocService = allocService;
        this.loader = loader;
    }

    public synchronized long nextId() {
        if (!initialized) {
            initActiveBuffer();
            initialized = true;
        }

        if (cursor >= activeMax) {
            switchToStandby();
        }

        long id = cursor++;
        maybeLoadStandby();
        return id;
    }

    private void initActiveBuffer() {
        try {
            loadIntoActive(allocService.loadNextSegment(bizTag));
        } catch (Exception e) {
            throw new SegmentExhaustedException(bizTag);
        }
    }

    private void loadIntoActive(LeafAlloc alloc) {
        activeMax = alloc.getMaxId();
        activeMin = activeMax - alloc.getStep();
        cursor = activeMin;
    }

    private void switchToStandby() {
        long deadline = System.currentTimeMillis() + WAIT_TIMEOUT_MS;
        while (!standbyReady) {
            if (!loadingStandby) triggerStandbyLoad();
            if (System.currentTimeMillis() > deadline) {
                throw new SegmentExhaustedException(bizTag);
            }
            try {
                wait(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SegmentExhaustedException(bizTag);
            }
        }
        activeMin = standbyMin;
        activeMax = standbyMax;
        cursor = activeMin;
        standbyReady = false;
    }

    private void maybeLoadStandby() {
        if (standbyReady || loadingStandby) return;
        long total = activeMax - activeMin;
        if (total <= 0) return;
        long used = cursor - activeMin;
        if (used * 100 / total >= LOAD_THRESHOLD_PERCENT) {
            triggerStandbyLoad();
        }
    }

    private void triggerStandbyLoad() {
        if (loadingStandby) return;
        loadingStandby = true;
        loader.submit(() -> {
            try {
                LeafAlloc alloc = allocService.loadNextSegment(bizTag);
                synchronized (LeafSegmentIdGenerator.this) {
                    standbyMax = alloc.getMaxId();
                    standbyMin = standbyMax - alloc.getStep();
                    standbyReady = true;
                    notifyAll();
                }
            } catch (Exception e) {
                log.error("Failed to load standby segment for {}: {}", bizTag, e.getMessage());
            } finally {
                loadingStandby = false;
            }
        });
    }
}
```

- [ ] **Step 4: Run tests — all should pass**

```bash
mvn test -Dtest=LeafSegmentIdGeneratorTest -q
```

Expected: `Tests run: 4, Failures: 0, Errors: 0`

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/tongji/id/segment/LeafSegmentIdGenerator.java \
        src/test/java/com/tongji/id/segment/LeafSegmentIdGeneratorTest.java
git commit -m "feat: implement LeafSegmentIdGenerator with dual-buffer loading"
```

---

## Task 6: IdServiceImpl

**Files:**
- Create: `src/main/java/com/tongji/id/IdServiceImpl.java`

- [ ] **Step 1: Create IdServiceImpl.java**

```java
package com.tongji.id;

import com.tongji.id.segment.LeafAllocService;
import com.tongji.id.segment.LeafSegmentIdGenerator;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

@Service
public class IdServiceImpl implements IdService {

    @Resource
    private SnowflakeIdGenerator snowflakeIdGenerator;

    @Resource
    private LeafAllocService leafAllocService;

    private final ConcurrentHashMap<String, LeafSegmentIdGenerator> segmentGenerators
        = new ConcurrentHashMap<>();

    @Override
    public long nextId(IdNamespace namespace) {
        return switch (namespace.mode) {
            case SNOWFLAKE -> snowflakeIdGenerator.nextId();
            case SEGMENT -> getOrCreateSegment(namespace.bizTag).nextId();
        };
    }

    private LeafSegmentIdGenerator getOrCreateSegment(String bizTag) {
        return segmentGenerators.computeIfAbsent(bizTag,
            tag -> new LeafSegmentIdGenerator(tag, leafAllocService,
                Executors.newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r, "segment-loader-" + tag);
                    t.setDaemon(true);
                    return t;
                })));
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
git add src/main/java/com/tongji/id/IdServiceImpl.java
git commit -m "feat: implement IdServiceImpl routing namespaces to Snowflake or Segment"
```

---

## Task 7: Migrate KnowPostServiceImpl + Delete Old Generator

**Files:**
- Modify: `src/main/java/com/tongji/knowpost/service/impl/KnowPostServiceImpl.java`
- Delete: `src/main/java/com/tongji/knowpost/id/SnowflakeIdGenerator.java`

**Context:** `KnowPostServiceImpl` uses constructor injection. `idGen.nextId()` appears 4 times:
- Line 88: `createDraft()` — generates post ID → use `IdNamespace.POST`
- Line 167: `updateMetadata()` — generates outbox event ID → use `IdNamespace.OUTBOX_EVENT`
- Line 193: `publish()` — generates outbox event ID → use `IdNamespace.OUTBOX_EVENT`
- Line 258: `delete()` — generates outbox event ID → use `IdNamespace.OUTBOX_EVENT`

- [ ] **Step 1: Write a failing migration validation test**

> **Why reflection?** `KnowPostServiceImpl` has many injected fields. Using `@InjectMocks` + calling business methods injects `null` for all unmocked dependencies and causes NPE before we even verify the ID field. A reflection-based structural test is simpler, deterministic, and perfectly captures what the migration verifies: "does the class use `IdService` instead of `SnowflakeIdGenerator`?"

In `src/test/java/com/tongji/knowpost/service/KnowPostIdServiceMigrationTest.java`:

```java
package com.tongji.knowpost.service;

import com.tongji.id.IdService;
import com.tongji.knowpost.service.impl.KnowPostServiceImpl;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class KnowPostIdServiceMigrationTest {

    @Test
    void knowPostServiceImpl_hasIdServiceField_notSnowflakeIdGenerator() {
        List<String> fieldTypeNames = Arrays.stream(KnowPostServiceImpl.class.getDeclaredFields())
            .map(Field::getType)
            .map(Class::getSimpleName)
            .collect(Collectors.toList());

        assertThat(fieldTypeNames)
            .as("KnowPostServiceImpl must have an IdService field after migration")
            .contains("IdService");

        assertThat(fieldTypeNames)
            .as("KnowPostServiceImpl must not have SnowflakeIdGenerator field after migration")
            .doesNotContain("SnowflakeIdGenerator");
    }

    @Test
    void idServiceField_isOfCorrectType() throws NoSuchFieldException {
        Field field = KnowPostServiceImpl.class.getDeclaredField("idService");
        assertThat(field.getType()).isEqualTo(IdService.class);
    }
}
```

This test fails before migration (`SnowflakeIdGenerator` present, `IdService` absent) and passes after.

- [ ] **Step 2: Run test to confirm it fails**

```bash
mvn test -Dtest=KnowPostIdServiceMigrationTest -q 2>&1 | tail -5
```

Expected: compilation error or failure — `KnowPostServiceImpl` still imports `SnowflakeIdGenerator`

- [ ] **Step 3: Update KnowPostServiceImpl — replace import and field**

Replace:
```java
import com.tongji.knowpost.id.SnowflakeIdGenerator;
```

With:
```java
import com.tongji.id.IdNamespace;
import com.tongji.id.IdService;
```

Replace the field declaration (currently around line 42):
```java
@Resource
private final SnowflakeIdGenerator idGen;
```

With:
```java
@Resource
private final IdService idService;
```

- [ ] **Step 4: Update constructor parameter**

In the constructor signature, replace:
```java
SnowflakeIdGenerator idGen,
```

With:
```java
IdService idService,
```

And update the assignment:
```java
this.idGen = idGen;
```

To:
```java
this.idService = idService;
```

- [ ] **Step 5: Replace all idGen.nextId() call sites**

Line 88 (`createDraft`):
```java
long id = idGen.nextId();
```
→
```java
long id = idService.nextId(IdNamespace.POST);
```

Line 167 (`updateMetadata` outbox):
```java
long outId = idGen.nextId();
```
→
```java
long outId = idService.nextId(IdNamespace.OUTBOX_EVENT);
```

Line 193 (`publish` outbox):
```java
long outId = idGen.nextId();
```
→
```java
long outId = idService.nextId(IdNamespace.OUTBOX_EVENT);
```

Line 258 (`delete` outbox):
```java
long outId = idGen.nextId();
```
→
```java
long outId = idService.nextId(IdNamespace.OUTBOX_EVENT);
```

- [ ] **Step 6: Delete old SnowflakeIdGenerator**

```bash
rm src/main/java/com/tongji/knowpost/id/SnowflakeIdGenerator.java
# If the knowpost/id/ directory is now empty, remove it too:
rmdir src/main/java/com/tongji/knowpost/id/ 2>/dev/null || true
```

- [ ] **Step 7: Compile and run all KnowPost tests**

```bash
mvn compile -q
mvn test -Dtest="*KnowPost*" -q
```

Expected: `BUILD SUCCESS`, no regressions

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/tongji/knowpost/service/impl/KnowPostServiceImpl.java
git add src/test/java/com/tongji/knowpost/service/KnowPostIdServiceMigrationTest.java
git rm src/main/java/com/tongji/knowpost/id/SnowflakeIdGenerator.java
git commit -m "feat: migrate KnowPostServiceImpl to IdService, remove old SnowflakeIdGenerator"
```

---

## Task 8: Concurrency Uniqueness + Throughput Test

**Files:**
- Create: `src/test/java/com/tongji/id/IdConcurrencyTest.java`

- [ ] **Step 1: Write concurrency and throughput tests**

```java
package com.tongji.id;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

class IdConcurrencyTest {

    private final SnowflakeIdGenerator gen = new SnowflakeIdGenerator(1, 1);

    @Test
    void snowflake_isUnique_underConcurrentLoad() throws InterruptedException {
        int threads = 10;
        int idsPerThread = 1000;
        Set<Long> ids = Collections.synchronizedSet(new HashSet<>(threads * idsPerThread));
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int j = 0; j < idsPerThread; j++) {
                        ids.add(gen.nextId());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        done.await();
        pool.shutdown();

        assertThat(ids).hasSize(threads * idsPerThread);
    }

    @Test
    void snowflake_throughput_100k_ids_under_2_seconds() {
        // 2s threshold: single-threaded Snowflake easily does 100k in <200ms on modern hardware,
        // but 2s gives CI machines headroom without making the test meaningless.
        int count = 100_000;
        long start = System.currentTimeMillis();
        for (int i = 0; i < count; i++) {
            gen.nextId();
        }
        long elapsed = System.currentTimeMillis() - start;
        assertThat(elapsed).as("Generated %d Snowflake IDs in %dms", count, elapsed)
            .isLessThan(2000L);
    }
}
```

- [ ] **Step 2: Add Segment smoke test (requires local DB with leaf_alloc table from Task 1)**

Add a third test method to `IdConcurrencyTest.java` — only runs when `@Tag("integration")` is present (skipped in normal unit test runs):

```java
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

// Add this as a SEPARATE file to avoid loading Spring for the other two tests:
// src/test/java/com/tongji/id/SegmentSmokeTest.java

package com.tongji.id;

import com.tongji.id.segment.LeafAllocService;
import com.tongji.id.segment.LeafSegmentIdGenerator;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Tag("integration")
class SegmentSmokeTest {

    @Autowired
    LeafAllocService allocService;

    @Test
    void segment_roundTrip_generatesUniqueIds() {
        LeafSegmentIdGenerator gen = new LeafSegmentIdGenerator(
            "reconciliation_task", allocService,
            Executors.newSingleThreadExecutor());

        Set<Long> ids = new HashSet<>(50);
        for (int i = 0; i < 50; i++) {
            ids.add(gen.nextId());
        }
        assertThat(ids).hasSize(50);
        assertThat(ids).allSatisfy(id -> assertThat(id).isPositive());
    }
}
```

Run integration test only (requires MySQL + leaf_alloc from Task 1):

```bash
mvn test -Dtest=SegmentSmokeTest -q
```

Expected: `Tests run: 1, Failures: 0` — confirms DB path, `@Transactional` UPDATE+SELECT, and buffer load all work end-to-end.

> **Note:** If `@SpringBootTest` fails due to missing Cassandra/ES (not yet running), add `@SpringBootTest(properties = {"spring.cassandra.schema-action=none"})` or run with all docker services up.

- [ ] **Step 3: Run Snowflake concurrency tests**

```bash
mvn test -Dtest=IdConcurrencyTest -q
```

Expected: `Tests run: 2, Failures: 0, Errors: 0`

- [ ] **Step 5: Run full test suite**

```bash
mvn test -q
```

Expected: `BUILD SUCCESS` — all tests pass

- [ ] **Step 6: Commit**

```bash
git add src/test/java/com/tongji/id/IdConcurrencyTest.java \
        src/test/java/com/tongji/id/SegmentSmokeTest.java
git commit -m "test: add Snowflake concurrency, throughput, and Segment smoke tests"
```

---

## Self-Review Checklist

**tasks.md coverage:**
- [x] 1.1 `leaf_alloc` table (Task 1)
- [x] 1.2 `reconciliation_task`, `admin_operation`, `audit_log` biz tags (Task 1)
- [x] 1.3 workerId/datacenterId config (Task 1 + Task 3)
- [x] 2.1 `IdService` interface with namespace routing (Task 2)
- [x] 2.2 Snowflake path (Task 3 + 6)
- [x] 2.3 Segment dual buffer (Task 5 + 6)
- [x] 2.4 Namespace → mode routing (Task 2 `IdNamespace` enum)
- [x] 2.5 Segment load failure handling (Task 5 `SegmentExhaustedException`); clock rollback (Task 3 `ClockBackwardException`)
- [x] 3.1 post_id, outbox_id → Snowflake via KnowPostServiceImpl migration (Task 7); COMMENT, PENDING_COMMENT, PUBLISH_ATTEMPT namespaces defined for future use (Task 2)
- [x] 3.2 reconciliation_task_id, admin_operation_id, audit_log_id → Segment namespaces defined (Task 2); biz tags in DB (Task 1)
- [x] 3.3 user_id auto-increment — not touched (verified: no changes to users table)
- [x] 4.1 Snowflake concurrency uniqueness test (Task 8)
- [x] 4.2 Segment dual buffer switch test (Task 5 `nextId_switchesToStandbyAfterActiveExhausted`)
- [x] 4.3 Snowflake smoke + throughput test (Task 8 `snowflake_throughput_100k_ids_under_2_seconds`); Segment smoke covered by `SegmentSmokeTest` (Task 8, requires live DB, `@Tag("integration")`)

**No placeholders.**

**Bugs fixed during review:**
- Task 3: Clock rollback test made deterministic — capture `fixedNow` once, avoid drift between two `System.currentTimeMillis()` calls
- Task 7: Migration test replaced with reflection test — avoids `createDraft` signature unknown issue and NPE from null-injected dependencies
- Task 1: MySQL schema apply changed from `docker exec zhiguang-mysql` to `docker compose exec mysql` — avoids hardcoded container name assumption
- Task 5: Removed self-contradicting multi-line comment in `nextId_firstBatchStartsFromOne`
- Task 8: Throughput threshold changed from 1s to 2s for CI resilience

**Type consistency:**
- `IdNamespace.POST`, `IdNamespace.OUTBOX_EVENT` used in Task 7 match definitions in Task 2
- `LeafAlloc.getMaxId()`, `LeafAlloc.getStep()` used in Task 5 match field definitions in Task 4
- `LeafAllocService.loadNextSegment(String)` used in Tasks 5 + 6 matches definition in Task 4
- `SnowflakeIdGenerator(int, int)` constructor used in tests matches implementation in Task 3
- `ClockBackwardException(long)` in Task 3 test `hasMessageContaining("10ms")` matches constructor `"...by " + offsetMs + "ms"` in Task 2
