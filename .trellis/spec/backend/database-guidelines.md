# Database Guidelines

> Database patterns and conventions for this project.

---

## Overview

<!--
Document your project's database conventions here.

Questions to answer:
- What ORM/query library do you use?
- How are migrations managed?
- What are the naming conventions for tables/columns?
- How do you handle transactions?
-->

(To be filled by the team)

---

## Query Patterns

<!-- How should queries be written? Batch operations? -->

(To be filled by the team)

---

## Migrations

<!-- How to create and run migrations -->

(To be filled by the team)

---

## Naming Conventions

<!-- Table names, column names, index names -->

(To be filled by the team)

---

## Common Mistakes

<!-- Database-related mistakes your team has made -->

## Scenario: Event-Derived Notification Inbox

### 1. Scope / Trigger
- Trigger: adding or changing in-site notifications that are derived from Kafka / Outbox events and persisted in MySQL.
- Applies when one write path must support notification list, unread count, mark-one-read, and mark-all-read without a second unread store.

### 2. Signatures
- Notification table signature:

```sql
CREATE TABLE notifications (
  id BIGINT UNSIGNED NOT NULL,
  recipient_user_id BIGINT UNSIGNED NOT NULL,
  actor_user_id BIGINT UNSIGNED NOT NULL,
  type VARCHAR(32) NOT NULL,
  entity_type VARCHAR(32) NOT NULL,
  entity_id VARCHAR(64) NOT NULL,
  second_entity_type VARCHAR(32) DEFAULT NULL,
  second_entity_id BIGINT UNSIGNED DEFAULT NULL,
  event_key VARCHAR(128) NOT NULL,
  aggregate_count INT NOT NULL DEFAULT 1,
  window_start DATETIME(3) DEFAULT NULL,
  window_end DATETIME(3) DEFAULT NULL,
  is_read TINYINT NOT NULL DEFAULT 0,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  read_at DATETIME(3) DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_notification_event_key (event_key),
  KEY idx_notification_recipient_created (recipient_user_id, created_at, id),
  KEY idx_notification_recipient_read (recipient_user_id, is_read, id)
);
```

- Mapper signatures:

```java
int insert(Notification notification);
List<Notification> listByRecipient(long recipientUserId, LocalDateTime cursorCreatedAt, Long cursorId, int limit);
int countUnread(long recipientUserId);
int markRead(long id, long recipientUserId, LocalDateTime readAt);
int markAllRead(long recipientUserId, LocalDateTime readAt);
```

### 3. Contracts
- `notifications` is the single source of truth for both inbox list and unread count.
- Do not maintain a second Redis or summary-table unread counter for the same inbox.
- Idempotency must be expressed as `event_key`, not by probing business tables.
- Follow notifications use the outbox row id as the idempotency boundary: `follow:outbox:{id}`.
- Comment notifications use the created comment id as the idempotency boundary: `comment:create:{commentId}`.
- Like notifications do not write one MySQL row per like. Aggregate by `recipient + entity_type + entity_id + 5-minute window`, then persist one row with `event_key = like:bucket:{recipient}:{entityType}:{entityId}:{windowStart}`.
- Duplicate delivery of the same positive like event may be deduped, but unlike-then-relike is a new event instance and must be able to form a new aggregate.
- Self-notifications are dropped at write time by comparing `recipient_user_id` and `actor_user_id`.

### 4. Validation & Error Matrix
- Duplicate `event_key` insert -> ignore duplicate write; do not fail the upstream business flow.
- `recipient_user_id == actor_user_id` -> drop notification.
- Mark-read target missing or owned by another user -> reject the request.
- Empty inbox page -> return empty items and `hasMore = false`.
- Invalid or repeated like delivery -> dedupe before aggregate count increments.

### 5. Good/Base/Bad Cases
- Good: one top-level comment creates one notification row for the post author and updates unread count through the same table.
- Good: ten likes on the same post within one 5-minute window become one notification row with `aggregate_count = 10`.
- Base: `markAllRead` only updates unread rows for the current recipient.
- Bad: maintain unread count in Redis while the list reads MySQL.
- Bad: use `relationId` as the follow notification idempotency key; unfollow-then-refollow can reuse the relation row and would collapse a new notification.
- Bad: write one MySQL notification row for every hot-post like.

### 6. Tests Required
- Consumer tests for follow, comment, and like notification creation.
- Contract test proving reply comments notify only the replied comment author, not the post author again.
- Flush test proving duplicate delivery of the same like event does not double-increment the aggregate bucket.
- Controller / service tests for pagination, unread count, mark-one-read, and mark-all-read.
- Regression tests proving existing follow/comment/like flows still pass after the notification module is added.

### 7. Wrong vs Correct

#### Wrong

```java
notificationMapper.insert(oneRowPerLike(event));
redisTemplate.opsForValue().increment("notif:unread:" + recipientUserId);
```

#### Correct

```java
Boolean accepted = redisTemplate.opsForValue()
        .setIfAbsent("notif:like:event:" + event.getEventId(), "1", EVENT_DEDUPE_TTL);
if (!Boolean.TRUE.equals(accepted)) {
    return;
}
redisTemplate.opsForHash().increment(bucketKey, "count", 1);
// flush later into one MySQL notification row
```

## Scenario: Time-Cursor Reconciliation Scans

### 1. Scope / Trigger
- Trigger: backend reconciliation scans over rows ordered by time plus id.
- Use this when a scanner cannot use a monotonic numeric id alone, for example settled auction windows ordered by `settled_at, id`.

### 2. Signatures
- Checkpoint table column: `reconciliation_checkpoint.last_scanned_at DATETIME(3) NULL`.
- Mapper method:

```java
int updateTimeCheckpoint(String scanType, Instant lastScannedAt, Long lastScannedId);
```

- Source query shape:

```sql
WHERE status = 'SETTLED'
  AND settled_at IS NOT NULL
  AND settled_at >= #{lookbackStart}
  AND (
    #{lastSettledAt} IS NULL
    OR settled_at > #{lastSettledAt}
    OR (settled_at = #{lastSettledAt} AND id > #{lastWindowId})
  )
ORDER BY settled_at ASC, id ASC
LIMIT #{batchSize}
```

### 3. Contracts
- `last_scanned_at` and `last_scanned_id` are one cursor pair.
- On a non-empty batch, advance both fields to the last row in result order.
- On an empty batch, do not reset the time cursor. Resetting causes repeated lookback scans and hides bad scan design behind task dedupe.
- Required index for settled window scans: `(status, settled_at, id)`.
- Config key for bounded history: `promotion.bprime.settled-compensation-lookback-seconds`.

### 4. Validation & Error Matrix
- Source row has `settled_at IS NULL` -> exclude it from settled-window scan.
- Batch empty -> no checkpoint write.
- Batch non-empty -> write exact last row cursor.
- Cursor pair missing -> start from configured lookback.

### 5. Good/Base/Bad Cases
- Good: two rows share `settled_at`; the scanner uses `id > lastWindowId` to continue deterministically.
- Base: one batch finishes; checkpoint stores the last row's `settled_at` and `id`.
- Bad: empty batch resets checkpoint to null/0 and the next schedule rescans the lookback window.

### 6. Tests Required
- Assert mapper receives `(lastScannedAt, lastScannedId, lookbackStart, batchSize)`.
- Assert empty result does not call `updateTimeCheckpoint`.
- Assert non-empty result advances to the last row.
- Assert SQL/schema contains the supporting `(status, settled_at, id)` index.

### 7. Wrong vs Correct

#### Wrong

```java
if (windows.isEmpty()) {
    checkpointMapper.updateTimeCheckpoint(scanType, null, 0L);
    return;
}
```

#### Correct

```java
if (windows.isEmpty()) {
    return;
}
checkpointMapper.updateTimeCheckpoint(scanType, last.getSettledAt(), last.getId());
```
