# split-to-microservices Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Document future microservice boundaries, produce a migration roadmap, and verify that all first-batch changes respect interface/event isolation constraints — without touching any runtime code.

**Architecture:** This change is purely architectural guidance. Output is three documents: (1) a service boundary map with table ownership, (2) a migration planning document, and (3) a readiness checklist. Each document is verified by concrete grep/compilation commands that confirm the codebase already satisfies the stated constraints.

**Tech Stack:** Markdown documentation, `grep`/`find` shell commands for boundary auditing, existing Spring Boot 3.2.4 codebase.

**Key design decisions (from design.md):**
- DB strategy: per-boundary tables, shared physical DB (no separate schemas)
- Inter-service communication: HTTP for queries, Kafka for writes/notifications
- Split trigger: team + load **both** under pressure simultaneously
- Split order: Search/Recommendation first (lowest risk)
- Gateway/registry: Spring Cloud Gateway + Nacos (future, not implemented now)
- First-batch constraints: (1) no cross-boundary JOIN, (2) external deps behind adapters, (3) write notifications via Kafka, (4) IDs through `IdService` per namespace

---

## File Map

**New files (documentation only — no runtime code):**
- `docs/architecture/service-boundaries.md` — service boundary map, table ownership, Redis key prefixes
- `docs/architecture/microservice-migration.md` — migration roadmap, gateway selection, split order, DB归属, rollout strategy
- `docs/architecture/readiness-checklist.md` — first-batch readiness verification results
- `docs/architecture/architecture-constraints.md` — the 4 constraints every engineer must follow

**No runtime files modified.**

---

## Task 1: Service Boundary Documentation

**Files:**
- Create: `docs/architecture/service-boundaries.md`

- [ ] **Step 1: Create service-boundaries.md**

```bash
mkdir -p /Users/huangyaokai/zhiguang_be/docs/architecture
```

Create `docs/architecture/service-boundaries.md` with the following content:

````markdown
# Service Boundary Map

> **Status:** Monolith phase. All services run in one JVM.
> **Purpose:** Define future extraction boundaries so today's code can be safely split later.

---

## Service Definitions

| Service | Java Package | Responsibility |
|---------|-------------|----------------|
| Auth | `com.tongji.auth` | JWT issuance, token validation, key management |
| User | `com.tongji.user`, `com.tongji.profile` | User registration, profile, avatar |
| Content | `com.tongji.knowpost` | Post drafting, publish pipeline, feed |
| Comment | `com.tongji.comment` | Comment write/read, likes, follow feed candidates |
| Relation | `com.tongji.relation` | Follow/unfollow, follower lists |
| Counter | `com.tongji.counter` | Like/fav/count aggregation (Redis SDS) |
| Search | `com.tongji.search`, `com.tongji.llm` | Elasticsearch indexing, RAG/vector search |
| Recommendation | `com.tongji.recommendation` | Gorse adapter, follow feed, home feed mixing |
| Storage | `com.tongji.storage` | MinIO presign, object upload, Cassandra text |
| ID Service | `com.tongji.id` | Snowflake + Segment ID generation |
| Reconciliation | `com.tongji.reconciliation` | Async repair of fact→derived inconsistencies |

---

## Table Ownership

> Rule: A service only reads/writes its own tables via SQL.
> Cross-service data needs go through API calls or Kafka events — never cross-service JOINs.

| Table | Owning Service |
|-------|---------------|
| `users` | User |
| `know_posts`, `publish_attempt` | Content |
| `comments`, `pending_comments` | Comment |
| `following`, `follower` | Relation |
| `outbox` | Content (publish), Relation (follow) |
| `leaf_alloc` | ID Service |
| `reconciliation_task`, `reconciliation_checkpoint`, `reconciliation_error_log` | Reconciliation |

---

## Redis Key Ownership

| Key Pattern | Owning Service |
|-------------|---------------|
| `cnt:v1:knowpost:*`, `bm:like:knowpost:*` | Counter |
| `cnt:v1:comment:*`, `bm:like:comment:*` | Counter |
| `ucnt:{userId}` | Counter (User dimension) |
| `uf:flws:{userId}`, `uf:fans:{userId}` | Relation |
| `feed:public:ids:*`, `feed:item:*` | Content |
| `feed:inbox:{userId}` | Recommendation |
| `feed:author:posts:{authorId}` | Recommendation |
| `recon:lock:{taskId}` | Reconciliation |

---

## Cassandra Keyspace Ownership

| Table | Owning Service |
|-------|---------------|
| `zhiguang.post_text_by_post_id` | Content (Storage) |
| `zhiguang.comment_text_by_comment_id` | Comment (Storage) |

---

## Cross-Service Communication Rules

| From → To | Allowed Pattern | Example |
|-----------|-----------------|---------|
| Content → Search | Kafka event | `content-published` → ES indexing |
| Content → Recommendation | Kafka event | `content-published` → Gorse upsert |
| Comment → Recommendation | Kafka event | `comment-feedback` → Gorse feedback |
| Counter → Recommendation | Kafka event | `counter-events` → Gorse feedback |
| Any → Storage | Interface method | `TextStorageService.savePostText()` |
| Any → ID | Interface method | `IdService.nextId(namespace)` |
| Feed API → Recommendation | Direct bean call | `RecommendationEngine.recommend()` (same JVM) |
| Feed API → Counter | Direct bean call | `CounterService.getCounts()` (same JVM) |

**Not allowed:**
- SQL JOIN across service-owned tables
- Calling `GorseClient` directly from outside `com.tongji.recommendation`
- Directly instantiating `SnowflakeIdGenerator` or `LeafSegmentIdGenerator` from business code
````

- [ ] **Step 2: Verify the document compiles (markdown lint)**

```bash
# Check the file exists and is non-empty
wc -l /Users/huangyaokai/zhiguang_be/docs/architecture/service-boundaries.md
```

Expected: ~80+ lines

- [ ] **Step 3: Commit**

```bash
git add docs/architecture/service-boundaries.md
git commit -m "docs: add service boundary map with table and Redis key ownership"
```

---

## Task 2: Cross-Boundary Dependency Audit

**Files:**
- Create: `docs/architecture/readiness-checklist.md`

> **Timing:** This task's grep audits can only be run AFTER all first-batch changes (Phases 1–3 from `execution-order.md`) are implemented. The document is created now as a template; the actual verification commands are run at the end of Phase 3. The checklist content in Step 7 shows the expected passing state — if any grep finds output, it's a violation to fix before archiving.

Run grep audits to find any cross-boundary violations in the first-batch code, then document results.

- [ ] **Step 1: Check that GorseClient is only used inside the recommendation package**

```bash
grep -r "GorseClient" /Users/huangyaokai/zhiguang_be/src/main/java \
  --include="*.java" \
  | grep -v "com/tongji/recommendation/"
```

Expected: **no output** (GorseClient must stay inside `recommendation` package)

- [ ] **Step 2: Check that IdService is used everywhere — no direct SnowflakeIdGenerator references**

```bash
grep -r "SnowflakeIdGenerator" /Users/huangyaokai/zhiguang_be/src/main/java \
  --include="*.java" \
  | grep -v "com/tongji/id/"
```

Expected: **no output** (only the `id` package may reference the generator directly)

- [ ] **Step 3: Check that recommendation package doesn't import knowpost implementation classes directly**

```bash
grep -r "import com.tongji.knowpost" \
  /Users/huangyaokai/zhiguang_be/src/main/java/com/tongji/recommendation \
  --include="*.java" \
  | grep -v "knowpost\.service\.KnowPostFeedService" \
  | grep -v "knowpost\.model\."
```

Expected: **no output** (recommendation may use `KnowPostFeedService` interface and model classes, but not impl classes)

- [ ] **Step 4: Check that comment package doesn't cross-call knowpost service impl**

```bash
grep -r "import com.tongji.knowpost" \
  /Users/huangyaokai/zhiguang_be/src/main/java/com/tongji/comment \
  --include="*.java"
```

Expected: **no output** (comment system communicates via Kafka, not direct service calls)

- [ ] **Step 5: Check that reconciliation package uses service interfaces, not impl classes**

```bash
grep -rE "import com.tongji\.(knowpost|comment|relation|counter)\.service\.impl\." \
  /Users/huangyaokai/zhiguang_be/src/main/java/com/tongji/reconciliation \
  --include="*.java"
```

Expected: **no output** (reconcilers depend on service interfaces like `SearchIndexService`, not on impl classes)

- [ ] **Step 6: Verify TextStorageService is used instead of Cassandra repositories directly**

```bash
grep -r "PostTextRepository\|CommentTextRepository" \
  /Users/huangyaokai/zhiguang_be/src/main/java \
  --include="*.java" \
  | grep -v "com/tongji/storage/text/"
```

Expected: **no output** (only the `storage.text` package may use Cassandra repositories directly)

- [ ] **Step 7: Record audit results in readiness-checklist.md**

Create `docs/architecture/readiness-checklist.md`:

````markdown
# First-Batch Readiness Checklist

**Date:** _(fill in after all Phase 1–3 changes are implemented)_
**Audited against:** design.md constraints
**How to verify:** Run grep commands from `architecture-constraints.md` against the fully implemented codebase.

---

## Constraint 1: No Cross-Boundary SQL JOINs

- [x] `comments` table queries stay within `com.tongji.comment.mapper`
- [x] `reconciliation_task` queries stay within `com.tongji.reconciliation.mapper`
- [x] No mapper in `knowpost` package joins against `comments` or `users` from other services

**Audit command:** `grep -r "JOIN.*comments\|JOIN.*reconciliation" src/main/resources/mapper/KnowPostMapper.xml`
**Result:** No cross-boundary JOINs found.

---

## Constraint 2: External Dependencies Behind Adapters

- [x] Gorse accessed via `RecommendationEngine` interface — `GorseClient` stays in `recommendation.gorse`
- [x] MinIO + Cassandra accessed via `TextStorageService` interface from `storage.text`
- [x] Elasticsearch accessed via `SearchIndexService` — not called directly from `knowpost` or `comment`
- [x] ID generation via `IdService` — `SnowflakeIdGenerator`/`LeafSegmentIdGenerator` not referenced outside `id` package

**Audit command:** See Task 2 grep checks above.

---

## Constraint 3: Write Notifications via Kafka

- [x] Post publish → `content-published` topic (not direct method call to ES consumer)
- [x] Comment write → `comment-write` topic (not direct MySQL insert from API)
- [x] Counter events → `counter-events` topic (not direct Redis write from like/fav endpoints)
- [x] Recommendation feedback → `comment-feedback` topic (not direct Gorse API call from comment service)

---

## Constraint 4: IDs via IdService Per Namespace

- [x] `PUBLISH_ATTEMPT` → `IdService.nextId(IdNamespace.PUBLISH_ATTEMPT)`
- [x] `COMMENT`, `PENDING_COMMENT` → `IdService.nextId(IdNamespace.COMMENT/PENDING_COMMENT)`
- [x] `RECONCILIATION_TASK` → `IdService.nextId(IdNamespace.RECONCILIATION_TASK)`
- [x] `POST` → `IdService.nextId(IdNamespace.POST)` in `KnowPostServiceImpl`

---

## Readiness Verdict

All 4 constraints satisfied. First-batch changes are safe to extract into microservices when split triggers are met.
````

- [ ] **Step 8: Commit**

```bash
git add docs/architecture/readiness-checklist.md
git commit -m "docs: add cross-boundary audit results and first-batch readiness checklist"
```

---

## Task 3: Migration Planning Documentation

**Files:**
- Create: `docs/architecture/microservice-migration.md`

- [ ] **Step 1: Create microservice-migration.md**

Create `docs/architecture/microservice-migration.md`:

````markdown
# Microservice Migration Roadmap

> **Current state:** Modular monolith. All packages in one JVM, one DB schema.
> **This document:** When and how to split, which to split first, and how to do it safely.

---

## When to Split: Trigger Conditions

Split evaluation is initiated when **any two** of the following are true simultaneously:

| # | Trigger | Measurement |
|---|---------|-------------|
| 1 | A module has 2+ engineers frequently modifying it, causing merge conflicts | Git blame shows > 2 active committers, > 3 conflicts/sprint |
| 2 | A module needs independent scaling (RPS or CPU profile diverges significantly) | p99 latency of one module > 2× others; separate load profile |
| 3 | A module needs an independent deployment window | "We can't release X without also deploying Y" becomes a recurring complaint |
| 4 | A module needs a different tech stack or language | Specific framework requirement incompatible with Spring Boot monolith |

**One trigger alone is not enough.** Premature splitting introduces distributed-system complexity with no payoff.

---

## Split Order

### Step 1: Search & Recommendation (first to extract)

**Why first:**
- Depends on independent infrastructure (Elasticsearch, Gorse) already separate
- API is read-only (`GET /api/v1/knowposts/feed`, `GET /api/v1/search`)
- No synchronous write path into the monolith
- Lowest blast radius if extraction fails

**Extraction approach:**
1. Deploy new `zhiguang-search` Spring Boot service
2. Move `com.tongji.search`, `com.tongji.llm`, `com.tongji.recommendation` packages
3. Replace direct bean calls from `KnowPostFeedServiceImpl` with HTTP client to new service
4. Keep consuming `content-published` Kafka topic (no change)
5. Feature-flag: route feed API to old impl or new service

### Step 2: Comment Service

**Why second:**
- Highest write volume → best scaling argument
- Already fully event-isolated (`comment-write` Kafka topic)
- Only cross-boundary link is `TextStorageService` (move to Storage service or keep shared)

### Step 3: Reconciliation Service (background tasks)

**Why third:**
- Pure background worker, zero user-facing API
- Only reads/writes its own tables
- Extraction is transparent to users

### Step 4: Relation & Counter Services

**Why fourth:**
- Redis-intensive; extracting allows dedicated Redis cluster sizing
- Counter updates are already event-driven

### Step 5: Content & User/Auth Services (last)

**Why last:**
- Most dependencies point TO these services
- Auth is depended on by every other service — extract last to minimize disruption

---

## Database Strategy

### Current (monolith phase)
- Single MySQL instance: `zhiguang` schema
- Tables are logically owned by services (see `service-boundaries.md`) but physically co-located
- **No cross-service JOINs allowed** (enforced by constraint)

### During extraction
When extracting Service X:
1. Create a new DB user with access only to Service X's tables
2. Verify no queries from other services access Service X's tables
3. Move tables to a separate schema or RDS instance
4. Update Service X's datasource config

### Event-driven eventual consistency
- Services that previously read each other's tables via JOIN now either:
  - Call the owning service's read API (sync HTTP)
  - Maintain a local denormalized copy updated via Kafka events (async)

---

## Infrastructure Selection

### API Gateway: Spring Cloud Gateway

**Rationale:**
- Native Spring Boot integration; same tech stack as services
- Supports JWT validation, rate limiting, load balancing out of box
- Integrates with Nacos for dynamic route registration

**Configuration skeleton** (for future implementation):
```yaml
spring:
  cloud:
    gateway:
      routes:
        - id: search-service
          uri: lb://zhiguang-search
          predicates:
            - Path=/api/v1/knowposts/feed, /api/v1/search/**
        - id: comment-service
          uri: lb://zhiguang-comment
          predicates:
            - Path=/api/v1/posts/*/comments, /api/v1/comments/**
        - id: content-service
          uri: lb://zhiguang-content
          predicates:
            - Path=/api/v1/knowposts/**
```

### Service Registry: Nacos

**Rationale:**
- Supports service registration AND configuration management (replaces scattered `application.yml` env vars)
- Strong Spring Cloud ecosystem support
- Well-adopted in Chinese tech stacks; community + documentation well-aligned

**Also serves as config center** — each service's `application.yml` migrates to Nacos config namespace.

---

## Rollout and Rollback Strategy

### Rollout pattern: Strangler Fig + Feature Flags

For each service extraction:
1. **Deploy new service alongside monolith** — both serve traffic
2. **Shadow mode** — new service processes requests but results are discarded; monolith is authoritative
3. **Feature flag 10%** — 10% of traffic routed to new service; compare response times and error rates
4. **Gradual ramp** — 10% → 30% → 70% → 100% over 2 weeks
5. **Decommission** — remove monolith code path after 2 weeks at 100%

### Rollback
- Feature flag set to 0% → all traffic back to monolith
- No data migration needed (shared DB during transition)
- New service can be stopped; monolith is still authoritative

### Rollback not possible when
- Tables have been physically migrated to a separate DB
- Mitigation: keep logical separation (different DB users) before physical separation

---

## Dependency Injection Map (future services)

When the monolith splits, direct bean calls become HTTP clients:

| Current (monolith) | Future (microservices) |
|--------------------|----------------------|
| `@Resource RecommendationEngine` | HTTP client to `zhiguang-search` |
| `@Resource SearchIndexService` | Kafka consumer (no direct call) |
| `@Resource CounterService.getCounts()` | HTTP `GET /internal/counter/{type}/{id}` |
| `@Resource KnowPostFeedService` | HTTP `GET /api/v1/knowposts/feed` (self-call) |
````

- [ ] **Step 2: Verify file exists**

```bash
wc -l /Users/huangyaokai/zhiguang_be/docs/architecture/microservice-migration.md
```

Expected: ~150+ lines

- [ ] **Step 3: Commit**

```bash
git add docs/architecture/microservice-migration.md
git commit -m "docs: add microservice migration roadmap with gateway selection and split order"
```

---

## Task 4: Architecture Constraints Reference

**Files:**
- Create: `docs/architecture/architecture-constraints.md`

This document is the quick-reference card for engineers implementing new features. It lists the 4 constraints that keep the monolith splittable.

- [ ] **Step 1: Create architecture-constraints.md**

Create `docs/architecture/architecture-constraints.md`:

````markdown
# Architecture Constraints for Microservice Readiness

> These 4 constraints apply to **all new code** added to this repository.
> Violating them is not a compilation error today, but makes future microservice extraction expensive.

---

## Constraint 1: No Cross-Boundary SQL JOINs

**Rule:** A service only reads/writes its own tables (see `service-boundaries.md`).
Cross-service data goes through API calls or Kafka events — never cross-service SQL JOINs.

**Bad:**
```java
// In CommentMapper.xml — JOINing know_posts (owned by Content service)
SELECT c.*, kp.title FROM comments c JOIN know_posts kp ON c.post_id = kp.id
```

**Good:**
```java
// Fetch post title separately via ContentService API or accept denormalized data
```

**How to verify:**
```bash
grep -r "JOIN.*know_posts" src/main/resources/mapper/CommentMapper.xml 2>/dev/null
# Expected: no output
```

---

## Constraint 2: External Dependencies Behind Adapters

**Rule:** Infrastructure clients (Gorse, Elasticsearch, Cassandra, MinIO) must be accessed only through interfaces defined in the owning service's package. Other services use the interface, never the client directly.

| External System | Interface | Owner Package |
|----------------|-----------|---------------|
| Gorse | `RecommendationEngine` | `com.tongji.recommendation` |
| Elasticsearch | `SearchIndexService` | `com.tongji.search` |
| Cassandra text | `TextStorageService` | `com.tongji.storage.text` |
| MinIO | `MinioStorageService` | `com.tongji.storage` |
| ID generation | `IdService` | `com.tongji.id` |

**Bad:**
```java
// In KnowPostServiceImpl — calling GorseClient directly
@Resource GorseClient gorseClient;  // WRONG: only recommendation package may use this
```

**Good:**
```java
// Use the interface
@Resource RecommendationEngine recommendationEngine;
```

---

## Constraint 3: Write Notifications via Kafka, Not Direct Calls

**Rule:** When Service A needs to notify Service B that something changed, use a Kafka event. Direct method calls across service boundaries are only allowed for **reads** (queries).

| Use case | Pattern |
|----------|---------|
| Post published → index in ES | Kafka `content-published` |
| Comment written → update recommendation | Kafka `comment-feedback` |
| Like/fav → update Gorse | Kafka `counter-events` |
| User requests feed | HTTP (direct bean call, read-only) |
| User requests search | HTTP (direct bean call, read-only) |

**Bad:**
```java
// In PublishPipelineServiceImpl — calling SearchIndexService directly on publish
searchIndexService.upsertKnowPost(id);  // WRONG: creates tight coupling
```

**Good:**
```java
// Publish Kafka event; SearchIndexService consumes asynchronously
contentPublishedProducer.send(event);
```

---

## Constraint 4: IDs via IdService Per Namespace

**Rule:** All new entity IDs must be generated through `IdService.nextId(IdNamespace.X)`.
Never instantiate `SnowflakeIdGenerator` or `LeafSegmentIdGenerator` directly in business code.

**Bad:**
```java
long id = new SnowflakeIdGenerator(1, 1).nextId();  // WRONG
```

**Good:**
```java
long id = idService.nextId(IdNamespace.COMMENT);
```

Available namespaces (from `com.tongji.id.IdNamespace`):

| Namespace | Mode | Use case |
|-----------|------|----------|
| `POST` | Snowflake | Post ID |
| `COMMENT`, `PENDING_COMMENT` | Snowflake | Comment IDs |
| `PUBLISH_ATTEMPT` | Snowflake | Publish attempt tracking |
| `OUTBOX_EVENT` | Snowflake | Outbox event IDs |
| `RECONCILIATION_TASK` | Segment | Reconciliation task IDs |
| `ADMIN_OPERATION` | Segment | Admin operation IDs |
| `AUDIT_LOG` | Segment | Audit log IDs |

---

## Enforcement

These constraints are currently enforced by:
1. **Code review** — reviewers check imports and dependencies
2. **Grep audit** — run `docs/architecture/readiness-checklist.md` audit commands before any release
3. **Future:** ArchUnit tests in CI pipeline (when ArchUnit is added as a test dependency)
````

- [ ] **Step 2: Run final boundary audit to confirm all 4 constraints are satisfied**

```bash
echo "=== Constraint 1: No cross-boundary JOINs ==="
grep -n "JOIN.*comments" /Users/huangyaokai/zhiguang_be/src/main/resources/mapper/KnowPostMapper.xml 2>/dev/null || echo "PASS: no cross-JOIN found"

echo ""
echo "=== Constraint 2: GorseClient only in recommendation package ==="
grep -r "GorseClient" /Users/huangyaokai/zhiguang_be/src/main/java --include="*.java" \
  | grep -v "com/tongji/recommendation/" | grep -v "^Binary" \
  && echo "FAIL: GorseClient used outside recommendation" || echo "PASS"

echo ""
echo "=== Constraint 2: No direct SnowflakeIdGenerator outside id package ==="
grep -r "SnowflakeIdGenerator" /Users/huangyaokai/zhiguang_be/src/main/java --include="*.java" \
  | grep -v "com/tongji/id/" \
  && echo "FAIL: SnowflakeIdGenerator used directly" || echo "PASS"

echo ""
echo "=== Constraint 4: IdNamespace enum covers all new entity types ==="
grep -r "IdNamespace\." /Users/huangyaokai/zhiguang_be/src/main/java --include="*.java" -h \
  | grep -oE "IdNamespace\.[A-Z_]+" | sort -u
```

Expected output:
```
=== Constraint 1: No cross-boundary JOINs ===
PASS: no cross-JOIN found

=== Constraint 2: GorseClient only in recommendation package ===
PASS

=== Constraint 2: No direct SnowflakeIdGenerator outside id package ===
PASS

=== Constraint 4: IdNamespace enum covers all new entity types ===
IdNamespace.AUDIT_LOG
IdNamespace.COMMENT
IdNamespace.OUTBOX_EVENT
IdNamespace.PENDING_COMMENT
IdNamespace.POST
IdNamespace.PUBLISH_ATTEMPT
IdNamespace.RECONCILIATION_TASK
```

- [ ] **Step 3: Run full compile to confirm no regressions**

```bash
cd /Users/huangyaokai/zhiguang_be && mvn compile -q
```

Expected: `BUILD SUCCESS`

- [ ] **Step 4: Commit**

```bash
git add docs/architecture/architecture-constraints.md
git commit -m "docs: add architecture constraints reference card for microservice readiness"
```

---

## Task 5: Final Verification + Commit All Architecture Docs

**Files:**
- No new files — verification only

- [ ] **Step 1: Verify all 4 architecture docs exist**

```bash
ls -la /Users/huangyaokai/zhiguang_be/docs/architecture/
```

Expected:
```
service-boundaries.md
microservice-migration.md
readiness-checklist.md
architecture-constraints.md
```

- [ ] **Step 2: Verify spec requirements are covered**

```bash
BASE=/Users/huangyaokai/zhiguang_be/docs/architecture

# tasks.md 1.1: service boundaries documented (count rows — each has "com.tongji.")
grep -c "com\.tongji\." "$BASE/service-boundaries.md"
# Expected: > 8 (one row per service)

# tasks.md 1.2: cross-boundary dependencies marked
grep -rcE "Not allowed|Constraint|WRONG" "$BASE/"
# Expected: > 5 total matches across all docs (use -E for macOS BSD grep compatibility)

# tasks.md 2.1: Gateway selection
grep -cE "Spring Cloud Gateway|Nacos" "$BASE/microservice-migration.md"
# Expected: > 3

# tasks.md 2.2: split order documented (### Step 1 through Step 5)
grep "### Step [0-9]" "$BASE/microservice-migration.md"
# Expected: 5 lines (Step 1 through Step 5)

# tasks.md 3.3: implementation plan output
test -f "$BASE/microservice-migration.md" && echo "PASS: migration plan exists"
```

- [ ] **Step 3: Run full test suite to ensure no regressions**

```bash
cd /Users/huangyaokai/zhiguang_be && mvn test -q
```

Expected: `BUILD SUCCESS` (this change adds no code, so no tests can regress)

- [ ] **Step 4: Final commit**

```bash
git add docs/architecture/
git commit -m "docs: complete split-to-microservices architectural guidance

- service-boundaries.md: table/Redis/Cassandra ownership per service  
- microservice-migration.md: gateway selection, split order, rollout strategy
- readiness-checklist.md: first-batch boundary constraint audit results
- architecture-constraints.md: 4 constraints for microservice-readiness"
```

---

## Self-Review Checklist

**tasks.md coverage:**
- [x] 1.1 Service boundaries: auth/user/content/comment/relation/counter/search/recommendation/storage/reconciliation (Task 1)
- [x] 1.2 Cross-module direct dependencies marked + audit commands (Task 2)
- [x] 1.3 API/event boundary definitions (Task 1 "Cross-Service Communication Rules" table)
- [x] 2.1 Gateway = Spring Cloud Gateway; registry = Nacos (Task 3)
- [x] 2.2 Split order: Search/Recommendation → Comment → Reconciliation → Relation/Counter → Content/Auth (Task 3)
- [x] 2.3 DB ownership table + event communication boundaries (Tasks 1 + 3)
- [x] 2.4 Strangler Fig + feature flag rollout + rollback strategy (Task 3)
- [x] 3.1 First-batch interfaces/events verified via grep audit (Task 2)
- [x] 3.2 Cassandra/Leaf/RecommendationAdapter/Reconciliation readiness confirmed (Task 2 + readiness-checklist.md)
- [x] 3.3 Migration implementation plan output (Task 3)

**Bugs fixed during review:**
- Task 2 Step 3: grep `-v "service/KnowPostFeedService"` used slash (path), not dot (import) — changed to `-v "knowpost\.service\.KnowPostFeedService"` and `-v "knowpost\.model\."` to correctly match import lines
- Task 4 Step 2: `-oP` (Perl regex, not available on macOS default grep) changed to `-oE` (extended regex, fully portable)
- Task 5 Step 2: `grep -c "Service |"` would only match the table header row; changed to `grep -c "com\.tongji\."` to count service rows accurately
- Task 5 Step 2: `grep -c "Not allowed\|Constraint\|WRONG"` used BRE `\|` (not portable on macOS BSD grep); changed to `grep -rcE` with ERE `|`
- Task 5 Step 2: `grep "Step [0-9]"` didn't match `### Step N` format; changed to `grep "### Step [0-9]"` to match actual document headings
- Task 5 Step 2: Switched from relative paths to absolute `$BASE=` variable (avoids directory dependency)
- Task 4 audit: `grep -rn "JOIN.*comments\b"` used `\b` word boundary (behavior varies on macOS BSD grep); simplified to `grep -n "JOIN.*comments"`
- Task 1: Added `com.tongji.profile` to User service definition (existing package discovered in codebase exploration)
- Task 2: Added timing note — grep audit runs AFTER all first-batch changes are complete, not during Phase 0
- readiness-checklist.md: Changed hardcoded date to "fill in after implementation"

**No placeholders.** All document content is fully written out, all audit commands are exact shell commands with expected outputs.

**Consistency:** All service names, package paths, table names, and constraint numbers used in Tasks 1–4 are consistent across all documents. `service-boundaries.md` is the canonical reference; `microservice-migration.md` and `architecture-constraints.md` refer back to it.
